package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D13-28 (X-40, upstream-issues.md R-16): the Rust reference reader parses the accession and unit of every param of a
 * {@code parameters} list as an mzdata CURIE ({@code ParameterVisitor} through {@code AnyCURIEArray} and
 * {@code UnitArray}, curie_array_visitor.rs:34, 70, 257, {@code parse().unwrap()}): a prefix, a colon and a number that
 * fits u32; it panics on anything else. So the split layout refuses such an accession or unit of a param of a
 * spectrum, its scans and precursors (isolation window, activation, selected ions), a chromatogram and its
 * precursors, a wavelength spectrum and its scans, naming the entity, its index, the param's position and the field,
 * before anything is written, in a batch write and in a session. Until X-40 these were written as given (D13-26). The
 * packed layout keeps writing them as given; a null unit and a CURIE such as UO:0000010 are written in either.
 *
 * <p>Fixtures: small.unpacked (spectra with scans and precursors) and has_uv (chromatograms, wavelength spectra with
 * scans) as read, one param added where a case says.
 */
class SplitParamCurieTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final Path HAS_UV = Fixtures.current("has_uv.mzpeak");
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
    private static final String WHY = " is not a CURIE the Rust reference reader parses (PREFIX:number, the number at"
            + " most 4294967295; it panics on any other value): give a CV term such as UO:0000010 (second), or null";
    /** A param with the unit {@code seconds}, which C6 reads and D13-26 wrote as given. */
    static final Param SECONDS = new Param("time", "MS:1000016", 1.5, "seconds");
    /** A param with the accession {@code foo}, which has no colon. */
    static final Param FOO = new Param("foo", "foo", null, null);
    /** A param with the accession {@code MS:abc}, whose local part is no number. */
    static final Param ABC = new Param("abc", "MS:abc", null, null);

    @TempDir
    Path tmp;

    /**
     * Each of {@link #SECONDS}, {@link #FOO} and {@link #ABC} on a spectrum, a scan, an isolation window, an
     * activation and a selected ion of small.unpacked, written split as a directory and as an archive, is refused
     * with the entity, the param's position in its list and the field, and nothing is written.
     */
    @Test
    void aSpectrumParamThatIsNoRustCurieIsRefusedInABatchWrite() {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        int msn = firstMsn(small.spectra);
        Spectrum s0 = small.spectra.get(0);
        int spectrumParams = s0.description().parameters().size();
        int scanParams = s0.description().scans().get(0).parameters().size();
        assertRefusedSpectrum(small, 0, s -> withParam(s, SECONDS),
                "spectrum 0: param " + spectrumParams + " unit seconds" + WHY);
        assertRefusedSpectrum(small, 0, s -> withParam(s, FOO),
                "spectrum 0: param " + spectrumParams + " accession foo" + WHY);
        assertRefusedSpectrum(small, 0, s -> withScanParam(s, ABC),
                "spectrum 0: scan 0: param " + scanParams + " accession MS:abc" + WHY);
        assertRefusedSpectrum(small, 0, s -> withScanParam(s, SECONDS),
                "spectrum 0: scan 0: param " + scanParams + " unit seconds" + WHY);
        Precursor p = small.spectra.get(msn).description().precursors().get(0);
        assertRefusedSpectrum(small, msn, s -> withPrecursorParam(s, 0, FOO), "spectrum " + msn
                + ": precursor 0 isolation window: param " + p.isolationWindow().parameters().size()
                + " accession foo" + WHY);
        assertRefusedSpectrum(small, msn, s -> withPrecursorParam(s, 1, SECONDS), "spectrum " + msn
                + ": precursor 0 activation: param " + p.activation().parameters().size() + " unit seconds" + WHY);
        assertRefusedSpectrum(small, msn, s -> withPrecursorParam(s, 2, ABC), "spectrum " + msn
                + ": precursor 0 selected ion 0: param " + p.selectedIons().get(0).parameters().size()
                + " accession MS:abc" + WHY);
    }

    /**
     * Each of the three values on a chromatogram, a chromatogram's precursor, a wavelength spectrum and a wavelength
     * scan of has_uv, written split, is refused and nothing is written.
     */
    @Test
    void aChromatogramOrWavelengthParamThatIsNoRustCurieIsRefusedInABatchWrite() {
        Source hasUv = ForwardBackwardCorpusTest.readSource(HAS_UV);
        int chromatogramParams = hasUv.chromatograms.get(1).parameters().size();
        assertRefusedChromatogram(hasUv, c -> withParam(c, SECONDS),
                "chromatogram 1: param " + chromatogramParams + " unit seconds" + WHY);
        assertRefusedChromatogram(hasUv, c -> withParam(c, FOO),
                "chromatogram 1: param " + chromatogramParams + " accession foo" + WHY);
        assertRefusedChromatogram(hasUv, c -> withPrecursor(c, ABC),
                "chromatogram 1: precursor 0 activation: param 0 accession MS:abc" + WHY);
        WavelengthSpectrum w2 = hasUv.wavelengthSpectra.get(2);
        int waveParams = w2.parameters().size();
        int waveScanParams = w2.scans().get(0).parameters().size();
        assertRefusedWavelength(hasUv, w -> withParam(w, ABC),
                "wavelength spectrum 2: param " + waveParams + " accession MS:abc" + WHY);
        assertRefusedWavelength(hasUv, w -> withParam(w, SECONDS),
                "wavelength spectrum 2: param " + waveParams + " unit seconds" + WHY);
        assertRefusedWavelength(hasUv, w -> withScanParam(w, FOO),
                "wavelength spectrum 2: scan 0: param " + waveScanParams + " accession foo" + WHY);
    }

    /**
     * A session refuses the same params as each entity is added, before anything of it is written: spectrum 0 with
     * {@link #SECONDS}, a scan with {@link #FOO}, chromatogram 0 with {@link #ABC} and wavelength spectrum 0 with
     * {@link #SECONDS} and, on a scan, {@link #ABC}; an entity without them is added after a refusal.
     */
    @Test
    void aSessionRefusesAParamThatIsNoRustCurieWhenTheEntityIsAdded() {
        Source source = ForwardBackwardCorpusTest.readSource(HAS_UV);
        Spectrum s0 = source.spectra.get(0);
        Chromatogram c0 = source.chromatograms.get(0);
        WavelengthSpectrum w0 = source.wavelengthSpectra.get(0);
        Path out = tmp.resolve("session.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(out, source.fileMetadata, source.scanSettings,
                new LinkedHashSet<>(), SPLIT)) {
            assertThatThrownBy(() -> session.addSpectrum(withParam(s0, SECONDS)))
                    .isInstanceOf(MzPeakException.class).hasMessage("spectrum 0: param "
                            + s0.description().parameters().size() + " unit seconds" + WHY);
            assertThatThrownBy(() -> session.addSpectrum(withScanParam(s0, FOO)))
                    .isInstanceOf(MzPeakException.class).hasMessage("spectrum 0: scan 0: param "
                            + s0.description().scans().get(0).parameters().size() + " accession foo" + WHY);
            session.addSpectrum(s0);
            assertThatThrownBy(() -> session.addChromatogram(withParam(c0, ABC)))
                    .isInstanceOf(MzPeakException.class).hasMessage("chromatogram 0: param "
                            + c0.parameters().size() + " accession MS:abc" + WHY);
            assertThatThrownBy(() -> session.addWavelengthSpectrum(withParam(w0, SECONDS)))
                    .isInstanceOf(MzPeakException.class).hasMessage("wavelength spectrum 0: param "
                            + w0.parameters().size() + " unit seconds" + WHY);
            assertThatThrownBy(() -> session.addWavelengthSpectrum(withScanParam(w0, ABC)))
                    .isInstanceOf(MzPeakException.class).hasMessage("wavelength spectrum 0: scan 0: param "
                            + w0.scans().get(0).parameters().size() + " accession MS:abc" + WHY);
            session.addChromatogram(c0);
            session.addWavelengthSpectrum(w0);
            session.finish();
        }
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(1);
            assertThat(reader.getSpectrumAt(0).description().parameters()).doesNotContain(SECONDS);
        }
    }

    /**
     * The packed layout keeps D13-26: a spectrum param with the unit {@code seconds} or the accession {@code MS:abc}
     * is written and reads back as given.
     */
    @Test
    void thePackedLayoutWritesSuchParamsAsGiven() {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        spectra.set(3, withParam(withParam(spectra.get(3), SECONDS), ABC));
        Path out = tmp.resolve("packed.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, PACKED);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrumAt(3).description().parameters()).contains(SECONDS, ABC);
        }
    }

    /**
     * A CURIE whose number fits u32, with leading zeros or not, and a null unit are written split on every list and
     * read back: UO:0000010 and UO:4294967295 as units, MS:1000786 as accession.
     */
    @Test
    void curiesAndNullUnitsAreWrittenSplit() {
        Param second = new Param("x", "MS:1000786", 1.5, "UO:0000010");
        Param largest = new Param("y", "MS:1000786", 2.5, "UO:4294967295");
        Param unitless = new Param("z", null, "v", null);
        Source hasUv = ForwardBackwardCorpusTest.readSource(HAS_UV);
        List<Spectrum> spectra = new ArrayList<>(hasUv.spectra);
        spectra.set(0, withScanParam(withParam(withParam(spectra.get(0), second), unitless), largest));
        List<Chromatogram> chromatograms = new ArrayList<>(hasUv.chromatograms);
        chromatograms.set(0, withParam(chromatograms.get(0), second));
        List<WavelengthSpectrum> waves = new ArrayList<>(hasUv.wavelengthSpectra);
        waves.set(0, withScanParam(withParam(waves.get(0), largest), unitless));
        Path out = tmp.resolve("curies.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, chromatograms, waves, hasUv.scanSettings, hasUv.fileMetadata,
                SPLIT);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            SpectrumDescription d = reader.getSpectrumAt(0).description();
            assertThat(d.parameters()).contains(second, unitless);
            assertThat(d.scans().get(0).parameters()).contains(largest);
            assertThat(reader.chromatograms().get(0).parameters()).contains(second);
            WavelengthSpectrum w = reader.getWavelengthSpectrum(0).orElseThrow();
            assertThat(w.parameters()).contains(largest);
            assertThat(w.scans().get(0).parameters()).contains(unitless);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private void assertRefusedSpectrum(Source source, int at, UnaryOperator<Spectrum> change, String message) {
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        spectra.set(at, change.apply(spectra.get(at)));
        Path dir = tmp.resolve("refused.mzpeak");
        Path archive = tmp.resolve("refused.zip.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(dir, spectra, source.chromatograms,
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(archive, spectra, source.chromatograms,
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(dir).doesNotExist();
        assertThat(archive).doesNotExist();
    }

    private void assertRefusedChromatogram(Source source, UnaryOperator<Chromatogram> change, String message) {
        List<Chromatogram> chromatograms = new ArrayList<>(source.chromatograms);
        chromatograms.set(1, change.apply(chromatograms.get(1)));
        Path out = tmp.resolve("refused.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, source.spectra, chromatograms,
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(out).doesNotExist();
    }

    private void assertRefusedWavelength(Source source, UnaryOperator<WavelengthSpectrum> change, String message) {
        List<WavelengthSpectrum> waves = new ArrayList<>(source.wavelengthSpectra);
        waves.set(2, change.apply(waves.get(2)));
        Path out = tmp.resolve("refused.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, waves,
                source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class).hasMessage(message);
        assertThat(out).doesNotExist();
    }

    /** The position of the first spectrum of {@code spectra} with a precursor. */
    private static int firstMsn(List<Spectrum> spectra) {
        int i = 0;
        while (spectra.get(i).description().precursors().isEmpty()) {
            i++;
        }
        return i;
    }

    private static List<Param> plus(List<Param> params, Param param) {
        List<Param> out = new ArrayList<>(params);
        out.add(param);
        return out;
    }

    private static Spectrum withDescription(Spectrum s, List<ScanEvent> scans, List<Precursor> precursors,
                                            List<Param> params) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans, precursors, params,
                d.dataProcessingRef()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                s.intensityUnit(), s.peakIntensityUnit(), s.auxiliaryArrays());
    }

    static Spectrum withParam(Spectrum s, Param param) {
        SpectrumDescription d = s.description();
        return withDescription(s, d.scans(), d.precursors(), plus(d.parameters(), param));
    }

    /** {@code s} with {@code param} added to its first scan. */
    static Spectrum withScanParam(Spectrum s, Param param) {
        SpectrumDescription d = s.description();
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        scans.set(0, withParam(scans.get(0), param));
        return withDescription(s, scans, d.precursors(), d.parameters());
    }

    /**
     * {@code s} with {@code param} added to its first precursor's isolation window ({@code where} 0), activation (1)
     * or first selected ion (2).
     */
    private static Spectrum withPrecursorParam(Spectrum s, int where, Param param) {
        SpectrumDescription d = s.description();
        List<Precursor> precursors = new ArrayList<>(d.precursors());
        Precursor p = precursors.get(0);
        IsolationWindow w = p.isolationWindow();
        IsolationWindow window = where == 0 ? new IsolationWindow(w.targetMz(), w.lowerOffset(), w.upperOffset(),
                plus(w.parameters(), param)) : w;
        Activation activation = where == 1 ? new Activation(plus(p.activation().parameters(), param))
                : p.activation();
        List<SelectedIon> ions = new ArrayList<>(p.selectedIons());
        if (where == 2) {
            SelectedIon ion = ions.get(0);
            ions.set(0, new SelectedIon(ion.mz(), ion.charge(), ion.intensity(), plus(ion.parameters(), param)));
        }
        precursors.set(0, new Precursor(p.precursorIndex(), p.precursorId(), window, ions, activation));
        return withDescription(s, d.scans(), precursors, d.parameters());
    }

    private static ScanEvent withParam(ScanEvent scan, Param param) {
        return new ScanEvent(scan.startTime(), scan.injectionTime(), scan.filterString(),
                scan.presetScanConfiguration(), scan.instrumentConfigurationRef(), scan.spectrumReference(),
                scan.scanWindows(), plus(scan.parameters(), param));
    }

    static Chromatogram withParam(Chromatogram c, Param param) {
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), plus(c.parameters(), param), c.precursors(),
                c.timeUnit(), c.products(), c.auxiliaryArrays());
    }

    /** {@code c} with one precursor without isolation window whose activation carries {@code param}. */
    private static Chromatogram withPrecursor(Chromatogram c, Param param) {
        Precursor p = new Precursor(null, null, null, List.of(), new Activation(List.of(param)));
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), List.of(p), c.timeUnit(),
                c.products(), c.auxiliaryArrays());
    }

    static WavelengthSpectrum withParam(WavelengthSpectrum w, Param param) {
        return new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity(),
                plus(w.parameters(), param), w.scans(), w.auxiliaryArrays());
    }

    /** {@code w} with {@code param} added to its first scan. */
    static WavelengthSpectrum withScanParam(WavelengthSpectrum w, Param param) {
        List<ScanEvent> scans = new ArrayList<>(w.scans());
        scans.set(0, withParam(scans.get(0), param));
        return new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity(),
                w.parameters(), scans, w.auxiliaryArrays());
    }
}
