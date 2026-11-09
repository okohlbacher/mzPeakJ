package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata.MsRun;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MJ-3: what {@link MzMLReader} at {@code 7d59f14} returns for tiny, {@code compactTiny}, small, TOFsulfas and
 * diaPASEF, pinned in {@code src/test/resources/mzml/characterization/<input>.tsv} (mzML design 12.8). The units that
 * rebuild the reader (MJ-6, MJ-8, MJ-9) must reproduce these files exactly. Each file is UTF-8 with LF line ends, its
 * fields separated by {@code |} (which the {@link Canonical} rendering escapes):
 * <ul>
 *   <li>{@code header|<component>|<rendering>} for each component of {@code header().fileMetadata()} in declaration
 *       order, one line per element of a list component, then {@code header|scanSettings|...} and
 *       {@code header|cvList|...} per element;
 *   <li>per spectrum, wavelength spectrum and chromatogram of {@link MzMLReader#read(MzMLReader.Visitor)}, in
 *       document order, {@code <kind>|<index>|<rendered id>|<SHA-256 of the rendered description>} and
 *       {@code |<key>=<SHA-256>} per array in model order ({@link #characterize(Path)});
 *   <li>{@code survey|<component>|<rendering>} for the ids of the MS and the wavelength spectra, the extra array keys
 *       in their order and the number of chromatograms;
 *   <li>{@code warning|<escaped line>} per line of {@code warnings()} after {@code header()}, {@code survey()} and
 *       {@code read()} on one reader.
 * </ul>
 *
 * <p>Run with {@code -Dmzpeak.characterization.write=true} the test writes the files into the module's source tree
 * instead of comparing; they are generated once on the unchanged reader and committed.
 */
class MzMLReaderCharacterizationTest {

    /** With this system property {@code true}, {@link #theReaderReproducesItsCharacterization} writes the files. */
    static final String WRITE_PROPERTY = "mzpeak.characterization.write";
    /** Where the files are written in write mode, relative to the module (surefire's working directory). */
    static final Path SOURCE_DIR = Path.of("src", "test", "resources", "mzml", "characterization");
    /** Stands for the line after the last one in a failure message. */
    static final String END_OF_FILE = "<end of file>";
    /** Characters shown on each side of the first difference in a failure message. */
    private static final int EXCERPT = 80;

    /** The five inputs, each with the name of its file and the way it is made in a directory. */
    enum Input {
        TINY("tiny", dir -> MzMLFixtures.extract(MzMLFixtures.TINY, dir)),
        TINY_COMPACT("tiny-compact", MzMLFixtures::compactTiny),
        SMALL("small", dir -> MzMLFixtures.extract(MzMLFixtures.SMALL, dir)),
        TOFSULFAS("TOFsulfas", dir -> MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, dir)),
        DIAPASEF("diaPASEF", dir -> MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir));

        final String file;
        private final Maker maker;

        Input(String name, Maker maker) {
            this.file = name + ".tsv";
            this.maker = maker;
        }

        Path make(Path dir) throws IOException {
            return maker.make(dir);
        }
    }

    /** Makes an input in a directory. */
    interface Maker {
        Path make(Path dir) throws IOException;
    }

    // ---- the characterization ------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Input.class)
    void theReaderReproducesItsCharacterization(Input input, @TempDir Path dir) throws IOException {
        pin(input, characterize(input.make(dir)), Boolean.getBoolean(WRITE_PROPERTY), SOURCE_DIR);
    }

    @Test
    void writeModeWritesTheCommittedFile(@TempDir Path dir) throws IOException {
        List<String> lines = characterize(Input.TINY.make(dir));
        Path out = dir.resolve("characterization");

        pin(Input.TINY, lines, true, out);

        assertThat(Files.readAllBytes(out.resolve(Input.TINY.file))).isEqualTo(committedBytes(Input.TINY));
    }

    /** Writes {@code lines} as the file of {@code input} into {@code dir} if {@code write}, else compares them. */
    static void pin(Input input, List<String> lines, boolean write, Path dir) throws IOException {
        if (write) {
            Files.createDirectories(dir);
            Files.write(dir.resolve(input.file), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            compare(input.file, committed(input), lines);
        }
    }

    /**
     * The characterization of the mzML file {@code mzml}: {@code header()}, {@code survey()} and {@code read()} on one
     * reader, as described in the class comment.
     */
    static List<String> characterize(Path mzml) {
        MzMLReader reader = new MzMLReader(mzml);
        MzMLReader.Header header = reader.header();
        MzMLReader.Survey survey = reader.survey();
        List<String> entities = new ArrayList<>();
        reader.read(new MzMLReader.Visitor() {
            @Override
            public void spectrum(Spectrum spectrum) {
                assertThat(spectrum.peaks()).isEmpty();
                assertThat(spectrum.peakArrays()).isEmpty();
                entities.add(entity("spectrum", spectrum.description().index(), spectrum.description().id(),
                        Canonical.render(spectrum.description()), "mz", spectrum.mz(), spectrum.intensity(),
                        spectrum.arrays()));
            }

            @Override
            public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
                entities.add(entity("wavelength", spectrum.index(), spectrum.id(),
                        Canonical.renderWithout(spectrum, "wavelength", "intensity"), "wavelength",
                        spectrum.wavelength(), spectrum.intensity(), Map.of()));
            }

            @Override
            public void chromatogram(Chromatogram chromatogram) {
                entities.add(entity("chromatogram", chromatogram.index(), chromatogram.id(),
                        Canonical.renderWithout(chromatogram, "time", "intensity", "arrays"), "time",
                        chromatogram.time(), chromatogram.intensity(), chromatogram.arrays()));
            }
        });
        List<String> lines = new ArrayList<>();
        Canonical.components(header.fileMetadata()).forEach((name, value) -> headerLines(name, value, lines));
        headerLines("scanSettings", header.scanSettings(), lines);
        headerLines("cvList", header.cvList(), lines);
        lines.addAll(entities);
        lines.add("survey|spectrumIds|" + Canonical.render(survey.spectrumIds()));
        lines.add("survey|wavelengthSpectrumIds|" + Canonical.render(survey.wavelengthSpectrumIds()));
        lines.add("survey|spectrumArrays|" + Canonical.render(List.copyOf(survey.spectrumArrays())));
        lines.add("survey|chromatograms|" + Canonical.render(survey.chromatograms()));
        for (String warning : reader.warnings()) {
            lines.add("warning|" + Canonical.escape(warning));
        }
        return lines;
    }

    /** One {@code header} line for {@code value}, or one per element if it is a list. */
    private static void headerLines(String name, Object value, List<String> lines) {
        List<?> values = value instanceof List<?> list ? list : Arrays.asList(value);
        for (Object element : values) {
            lines.add("header|" + name + "|" + Canonical.render(element));
        }
    }

    /**
     * The line of an entity: its kind, index, id and the SHA-256 of its rendered {@code description}, then
     * {@code key=SHA-256} for the axis array under {@code axisKey}, the intensity array and each of {@code arrays}.
     */
    private static String entity(String kind, long index, String id, String description, String axisKey,
                                 double[] axis, double[] intensity, Map<String, double[]> arrays) {
        StringBuilder line = new StringBuilder(kind).append('|').append(index).append('|')
                .append(Canonical.render(id)).append('|').append(Canonical.sha256(description));
        array(line, axisKey, axis);
        array(line, "intensity", intensity);
        arrays.forEach((key, values) -> array(line, key, values));
        return line.toString();
    }

    private static void array(StringBuilder line, String key, double[] values) {
        line.append('|').append(Canonical.escape(key)).append('=').append(Canonical.sha256(values));
    }

    /**
     * Compares {@code actual} with {@code expected}, the lines of {@code file}, and names the first line that
     * differs, with the column of the first difference and an excerpt of both lines around it.
     *
     * @throws AssertionError if the lines differ
     */
    static void compare(String file, List<String> expected, List<String> actual) {
        for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            String e = i < expected.size() ? expected.get(i) : END_OF_FILE;
            String a = i < actual.size() ? actual.get(i) : END_OF_FILE;
            if (!e.equals(a)) {
                int column = firstDifference(e, a);
                throw new AssertionError(file + " line " + (i + 1) + ", column " + (column + 1) + ": expected\n  "
                        + excerpt(e, column) + "\nbut the reader gives\n  " + excerpt(a, column));
            }
        }
    }

    private static int firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** At most {@link #EXCERPT} characters of {@code line} on each side of {@code column}, cuts marked by "...". */
    static String excerpt(String line, int column) {
        int from = Math.max(0, column - EXCERPT);
        int to = Math.min(line.length(), column + EXCERPT);
        return (from > 0 ? "..." : "") + line.substring(Math.min(from, to), to) + (to < line.length() ? "..." : "");
    }

    /** The committed file of {@code input}, as bytes. */
    static byte[] committedBytes(Input input) throws IOException {
        try (InputStream in = MzMLFixtures.open("characterization/" + input.file)) {
            return in.readAllBytes();
        }
    }

    /** The lines of the committed file of {@code input}, which is UTF-8 and ends each line with LF. */
    static List<String> committed(Input input) throws IOException {
        String text = new String(committedBytes(input), StandardCharsets.UTF_8);
        assertThat(text).endsWith("\n").doesNotContain("\r");
        return List.of(text.substring(0, text.length() - 1).split("\n", -1));
    }

    // ---- (b) the canonical rendering -----------------------------------------------------------------------------

    @Test
    void valuesOfDifferentTypesRenderDifferently() {
        assertThat(Canonical.render(7L)).isEqualTo("Long:7");
        assertThat(Canonical.render("7")).isEqualTo("String:7");
        assertThat(Canonical.render(true)).isEqualTo("Boolean:true");
        assertThat(Canonical.render("true")).isEqualTo("String:true");
        assertThat(Canonical.render(1.0)).isEqualTo("Double:1.0");
        assertThat(Canonical.render(1L)).isEqualTo("Long:1");
        assertThat(Canonical.render(null)).isEqualTo("null");
        assertThat(Canonical.render("null")).isEqualTo("String:null");
        assertThat(Canonical.render(7)).isEqualTo("Integer:7");
        assertThat(Canonical.render((short) 7)).isEqualTo("Short:7");
        assertThat(Canonical.render((byte) 7)).isEqualTo("Byte:7");
        assertThat(Canonical.render(0.1f)).isEqualTo("Float:" + Double.toString(0.1f));
        assertThat(Canonical.render(Double.NaN)).isEqualTo("Double:NaN");
        assertThat(Canonical.render(-0.0)).isEqualTo("Double:-0.0");
        assertThat(Canonical.render(1e-300)).isEqualTo("Double:1.0E-300");
        assertThat(Canonical.render(Polarity.POSITIVE)).isEqualTo("POSITIVE");
        assertThat(Canonical.render(new double[]{1.0, -2.5e10})).isEqualTo("double[1.0, -2.5E10]");
        assertThat(Canonical.render(new double[0])).isEqualTo("double[]");
        assertThat(Canonical.render(List.of())).isEqualTo("[]");
        assertThat(Canonical.render(Map.of())).isEqualTo("{}");
    }

    @Test
    void separatorsControlCharactersAndNonAsciiAreEscaped() {
        assertThat(Canonical.render("a|b\tc\u0085d\\e\nf\rg\u00e9h~ i\u0000\u007f\ud83d\ude00"))
                .isEqualTo("String:a\\u007cb\\u0009c\\u0085d\\u005ce\\u000af\\u000dg\\u00e9h~ i\\u0000\\u007f"
                        + "\\ud83d\\ude00");
        assertThat(Canonical.escape(" !az{}~")).isEqualTo(" !az{}~");
        assertThat(Canonical.render("\\u007c")).isNotEqualTo(Canonical.render("|"));
    }

    /** A record with a nested list, map and record, for the rendering test. */
    record Outer(String name, List<Object> items, Map<Object, Object> map, Inner inner, Polarity polarity) {
    }

    /** The nested record of {@link Outer}. */
    record Inner(Long count, Double value) {
    }

    @Test
    void aRecordRendersItsComponentsInDeclarationOrderRecursively() {
        Map<Object, Object> map = new LinkedHashMap<>();
        map.put("z", 1L);
        map.put("a", List.of(true, "x"));
        map.put(2L, null);
        Outer outer = new Outer("o", List.of(new Inner(3L, null), "s", List.of()), map, new Inner(null, 2.0),
                Polarity.NEGATIVE);

        assertThat(Canonical.render(outer)).isEqualTo("Outer(name=String:o, items=[Inner(count=Long:3, value=null),"
                + " String:s, []], map={String:z=Long:1, String:a=[Boolean:true, String:x], Long:2=null},"
                + " inner=Inner(count=null, value=Double:2.0), polarity=NEGATIVE)");
        assertThat(Canonical.renderWithout(outer, "map", "items"))
                .isEqualTo("Outer(name=String:o, inner=Inner(count=null, value=Double:2.0), polarity=NEGATIVE)");
        assertThat(Canonical.components(new Inner(1L, 2.0))).containsExactly(Map.entry("count", 1L),
                Map.entry("value", 2.0));
    }

    @Test
    void aTypeWithoutARuleFailsTheRendering() {
        for (Object value : List.of(new Object(), Set.of("a"), Optional.empty(), 'c', new int[0],
                new StringBuilder("s"), List.of(new Object()), Map.of("k", new Object()))) {
            assertThatThrownBy(() -> Canonical.render(value)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("no canonical rendering for ");
        }
        assertThatThrownBy(() -> Canonical.render(new Outer("o", List.of(Set.of()), Map.of(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void renderWithoutRejectsANameThatIsNoComponent() {
        assertThatThrownBy(() -> Canonical.renderWithout(new Inner(1L, 1.0), "count", "mz"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Inner has no component [mz]; its components: [count, value]");
    }

    /** A record whose accessor throws. */
    record Failing(int x) {
        @Override
        public int x() {
            throw new UnsupportedOperationException("no x");
        }
    }

    @Test
    void aFailingAccessorFailsTheRendering() {
        assertThatThrownBy(() -> Canonical.render(new Failing(1))).isInstanceOf(IllegalStateException.class)
                .hasMessage("cannot read Failing.x").hasRootCauseMessage("no x");
    }

    @Test
    void sha256HashesTheLittleEndianIeee754Bytes() throws NoSuchAlgorithmException {
        byte[] one = {0, 0, 0, 0, 0, 0, (byte) 0xf0, 0x3f};
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(one));

        assertThat(Canonical.sha256(new double[]{1.0})).isEqualTo(expected);
        assertThat(Canonical.sha256(new double[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Canonical.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    // ---- (c) compactTiny -----------------------------------------------------------------------------------------

    @Test
    void compactTinyHasNoWhitespaceOnlyTextBetweenTags(@TempDir Path dir) throws IOException, XMLStreamException {
        Path compact = MzMLFixtures.compactTiny(dir);

        assertThat(compact).isEqualTo(dir.resolve(MzMLFixtures.COMPACT_TINY));
        assertThat(whitespaceOnlyTexts(compact)).isZero();
        // the same pass finds them in tiny
        assertThat(whitespaceOnlyTexts(dir.resolve(MzMLFixtures.TINY))).isPositive();
    }

    /** The number of whitespace-only text events of a StAX pass over {@code file}. */
    private static int whitespaceOnlyTexts(Path file) throws IOException, XMLStreamException {
        int n = 0;
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = staxFactory().createXMLStreamReader(in);
            while (r.hasNext()) {
                int event = r.next();
                if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.SPACE) && r.isWhiteSpace()) {
                    n++;
                }
            }
            r.close();
        }
        return n;
    }

    @Test
    void theRunOfCompactTinyStartsWithTheTwoParams(@TempDir Path dir) throws IOException, XMLStreamException {
        try (InputStream in = Files.newInputStream(MzMLFixtures.compactTiny(dir))) {
            XMLStreamReader r = staxFactory().createXMLStreamReader(in);
            while (!(r.next() == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("run"))) {
                assertThat(r.hasNext()).isTrue();
            }
            assertThat(r.next()).isEqualTo(XMLStreamConstants.START_ELEMENT);
            assertThat(attributes(r)).containsExactly("cvParam", "MS", "MS:1000857", "run attribute", "");
            assertThat(r.next()).isEqualTo(XMLStreamConstants.END_ELEMENT);
            assertThat(r.next()).isEqualTo(XMLStreamConstants.START_ELEMENT);
            assertThat(attributes(r)).containsExactly("cvParam", "MS", "MS:1000511", "ms level", "2");
            assertThat(r.next()).isEqualTo(XMLStreamConstants.END_ELEMENT);
            assertThat(r.next()).isEqualTo(XMLStreamConstants.START_ELEMENT);
            assertThat(r.getLocalName()).isEqualTo("spectrumList");
            r.close();
        }
    }

    private static List<String> attributes(XMLStreamReader r) {
        return List.of(r.getLocalName(), r.getAttributeValue(null, "cvRef"), r.getAttributeValue(null, "accession"),
                r.getAttributeValue(null, "name"), r.getAttributeValue(null, "value"));
    }

    private static XMLInputFactory staxFactory() {
        XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return factory;
    }

    @Test
    void compactTinyIsSchemaValid(@TempDir Path dir) throws IOException, XMLStreamException {
        Path compact = MzMLFixtures.compactTiny(dir);

        assertThat(MzMLSchema.rootElement(compact)).isEqualTo(MzMLSchema.INDEXED_ROOT);
        assertThat(MzMLSchema.validate(compact)).isEmpty();
    }

    /** JZ-4: the two run params of compactTiny are not read (L-2), so it reads exactly as tiny. */
    @Test
    void compactTinyReadsAsTiny(@TempDir Path dir) throws IOException {
        MsRun run = new MzMLReader(Input.TINY_COMPACT.make(dir)).header().fileMetadata().run();

        assertThat(run.parameters()).isEmpty();
        assertThat(committed(Input.TINY_COMPACT)).isEqualTo(committed(Input.TINY));
    }

    @Test
    void compactNeedsARunStartTag() {
        assertThat(MzMLFixtures.compact("<mzML>\n  <run id=\"r\">\n  </run>\n</mzML>\n"))
                .isEqualTo("<mzML><run id=\"r\">" + MzMLFixtures.COMPACT_RUN_PARAMS + "</run></mzML>\n");
        assertThat(MzMLFixtures.compact("<runs><run>x</run></runs>"))
                .isEqualTo("<runs><run>" + MzMLFixtures.COMPACT_RUN_PARAMS + "x</run></runs>");
        for (String document : List.of("<mzML/>", "<mzML><run id=\"r\"/></mzML>", "<mzML><runs/></mzML>")) {
            assertThatThrownBy(() -> MzMLFixtures.compact(document)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("no run start tag");
        }
    }

    // ---- (d) entity counts and warnings --------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"TINY, 4, 0, 2", "TINY_COMPACT, 4, 0, 2", "SMALL, 48, 0, 1", "TOFSULFAS, 212, 520, 2",
            "DIAPASEF, 9, 0, 2"})
    void eachFileHasALinePerEntityAndTheReadersWarnings(Input input, int spectra, int wavelengthSpectra,
                                                        int chromatograms, @TempDir Path dir) throws IOException {
        List<String> lines = committed(input);
        MzMLReader reader = new MzMLReader(input.make(dir));
        reader.header();
        reader.survey();
        reader.read(new MzMLReader.Visitor() {
            @Override
            public void spectrum(Spectrum spectrum) {
            }

            @Override
            public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
            }

            @Override
            public void chromatogram(Chromatogram chromatogram) {
            }
        });
        List<String> warnings = new ArrayList<>();
        for (String warning : reader.warnings()) {
            warnings.add("warning|" + Canonical.escape(warning));
        }

        assertThat(lines).filteredOn(line -> line.startsWith("spectrum|")).hasSize(spectra);
        assertThat(lines).filteredOn(line -> line.startsWith("wavelength|")).hasSize(wavelengthSpectra);
        assertThat(lines).filteredOn(line -> line.startsWith("chromatogram|")).hasSize(chromatograms);
        assertThat(lines).filteredOn(line -> line.startsWith("survey|")).hasSize(4);
        assertThat(lines).filteredOn(line -> line.startsWith("warning|")).containsExactlyElementsOf(warnings);
        assertThat(lines.subList(lines.size() - warnings.size(), lines.size())).isEqualTo(warnings);
        assertThat(lines).allMatch(line -> line.matches("(header|spectrum|wavelength|chromatogram|survey|warning)"
                + "\\|[ -~]*"));
    }

    @Test
    void theHeaderOfTinyHasALinePerComponentElement() throws IOException {
        List<String> lines = committed(Input.TINY);
        List<String> kinds = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("header|")) {
                kinds.add(line.substring("header|".length(), line.indexOf('|', "header|".length())));
            }
        }

        // tiny: 1 instrument configuration, 2 data processings, 1 sample, 3 software, 1 scan settings, 2 cvs
        assertThat(kinds).containsExactly("fileDescription", "instrumentConfigurations", "dataProcessingMethods",
                "dataProcessingMethods", "samples", "software", "software", "software", "run", "scanSettings",
                "cvList", "cvList");
        assertThat(lines).first().asString().startsWith("header|fileDescription|FileDescription(contents=[Param(");
        assertThat(lines).filteredOn(line -> line.startsWith("header|cvList|"))
                .allMatch(line -> line.startsWith("header|cvList|" + MzPeakManifest.CvEntry.class.getSimpleName()
                        + "(id=String:"));
    }

    // ---- (e) the comparison ------------------------------------------------------------------------------------

    @Test
    void oneChangedCharacterFailsTheComparisonNamingItsLine(@TempDir Path dir) throws IOException {
        List<String> actual = characterize(Input.TINY.make(dir));
        List<String> expected = new ArrayList<>(committed(Input.TINY));
        int line = expected.indexOf(expected.stream().filter(l -> l.startsWith("spectrum|1|")).findFirst()
                .orElseThrow());
        String original = expected.get(line);
        int column = original.length() - 1;
        expected.set(line, original.substring(0, column) + (original.charAt(column) == '0' ? '1' : '0'));

        compare(Input.TINY.file, committed(Input.TINY), actual);
        assertThatThrownBy(() -> compare(Input.TINY.file, expected, actual)).isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("tiny.tsv line " + (line + 1) + ", column " + (column + 1) + ": expected\n")
                .hasMessageContaining(expected.get(line).substring(column - 10))
                .hasMessageEndingWith(original.substring(column - 10));
    }

    @Test
    void aMissingOrExtraLineFailsTheComparisonAtTheEndOfTheShorterFile() {
        List<String> expected = List.of("header|a", "survey|b");

        assertThatThrownBy(() -> compare("x.tsv", expected, List.of("header|a")))
                .hasMessage("x.tsv line 2, column 1: expected\n  survey|b\nbut the reader gives\n  " + END_OF_FILE);
        assertThatThrownBy(() -> compare("x.tsv", List.of("header|a"), expected))
                .hasMessage("x.tsv line 2, column 1: expected\n  " + END_OF_FILE + "\nbut the reader gives\n  survey|b");
    }

    @Test
    void anExcerptCutsLongLinesAroundTheColumn() {
        String line = "a".repeat(100) + "X" + "b".repeat(100);

        assertThat(excerpt(line, 100)).isEqualTo("..." + "a".repeat(80) + "X" + "b".repeat(79) + "...");
        assertThat(excerpt(line, 0)).isEqualTo("a".repeat(80) + "...");
        assertThat(excerpt(line, 201)).isEqualTo("..." + "b".repeat(80));
        assertThat(excerpt("short", 3)).isEqualTo("short");
        assertThat(excerpt("ab", 300)).isEqualTo("...");
    }
}
