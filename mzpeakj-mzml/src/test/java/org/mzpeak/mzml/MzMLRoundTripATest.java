package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.CvTerms;
import org.mzpeak.model.Spectrum;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Model;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * JZ-8: direction A, mzPeak -> mzML -> mzPeak, on all 13 mzPeak fixtures (the 8 current and the 5 legacy ones), each
 * with and without zlib (mzML design 6.2, 12.4). Each fixture is read by {@link MzPeakReader} with its defaults (both
 * signal members, profiles reconstructed), converted by {@link MzMLConversion#toMzML}, checked by
 * {@link MzMLAssert#valid} and {@link MzMLMappingCheck}, converted back by {@link MzMLConversion#toMzPeak} (defaults,
 * archive) and read again; {@link ModelAssert} compares the two models strictly, arrays bit-exact, with exactly the
 * exclusions of the A table of design 12.4, each hiding the pinned number of differences. The warnings of both steps
 * are pinned: the limit lines (L-8 and L-9 of the writer, L-1 to L-6 of the reader), the writer's loss lines per
 * fixture, no A-25 to A-29 line, and no loss line of the reader. Last, the array-key case of design 12.4: extra
 * arrays keyed outside {@link CvTerms#ARRAY_TYPES} go through A as MS:1000786 'non-standard data array'.
 */
class MzMLRoundTripATest {

    private static final String NON_STANDARD_ARRAY = "MS:1000786";
    private static final String ZLIB = "MS:1000574";
    private static final String NO_COMPRESSION = "MS:1000576";

    @TempDir
    Path dir;

    /** An exclusion of design 12.4 with the number of differences it hides in one fixture. */
    private record Hides(Exclusion exclusion, int differences) {
    }

    private static Hides hides(Exclusion exclusion, int differences) {
        return new Hides(exclusion, differences);
    }

    /**
     * One fixture of the A table: its dataset, the exclusions with the differences each hides, the writer's loss lines
     * after its limit lines, the CVs of the mzML and of the mzPeak written from it, and the violations
     * {@link MzMLMappingCheck} reports for the mzML.
     */
    private record Fixture(Path path, List<Hides> hidden, List<String> losses, List<String> cvs,
                           List<String> violations) {
    }

    // The writer's loss lines of design 6.2, with the counts the fixtures give them.
    private static final String A_1_CHUNKED = MzMLWriter.A_1 + " (14)";
    private static final List<String> A_DIAPASEF = List.of(MzMLWriter.A_2 + " (1)",
            String.format(MzMLWriter.A_18, "run defaultInstrumentConfigurationRef redirected to the first instrument"
                    + " configuration") + " (1)",
            String.format(MzMLWriter.A_8, "spectrumList defaultDataProcessingRef (the first data processing)") + " (1)",
            a7(9));
    private static final List<String> A_LEGACY_SMALL = List.of(MzMLWriter.A_17 + " (2)", MzMLWriter.A_16 + " (48)",
            MzMLWriter.A_9 + " (48)", a7(48));

    /** The A-7 line for {@code spectra} MS and wavelength spectra. */
    private static String a7(int spectra) {
        return MzMLWriter.A_7 + " (" + spectra + ")";
    }

    /**
     * The 13 fixtures with the A table of design 12.4, keyed by display name. The hidden counts are differences, not
     * spectra: A_1 hides two per peak list (peaks.size and numberOfPeaks; the 14 MS1 profile spectra of small.chunked
     * carry peaks that are not a row copy of their arrays, A-1); A_7 one per MS and wavelength spectrum; A_16 one
     * spectrum type and five summary params per spectrum (48 or 212); A_2, A_8, A_17 and A_18 one each.
     *
     * <p>Mapping-rule violations (design 6.4, 12.4): none for the outputs of current small*, has_uv, diaPASEF.ref and
     * .grid, Example_Processed and legacy small*. diaPASEF.ref and .grid lack nothing the six rules check (IC 0 holds
     * the instrument model MS:1003005 timsTOF Pro, every spectrum a spectrum type and a representation, both
     * chromatograms a type, every activation a dissociation method), and so does Example_Processed (its spectra of MS
     * level 0 carry MS:1000579 and MS:1000128, IC 0 holds MS:1000557 LTQ FT Ultra); their A-2, A-8, A-17 and A-18
     * repairs are not mapping rules. Legacy has_uv: the 520 wavelength spectra of its packed layout carry the spectrum
     * type MS:1000804 but no spectrum representation (MS:1000127 or MS:1000128; current has_uv has MS:1000128), which
     * the writer does not invent, so each is one spectrum_must violation.
     */
    static Map<String, Fixture> fixtures() {
        Map<String, Fixture> f = new LinkedHashMap<>();
        List<String> msUo = List.of("MS", "UO");
        for (String name : List.of("small.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak")) {
            f.put("current " + name, new Fixture(Fixtures.current(name), List.of(hides(ModelAssert.A_7, 48)),
                    List.of(a7(48)), msUo, List.of()));
        }
        f.put("current small.chunked.mzpeak", new Fixture(Fixtures.current("small.chunked.mzpeak"),
                List.of(hides(ModelAssert.A_1, 2 * 14), hides(ModelAssert.A_7, 48)), List.of(a7(48), A_1_CHUNKED),
                msUo, List.of()));
        f.put("current has_uv.mzpeak", new Fixture(Fixtures.current("has_uv.mzpeak"),
                List.of(hides(ModelAssert.A_7, 212 + 520)), List.of(a7(212 + 520)), msUo, List.of()));
        for (String name : List.of("diaPASEF.ref.mzpeak", "diaPASEF.grid.mzpeak")) {
            f.put("current " + name, new Fixture(Fixtures.current(name), List.of(hides(ModelAssert.A_2, 1),
                    hides(ModelAssert.A_7, 9), hides(ModelAssert.A_8, 1), hides(ModelAssert.A_18, 1)), A_DIAPASEF,
                    msUo, List.of()));
        }
        f.put("current Example_Processed.img.mzpeak", new Fixture(Fixtures.current("Example_Processed.img.mzpeak"),
                List.of(hides(ModelAssert.A_7, 9), hides(ModelAssert.A_17, 1)),
                List.of(MzMLWriter.A_17 + " (3)", a7(9)), List.of("MS", "UO", "IMS"), List.of()));
        List<Hides> legacySmall = List.of(hides(ModelAssert.A_7, 48), hides(ModelAssert.A_9, 48),
                hides(ModelAssert.A_16, 48 + 5 * 48), hides(ModelAssert.A_17, 1));
        for (String name : List.of("small.mzpeak", "small.numpress.mzpeak", "small.unpacked.mzpeak")) {
            f.put("legacy " + name, new Fixture(Fixtures.legacy(name), legacySmall, A_LEGACY_SMALL, msUo, List.of()));
        }
        List<Hides> legacyChunked = new ArrayList<>(legacySmall);
        legacyChunked.add(hides(ModelAssert.A_1, 2 * 14));
        List<String> legacyChunkedLosses = new ArrayList<>(A_LEGACY_SMALL);
        legacyChunkedLosses.add(A_1_CHUNKED);
        f.put("legacy small.chunked.mzpeak", new Fixture(Fixtures.legacy("small.chunked.mzpeak"), legacyChunked,
                legacyChunkedLosses, msUo, List.of()));
        f.put("legacy has_uv.mzpeak", new Fixture(Fixtures.legacy("has_uv.mzpeak"), List.of(hides(ModelAssert.A_7,
                212 + 520), hides(ModelAssert.A_9, 211), hides(ModelAssert.A_16, 212 + 5 * 212),
                hides(ModelAssert.A_17, 1)), List.of(MzMLWriter.A_17 + " (2)", MzMLWriter.A_16 + " (212)",
                MzMLWriter.A_9 + " (211)", a7(212 + 520)), msUo,
                IntStream.range(0, 520).mapToObj(k -> MzMLMappingCheck.SPECTRUM_MUST + " @ merged=" + (212 + k)
                        + " row=" + k).toList()));
        return f;
    }

    /** Every fixture, with zlib and without. */
    static Stream<Arguments> cases() {
        return fixtures().entrySet().stream().flatMap(e -> Stream.of(arguments(e.getKey(), e.getValue(), true),
                arguments(e.getKey(), e.getValue(), false)));
    }

    @Test
    void theTableCoversEveryFixture() {
        List<String> names = new ArrayList<>();
        Fixtures.CURRENT_NAMES.forEach(n -> names.add("current " + n));
        Fixtures.LEGACY_NAMES.forEach(n -> names.add("legacy " + n));
        assertThat(fixtures().keySet()).containsExactlyInAnyOrderElementsOf(names).hasSize(13);
    }

    @ParameterizedTest(name = "{0}, zlib {2}")
    @MethodSource("cases")
    void comesBackEqualUpToTheExclusionsOfItsFixture(String name, Fixture fixture, boolean zlib) throws IOException {
        Path mzml = dir.resolve("a.mzML");
        Path back = dir.resolve("a.mzpeak");
        Model expected = Model.load(fixture.path());

        MzMLConversion.Result toMzML = MzMLConversion.toMzML(fixture.path(), mzml, zlib);
        MzMLAssert.valid(mzml);
        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(mzml, back, false, MzPeakWriter.Options.defaults());

        List<String> writerLines = new ArrayList<>(MzMLWriter.LIMITS);
        writerLines.addAll(fixture.losses());
        assertThat(toMzML.warnings()).containsExactlyElementsOf(writerLines)
                .noneMatch(line -> line.matches("A-2[5-9] .*"));
        assertThat(toMzPeak.warnings()).containsExactlyElementsOf(MzMLReader.LIMITS);
        List<Long> sizes = List.of((long) expected.spectrumList().size(), (long) expected.wavelengthSpectra().size(),
                (long) expected.chromatograms().size());
        assertThat(List.of(toMzML.spectra(), toMzML.wavelengthSpectra(), toMzML.chromatograms())).isEqualTo(sizes);
        assertThat(List.of(toMzPeak.spectra(), toMzPeak.wavelengthSpectra(), toMzPeak.chromatograms()))
                .isEqualTo(sizes);
        String text = Files.readString(mzml, StandardCharsets.UTF_8);
        assertThat(text).contains(accession(zlib ? ZLIB : NO_COMPRESSION));
        assertThat(text.contains(accession(ZLIB))).as("zlib-compressed arrays").isEqualTo(zlib);
        assertThat(MzMLMappingCheck.violations(mzml)).containsExactlyElementsOf(fixture.violations());

        Model actual = Model.load(back);
        assertThat(actual.cvList()).extracting(CvEntry::id).containsExactlyElementsOf(fixture.cvs());
        Map<String, Integer> counts = new LinkedHashMap<>();
        fixture.hidden().forEach(h -> counts.put(h.exclusion().id(), h.differences()));
        ModelAssert.Result result = ModelAssert.assertEqual(name + " A, zlib " + zlib, expected, actual, STRICT,
                fixture.hidden().stream().map(Hides::exclusion).toArray(Exclusion[]::new));
        assertThat(result.hidden()).isEqualTo(counts);
    }

    // ---- an array key outside CvTerms.ARRAY_TYPES (design 5.7, 12.4) -------------------------------------------

    /**
     * Current small.mzpeak with spectrum 0 given two extra arrays whose keys are no array type of
     * {@link CvTerms#ARRAY_TYPES}: 'MS:9999999' holding 0, 1, ..., n-1 and 'XX:0000001' their squares, n its point
     * count. The mzML holds both as MS:1000786 with the key as value and declares no CV XX; back in mzPeak, spectrum 0
     * has both keys with the values bit-exact, and the whole model equals the input up to A-7.
     */
    @Test
    void arrayKeysOutsideTheArrayTypesComeBackThroughNonStandardDataArrays() throws IOException {
        String msKey = "MS:9999999";
        String xxKey = "XX:0000001";
        assertThat(CvTerms.ARRAY_TYPES).doesNotContainKeys(msKey, xxKey);
        Path input = dir.resolve("keys.mzpeak");
        double[] ramp;
        double[] squares;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            List<Spectrum> spectra = new ArrayList<>();
            reader.forEach(spectra::add);
            Spectrum first = spectra.get(0);
            assertThat(first.arrays()).isEmpty();
            int n = first.mz().length;
            assertThat(n).isPositive();
            ramp = new double[n];
            squares = new double[n];
            for (int i = 0; i < n; i++) {
                ramp[i] = i;
                squares[i] = (double) i * i;
            }
            Map<String, double[]> arrays = new LinkedHashMap<>();
            arrays.put(msKey, ramp);
            arrays.put(xxKey, squares);
            spectra.set(0, new Spectrum(first.description(), first.mz(), first.intensity(), first.peaks(), arrays,
                    first.peakArrays()));
            Set<String> keys = new LinkedHashSet<>(List.of(msKey, xxKey));
            try (MzPeakWriter.Session session = MzPeakWriter.openArchive(input, reader.fileMetadata(),
                    reader.scanSettings(), keys, MzPeakWriter.Options.defaults().withCvList(
                            reader.manifest().cvList()))) {
                spectra.forEach(session::addSpectrum);
                reader.chromatograms().forEach(session::addChromatogram);
                reader.wavelengthSpectra().forEach(session::addWavelengthSpectrum);
                session.finish();
            }
        }
        Model given = Model.load(input);
        assertThat(given.spectrumList().get(0).arrays()).containsOnlyKeys(msKey, xxKey);
        assertThat(given.cvList()).extracting(CvEntry::id).containsExactly("MS", "UO");

        Path mzml = dir.resolve("keys.mzML");
        MzMLConversion.Result toMzML = MzMLConversion.toMzML(input, mzml, true);
        MzMLAssert.valid(mzml);
        Path back = dir.resolve("keys-back.mzpeak");
        MzMLConversion.toMzPeak(mzml, back, false, MzPeakWriter.Options.defaults());

        List<String> writerLines = new ArrayList<>(MzMLWriter.LIMITS);
        writerLines.add(a7(48));
        assertThat(toMzML.warnings()).containsExactlyElementsOf(writerLines);
        Xml xml = xml(mzml);
        // spectrum 0's two keys, then the 'ms level' array of the TIC
        assertThat(xml.nonStandardArrays()).containsExactly(msKey, xxKey, "ms level");
        assertThat(xml.cvs()).containsExactly("MS", "UO");
        assertThat(xml.prefixes()).doesNotContain("XX");
        try (MzPeakReader reader = MzPeakReader.open(back)) {
            Map<String, double[]> arrays = reader.getSpectrumAt(0).arrays();
            assertThat(arrays).containsOnlyKeys(msKey, xxKey);
            assertThat(bits(arrays.get(msKey))).containsExactly(bits(ramp));
            assertThat(bits(arrays.get(xxKey))).containsExactly(bits(squares));
        }
        assertThat(ModelAssert.assertEqual("array keys A", given, Model.load(back), STRICT, ModelAssert.A_7).hidden())
                .isEqualTo(Map.of("A_7", 48));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The attribute {@code accession="<term>"} as the writer puts it on a cvParam. */
    private static String accession(String term) {
        return "accession=\"" + term + "\"";
    }

    /** What the array-key case reads from the mzML with StAX, independent of jmzML. */
    private record Xml(List<String> nonStandardArrays, List<String> cvs, Set<String> prefixes) {
    }

    /**
     * The values of the MS:1000786 cvParams, the ids of the {@code cv} elements, and the prefixes of every
     * {@code cvRef}, {@code unitCvRef}, {@code accession} and {@code unitAccession} of {@code file}, in document order.
     */
    private static Xml xml(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        List<String> nonStandard = new ArrayList<>();
        List<String> cvs = new ArrayList<>();
        Set<String> prefixes = new LinkedHashSet<>();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (r.getLocalName().equals("cv")) {
                    cvs.add(r.getAttributeValue(null, "id"));
                }
                if (r.getLocalName().equals("cvParam")
                        && NON_STANDARD_ARRAY.equals(r.getAttributeValue(null, "accession"))) {
                    nonStandard.add(r.getAttributeValue(null, "value"));
                }
                for (String attribute : List.of("cvRef", "unitCvRef", "accession", "unitAccession")) {
                    String value = r.getAttributeValue(null, attribute);
                    if (value != null) {
                        prefixes.add(value.contains(":") ? value.substring(0, value.indexOf(':')) : value);
                    }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        return new Xml(nonStandard, cvs, prefixes);
    }

    /** The IEEE-754 bits of each value, so that a comparison is bit-exact. */
    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }
}
