package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.MsRun;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-25 (design §4.4, D-13): the remaining write losses of the split layout, audited against the reference outputs.
 * Each of current small.unpacked, has_uv, diaPASEF.ref and Example_Processed.img is read, written SPLIT and read back:
 * <ul>
 *   <li>(b) what mzPeakJ reads back equals what it read from the fixture (Canonical forms, exact arrays): spectrum
 *       descriptions, signal arrays and peaks with their extra arrays, chromatograms with their extra arrays, units
 *       and Params, wavelength spectra, scan settings and file metadata.
 * </ul>
 */
class WriteLossAuditTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);

    static Stream<String> fixtures() {
        return Stream.of("small.unpacked.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak",
                "Example_Processed.img.mzpeak");
    }

    // ---- (b) read, write, read ----------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void whatIsReadBackEqualsWhatWasRead(String name, @TempDir Path tmp) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(name));
        Source back = ForwardBackwardCorpusTest.readSource(write(source, tmp.resolve("out.mzpeak")));

        assertThat(Canonical.lines(descriptions(back.spectra), Canonical::describe)).as("%s descriptions", name)
                .containsExactlyElementsOf(expectedDescriptions(name, source.spectra));
        assertThat(back.spectra).hasSameSizeAs(source.spectra);
        for (int i = 0; i < source.spectra.size(); i++) {
            Spectrum a = source.spectra.get(i);
            Spectrum b = back.spectra.get(i);
            String what = name + " spectrum " + i;
            assertThat(b.mz()).as("%s mz", what).isEqualTo(a.mz());
            assertThat(b.intensity()).as("%s intensity", what).isEqualTo(a.intensity());
            assertThat(b.peaks()).as("%s peaks", what).isEqualTo(a.peaks());
            assertArraysEqual(b.arrays(), a.arrays(), what + " arrays");
            assertArraysEqual(b.peakArrays(), a.peakArrays(), what + " peakArrays");
        }

        assertThat(Canonical.lines(back.chromatograms, Canonical::describe)).as("%s chromatograms", name)
                .containsExactlyElementsOf(Canonical.lines(source.chromatograms, Canonical::describe));
        for (int i = 0; i < source.chromatograms.size(); i++) {
            Chromatogram a = source.chromatograms.get(i);
            Chromatogram b = back.chromatograms.get(i);
            String what = name + " chromatogram " + a.id();
            assertThat(b.time()).as("%s time", what).isEqualTo(a.time());
            assertThat(b.intensity()).as("%s intensity", what).isEqualTo(a.intensity());
            assertArraysEqual(b.arrays(), a.arrays(), what + " arrays");
            assertThat(b.intensityUnit()).as("%s intensityUnit", what).isEqualTo(a.intensityUnit());
            assertThat(b.timeUnit()).as("%s timeUnit", what).isEqualTo(a.timeUnit());
            assertThat(b.polarity()).as("%s polarity", what).isEqualTo(a.polarity());
            assertThat(b.dataProcessingRef()).as("%s dataProcessingRef", what).isEqualTo(a.dataProcessingRef());
            assertThat(Canonical.lines(b.parameters(), Canonical::param)).as("%s parameters", what)
                    .isEqualTo(Canonical.lines(a.parameters(), Canonical::param));
            assertThat(Canonical.lines(b.precursors(), Canonical::precursor)).as("%s precursors", what)
                    .isEqualTo(Canonical.lines(a.precursors(), Canonical::precursor));
        }

        assertThat(Canonical.lines(back.wavelengthSpectra, Canonical::describe)).as("%s wavelength spectra", name)
                .containsExactlyElementsOf(Canonical.lines(source.wavelengthSpectra, Canonical::describe));
        for (int i = 0; i < source.wavelengthSpectra.size(); i++) {
            WavelengthSpectrum a = source.wavelengthSpectra.get(i);
            WavelengthSpectrum b = back.wavelengthSpectra.get(i);
            assertThat(b.wavelength()).as("%s wavelength spectrum %d wavelength", name, i).isEqualTo(a.wavelength());
            assertThat(b.intensity()).as("%s wavelength spectrum %d intensity", name, i).isEqualTo(a.intensity());
        }

        assertThat(back.scanSettings).as("%s scan settings", name).isEqualTo(source.scanSettings);
        assertThat(back.fileMetadata).as("%s file metadata", name)
                .isEqualTo(withWriterDefaults(source.fileMetadata));
    }

    /** The pins of (b): what the round trip above compares holds the arrays and units the design names. */
    @Test
    void theRoundTripCoversTheExtraArraysUnitsAndWavelengthSpectra() {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Chromatogram tic = small.chromatograms.get(0);
        assertThat(tic.typeCurie()).isEqualTo("MS:1000235");
        assertThat(tic.arrays().keySet()).containsExactly("ms level");
        assertThat(tic.arrays().get("ms level")).hasSize(48);

        Source dia = ForwardBackwardCorpusTest.readSource(Fixtures.current("diaPASEF.ref.mzpeak"));
        assertThat(dia.spectra.stream().filter(s -> s.peakArrays().containsKey("MS:1003006")).count())
                .as("diaPASEF.ref spectra with an MS:1003006 peak array").isPositive();

        Source uv = ForwardBackwardCorpusTest.readSource(Fixtures.current("has_uv.mzpeak"));
        assertThat(uv.wavelengthSpectra).hasSize(520);
        assertThat(uv.chromatograms).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", "MS:1000812");
        assertThat(uv.chromatograms).extracting(Chromatogram::intensityUnit)
                .containsExactly("MS:1000131", "UO:0000269");
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static Path write(Source s, Path out) {
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, SPLIT);
        return out;
    }

    /**
     * The Canonical lines of the descriptions read from fixture {@code name}, as they read back. The ion mobility
     * Params of diaPASEF.ref's scans read back with the name the reader gives MS:1002815 (V-24, WR-5c: equal apart
     * from the name).
     */
    private static List<String> expectedDescriptions(String name, List<Spectrum> spectra) {
        List<String> lines = Canonical.lines(descriptions(spectra), Canonical::describe);
        if (!name.equals("diaPASEF.ref.mzpeak")) {
            return lines;
        }
        String stored = "(inverse reduced ion mobility|MS:1002815|";
        String named = "(inverse reduced ion mobility drift time|MS:1002815|";
        List<String> out = new ArrayList<>(lines.size());
        int renamed = 0;
        for (String line : lines) {
            renamed += line.split(Pattern.quote(stored), -1).length - 1;
            out.add(line.replace(stored, named));
        }
        assertThat(renamed).as("ion mobility Params of diaPASEF.ref's scans").isEqualTo(8);
        return out;
    }

    /**
     * {@code meta} as the writer completes it: a run without {@code default_data_processing_id} gets the first data
     * processing's id, the spec requiring one (diaPASEF.ref's run has none).
     */
    private static FileMetadata withWriterDefaults(FileMetadata meta) {
        MsRun run = meta.run();
        if (run == null || run.defaultDataProcessingId() != null || meta.dataProcessingMethods().isEmpty()) {
            return meta;
        }
        MsRun completed = new MsRun(run.id(), run.defaultInstrumentId(), meta.dataProcessingMethods().get(0).id(),
                run.defaultSourceFileId(), run.startTime(), run.parameters());
        return new FileMetadata(meta.fileDescription(), meta.instrumentConfigurations(), meta.dataProcessingMethods(),
                meta.samples(), meta.software(), completed);
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static void assertArraysEqual(Map<String, double[]> actual, Map<String, double[]> expected, String what) {
        assertThat(actual.keySet()).as("%s keys", what).containsExactlyElementsOf(expected.keySet());
        for (Map.Entry<String, double[]> e : expected.entrySet()) {
            assertThat(actual.get(e.getKey())).as("%s %s", what, e.getKey()).isEqualTo(e.getValue());
        }
    }
}
