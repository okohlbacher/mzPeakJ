package org.mzpeak.mzml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * JZ-8: direction B, mzML -> mzPeak -> mzML, on the four vendored mzML fixtures (mzML design 6.1, 12.4), under the
 * default time zone UTC, restored after each test. Each fixture is extracted, read by {@link MzMLReader} (M1),
 * converted by {@link MzMLConversion#toMzPeak} (defaults, archive) and back by {@link MzMLConversion#toMzML} (zlib),
 * checked by {@link MzMLAssert#valid} and {@link MzMLMappingCheck} (no violation), and read again (M3);
 * {@link ModelAssert} compares M1 and M3 strictly, arrays bit-exact, with exactly the exclusions of the B table of
 * design 12.4, each hiding the pinned number of differences. The warnings of both steps are pinned: the reader's limit
 * lines L-1 to L-6 and its loss lines, then the writer's limit lines L-8 and L-9 and its loss lines.
 */
class MzMLRoundTripBTest {

    @TempDir
    Path dir;

    private TimeZone zone;

    @BeforeEach
    void utc() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    /**
     * The B table of design 12.4: per fixture the exclusions with the differences each hides, the numbers of spectra,
     * wavelength spectra and chromatograms, the loss lines of toMzPeak after the reader's limit lines, and those of
     * toMzML after the writer's.
     * <ul>
     *   <li>tiny: A_26, its run start time 2007-06-27T15:23:45.00035 (no zone) written as 2007-06-27T15:23:45Z under
     *       UTC; the sic's product is dropped by MzMLReader already (B-27, a warning), so M1 has none;
     *   <li>small and TOFsulfas: none (TOFsulfas loses the scan window params other than the limits, B-17, in M1
     *       already);
     *   <li>diaPASEF: B_22, the m/z, intensity and ion mobility arrays of its 9 spectra (27 differences), which pwiz
     *       wrote in scan order and the mzPeak writer sorts by m/z; its 18 userParam units are lost in M1 already
     *       (L-4). Its B-14 line counts 18 unitless summary params and 8 unitless collision energies.
     * </ul>
     */
    static Stream<Arguments> fixtures() {
        String b3 = MzMLReader.B_3 + " (1)";
        return Stream.of(
                arguments(MzMLFixtures.TINY, Map.of(ModelAssert.A_26, 1), List.of(4L, 0L, 2L),
                        List.of(b3, MzMLReader.B_1 + " (2)",
                                String.format(MzMLReader.B_5, "[LCQ_x0020_Deca]") + " (1)",
                                MzMLReader.B_7 + " (1)",
                                MzMLReader.B_14_UNIT + " (4)",
                                MzMLReader.B_21_PROCESSING + " (10)",
                                String.format(MzMLReader.B_10, "spotID") + " (1)",
                                String.format(MzMLReader.B_10, "sourceFileRef") + " (1)",
                                MzMLReader.B_15 + " (3)",
                                MzMLReader.B_27 + " (1)"),
                        List.of(String.format(MzMLWriter.A_26, "2007-06-27T15:23:45.00035", "2007-06-27T15:23:45Z")
                                + " (1)")),
                arguments(MzMLFixtures.SMALL, Map.of(), List.of(48L, 0L, 1L),
                        List.of(b3, MzMLReader.B_1 + " (1)", MzMLReader.B_14_UNIT + " (48)"), List.of()),
                arguments(MzMLFixtures.TOFSULFAS, Map.of(), List.of(212L, 520L, 2L),
                        List.of(b3, MzMLReader.B_1 + " (1)", MzMLReader.B_17_PARAMS + " (212)",
                                MzMLReader.B_11_INDEX + " (520)"), List.of()),
                arguments(MzMLFixtures.DIAPASEF, Map.of(ModelAssert.B_22, 3 * 9), List.of(9L, 0L, 2L),
                        List.of(b3, MzMLReader.B_1 + " (1)", MzMLReader.B_15 + " (11)",
                                MzMLReader.B_14_UNIT + " (26)"), List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void comesBackEqualUpToTheExclusionsOfItsFixture(String name, Map<Exclusion, Integer> hidden, List<Long> sizes,
                                                     List<String> toMzPeakLosses, List<String> toMzMLLosses)
            throws IOException {
        Path source = MzMLFixtures.extract(name, Files.createDirectories(dir.resolve("source")));
        Path mzpeak = dir.resolve("b.mzpeak");
        Path mzml = dir.resolve("b.mzML");
        Model m1 = model(source);

        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(source, mzpeak, false,
                MzPeakWriter.Options.defaults());
        MzMLConversion.Result toMzML = MzMLConversion.toMzML(mzpeak, mzml, true);
        MzMLAssert.valid(mzml);

        List<String> readerLines = new ArrayList<>(MzMLReader.LIMITS);
        readerLines.addAll(toMzPeakLosses);
        assertThat(toMzPeak.warnings()).containsExactlyElementsOf(readerLines);
        List<String> writerLines = new ArrayList<>(MzMLWriter.LIMITS);
        writerLines.addAll(MzMLWriter.NOT_VISIBLE);
        writerLines.addAll(toMzMLLosses);
        assertThat(toMzML.warnings()).containsExactlyElementsOf(writerLines);
        assertThat(List.of(toMzPeak.spectra(), toMzPeak.wavelengthSpectra(), toMzPeak.chromatograms()))
                .isEqualTo(sizes);
        assertThat(List.of(toMzML.spectra(), toMzML.wavelengthSpectra(), toMzML.chromatograms())).isEqualTo(sizes);
        assertThat(MzMLMappingCheck.violations(mzml)).isEmpty();

        Model m3 = model(mzml);
        Map<String, Integer> counts = new LinkedHashMap<>();
        hidden.forEach((exclusion, n) -> counts.put(exclusion.id(), n));
        ModelAssert.Result result = ModelAssert.assertEqual(name + " B", m1, m3, STRICT,
                hidden.keySet().toArray(Exclusion[]::new));
        assertThat(result.hidden()).isEqualTo(counts);
    }

    /** The model {@link MzMLReader} reads from {@code file}. */
    private static Model model(Path file) {
        MzMLReader reader = new MzMLReader(file);
        List<Spectrum> spectra = new ArrayList<>();
        List<WavelengthSpectrum> wavelengthSpectra = new ArrayList<>();
        List<Chromatogram> chromatograms = new ArrayList<>();
        reader.read(new MzMLReader.Visitor() {
            @Override
            public void spectrum(Spectrum spectrum) {
                spectra.add(spectrum);
            }

            @Override
            public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
                wavelengthSpectra.add(spectrum);
            }

            @Override
            public void chromatogram(Chromatogram chromatogram) {
                chromatograms.add(chromatogram);
            }
        });
        MzMLReader.Header header = reader.header();
        return new Model(spectra, chromatograms, wavelengthSpectra, header.fileMetadata(), header.scanSettings(),
                header.cvList());
    }
}
