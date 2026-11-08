package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packed (pin-era) metadata layout written by {@link PackedMetadataWriter}: a part of a spectrum, of its first
 * scan, of its primary precursor, of a chromatogram or of a wavelength spectrum that is absent is left out of its
 * facet, and the dataset reads back with that part absent and everything else as given.
 *
 * <p>Oracles: legacy small.unpacked and legacy has_uv, read with {@link MzPeakReader} and changed where a test says
 * so, written in the packed layout, and read back from the written members and with {@link MzPeakReader}.
 */
class PackedMetadataWriterTest {

    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.legacy("has_uv.mzpeak");
    private static final String SPECTRA = "spectra_metadata.parquet";

    /**
     * Spectrum 0 of legacy small without id and with an unknown polarity, its scan without start time: the row has
     * no id and no polarity, the scan's start time is the spectrum's retention time, and the spectrum reads back so.
     */
    @Test
    void aSpectrumWithoutIdPolarityOrScanStartTimeLeavesThoseColumnsOut(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        SpectrumDescription d = spectra.get(0).description();
        ScanEvent scan = d.scans().get(0);
        ScanEvent untimed = new ScanEvent(Double.NaN, scan.injectionTime(), scan.filterString(),
                scan.presetScanConfiguration(), scan.instrumentConfigurationRef(), scan.spectrumReference(),
                scan.scanWindows(), scan.parameters());
        spectra.set(0, with(spectra.get(0), new SpectrumDescription(d.index(), null, d.msLevel(), d.retentionTime(),
                Polarity.UNKNOWN, d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), List.of(untimed),
                d.precursors(), d.parameters())));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, PACKED);

        List<Group> rows = rows(out.resolve(SPECTRA));
        Group spectrum = rows.get(0).getGroup("spectrum", 0);
        assertThat(ParquetGroups.has(spectrum, "id")).isFalse();
        assertThat(ParquetGroups.has(spectrum, "MS_1000465_scan_polarity")).isFalse();
        assertThat(ParquetGroups.optDouble(rows.get(0).getGroup("scan", 0),
                "MS_1000016_scan_start_time_unit_UO_0000031")).isEqualTo(d.retentionTime());
        Group second = rows.get(1).getGroup("spectrum", 0);
        assertThat(ParquetGroups.optString(second, "id")).isEqualTo(spectra.get(1).description().id());
        assertThat(ParquetGroups.optInt(second, "MS_1000465_scan_polarity")).isEqualTo(1);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        SpectrumDescription first = back.spectra.get(0).description();
        assertThat(first.id()).isNull();
        assertThat(first.polarity()).isEqualTo(Polarity.UNKNOWN);
        assertThat(first.retentionTime()).isEqualTo(d.retentionTime());
        assertThat(first.scans()).hasSize(1);
        assertThat(first.scans().get(0).startTime()).isEqualTo(d.retentionTime());
        assertThat(first.scans().get(0).filterString()).isEqualTo(scan.filterString());
        DescriptionAssert.assertEqual("legacy small after spectrum 0", descriptions(spectra.subList(1, 48)),
                descriptions(back.spectra.subList(1, 48)));
    }

    /**
     * The primary precursors of five MS2 spectra of legacy small, each without some of its parts: without
     * precursor_id, isolation window and activation, its ion with a charge; with an isolation window of only a lower
     * offset and without selected ions; with only an upper offset; with only Params; and with an isolation window
     * that has nothing in it. Each part that is absent is left out of the precursor facet, an isolation window
     * without anything in it included, and each precursor reads back as written.
     */
    @Test
    void aPrimaryPrecursorIsWrittenWithOnlyThePartsItHas(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        List<Integer> msn = msnPositions(spectra);
        Precursor original = spectra.get(msn.get(0)).description().primaryPrecursor();
        SelectedIon ion = original.primaryIon();
        Param userParam = new Param("isolation note", null, "narrow", null);

        Precursor bare = new Precursor(original.precursorIndex(), null, null,
                List.of(new SelectedIon(ion.mz(), 2, ion.intensity(), ion.parameters())), Activation.EMPTY);
        Precursor lowerOnly = new Precursor(original.precursorIndex(), original.precursorId(),
                new IsolationWindow(null, 1.5, null), List.of(), original.activation());
        Precursor upperOnly = new Precursor(original.precursorIndex(), original.precursorId(),
                new IsolationWindow(null, null, 2.5), original.selectedIons(), original.activation());
        Precursor paramsOnly = new Precursor(original.precursorIndex(), original.precursorId(),
                new IsolationWindow(null, null, null, List.of(userParam)), original.selectedIons(),
                original.activation());
        Precursor emptyWindow = new Precursor(original.precursorIndex(), original.precursorId(),
                new IsolationWindow(null, null, null), original.selectedIons(), original.activation());
        List<Precursor> written = List.of(bare, lowerOnly, upperOnly, paramsOnly, emptyWindow);
        for (int i = 0; i < written.size(); i++) {
            spectra.set(msn.get(i), withPrecursors(spectra.get(msn.get(i)), List.of(written.get(i))));
        }

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, PACKED);

        List<Group> rows = rows(out.resolve(SPECTRA));
        Group bareRow = rows.get(msn.get(0)).getGroup("precursor", 0);
        assertThat(ParquetGroups.has(bareRow, "precursor_id")).isFalse();
        assertThat(ParquetGroups.has(bareRow, "isolation_window")).isFalse();
        assertThat(ParquetGroups.has(bareRow, "activation")).isFalse();
        assertThat(ParquetGroups.optInt(rows.get(msn.get(0)).getGroup("selected_ion", 0), "MS_1000041_charge_state"))
                .isEqualTo(2);
        assertThat(ParquetGroups.has(rows.get(msn.get(1)), "selected_ion")).isFalse();
        Group lowerWindow = rows.get(msn.get(1)).getGroup("precursor", 0).getGroup("isolation_window", 0);
        assertThat(ParquetGroups.has(lowerWindow, "MS_1000827_isolation_window_target_mz")).isFalse();
        assertThat(ParquetGroups.optDouble(lowerWindow, "MS_1000828_isolation_window_lower_offset")).isEqualTo(1.5);
        assertThat(ParquetGroups.has(lowerWindow, "MS_1000829_isolation_window_upper_offset")).isFalse();
        assertThat(ParquetGroups.has(rows.get(msn.get(4)).getGroup("precursor", 0), "isolation_window")).isFalse();

        Source back = ForwardBackwardCorpusTest.readSource(out);
        for (int i = 0; i < written.size() - 1; i++) {
            assertThat(back.spectra.get(msn.get(i)).description().precursors()).as("precursors of MS2 spectrum %d", i)
                    .containsExactly(written.get(i));
        }
        assertThat(back.spectra.get(msn.get(4)).description().precursors()).containsExactly(new Precursor(
                original.precursorIndex(), original.precursorId(), null, original.selectedIons(),
                original.activation()));
    }

    /**
     * Chromatogram 1 of legacy has_uv without id and type, and wavelength spectrum 0 without id, time and lambda max:
     * their rows leave those columns out and they read back without them; the other entities keep theirs.
     */
    @Test
    void chromatogramsAndWavelengthSpectraWithoutIdTypeTimeOrLambdaMaxLeaveThoseColumnsOut(@TempDir Path tmp) {
        Source hasUv = ForwardBackwardCorpusTest.readSource(HAS_UV);
        List<Chromatogram> chromatograms = new ArrayList<>(hasUv.chromatograms);
        Chromatogram dad = chromatograms.get(1);
        chromatograms.set(1, new Chromatogram(dad.index(), null, null, dad.time(), dad.intensity(),
                dad.intensityUnit(), dad.arrays()));
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>(hasUv.wavelengthSpectra);
        WavelengthSpectrum w = wavelengthSpectra.get(0);
        wavelengthSpectra.set(0, new WavelengthSpectrum(w.index(), null, Double.NaN, null, w.wavelength(),
                w.intensity()));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, hasUv.spectra, chromatograms, wavelengthSpectra, hasUv.scanSettings,
                hasUv.fileMetadata, PACKED);

        List<Group> chromatogramRows = rows(out.resolve("chromatograms_metadata.parquet"));
        Group untyped = chromatogramRows.get(1).getGroup("chromatogram", 0);
        assertThat(ParquetGroups.has(untyped, "id")).isFalse();
        assertThat(ParquetGroups.has(untyped, "MS_1000626_chromatogram_type")).isFalse();
        assertThat(ParquetGroups.optLong(untyped, "MS_1003060_number_of_data_points")).isEqualTo(dad.size());
        Group tic = chromatogramRows.get(0).getGroup("chromatogram", 0);
        assertThat(ParquetGroups.optString(tic, "id")).isEqualTo("TIC");
        assertThat(ParquetGroups.optString(tic, "MS_1000626_chromatogram_type")).isEqualTo("MS:1000235");

        List<Group> wavelengthRows = rows(out.resolve("wavelength_spectra_metadata.parquet"));
        Group bare = wavelengthRows.get(0).getGroup("spectrum", 0);
        assertThat(ParquetGroups.has(bare, "id")).isFalse();
        assertThat(ParquetGroups.has(bare, "time")).isFalse();
        assertThat(ParquetGroups.has(bare, "MS_1003812_lambda_max_unit_UO:0000018")).isFalse();
        assertThat(ParquetGroups.optLong(bare, "MS_1003060_number_of_data_points")).isEqualTo(w.size());
        Group next = wavelengthRows.get(1).getGroup("spectrum", 0);
        WavelengthSpectrum w1 = wavelengthSpectra.get(1);
        assertThat(ParquetGroups.optString(next, "id")).isEqualTo(w1.id());
        assertThat(ParquetGroups.optDouble(next, "time")).isEqualTo(w1.time());
        assertThat(ParquetGroups.optDouble(next, "MS_1003812_lambda_max_unit_UO:0000018")).isEqualTo(w1.lambdaMax());

        Source back = ForwardBackwardCorpusTest.readSource(out);
        Chromatogram dadBack = back.chromatograms.get(1);
        assertThat(dadBack.id()).isNull();
        assertThat(dadBack.typeCurie()).isNull();
        assertThat(dadBack.time()).containsExactly(dad.time());
        assertThat(dadBack.intensity()).containsExactly(dad.intensity());
        assertThat(back.chromatograms.get(0).id()).isEqualTo("TIC");
        WavelengthSpectrum wBack = back.wavelengthSpectra.get(0);
        assertThat(wBack.id()).isNull();
        assertThat(wBack.time()).isNaN();
        assertThat(wBack.lambdaMax()).isNull();
        assertThat(wBack.wavelength()).containsExactly(w.wavelength());
        assertThat(wBack.intensity()).containsExactly(w.intensity());
        assertThat(back.wavelengthSpectra.get(1).id()).isEqualTo(w1.id());
        assertThat(back.wavelengthSpectra.get(1).lambdaMax()).isEqualTo(w1.lambdaMax());
    }

    private static List<Integer> msnPositions(List<Spectrum> spectra) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < spectra.size(); i++) {
            if (!spectra.get(i).description().precursors().isEmpty()) {
                out.add(i);
            }
        }
        return out;
    }

    private static Spectrum withPrecursors(Spectrum s, List<Precursor> precursors) {
        SpectrumDescription d = s.description();
        return with(s, new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters()));
    }

    private static Spectrum with(Spectrum s, SpectrumDescription d) {
        return new Spectrum(d, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
