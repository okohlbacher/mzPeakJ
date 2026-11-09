package org.mzpeak.mzml;

import ms.numpress.MSNumpress;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ChildJvm;
import uk.ac.ebi.jmzml.model.mzml.BinaryDataArray;
import uk.ac.ebi.jmzml.model.mzml.CVParam;
import uk.ac.ebi.jmzml.model.mzml.FileDescription;
import uk.ac.ebi.jmzml.model.mzml.MzML;
import uk.ac.ebi.jmzml.model.mzml.Spectrum;
import uk.ac.ebi.jmzml.model.mzml.UserParam;
import uk.ac.ebi.jmzml.xml.io.MzMLMarshaller;
import uk.ac.ebi.jmzml.xml.io.MzMLObjectIterator;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;
import uk.ac.ebi.jmzml.xml.jaxb.adapters.CalendarAdapter;

import javax.xml.stream.XMLStreamException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPOutputStream;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * JZ-1: the behaviour of jmzML 1.7.11 as is (with xxindex 0.23 and the JAXB RI 2.3.9) that the converter relies on or
 * lists as a limit or loss (mzML design R4.3, 6.0, 11, Q67), one test per item (a) to (p) of the unit, so that a change
 * of jmzML, xxindex, JAXB or the JDK that changes one of them fails here first. The inputs are the vendored fixtures,
 * extracted into a {@code @TempDir} (jmzML reads files and cannot read the gzipped ones), and documents derived from
 * tiny by string edits, read and written as ISO-8859-1; "plain tiny" is an XML declaration followed by tiny's text from
 * {@code <mzML} to {@code </mzML>}. Every test that changes the default time zone, {@code System.out},
 * {@code System.err} or the system property {@link MzMLMarshaller} sets restores it.
 */
class JmzmlAsIsTest {

    /** The JVM-wide system property {@link MzMLMarshaller} sets on every call. */
    private static final String OUTPUT_FACTORY = "javax.xml.stream.XMLOutputFactory";
    private static final String SPECTRA = "/run/spectrumList/spectrum";
    private static final String PLAIN_DECLARATION = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>";
    private static final String CYCLE_22 = "sample=1 period=1 cycle=22 experiment=1";
    private static final String NO_MZML_ROOT = "Index does not contain mzML root!";
    /** U+FFFD, what jmzML reads for a byte it cannot decode and writes for a character XML cannot carry. */
    private static final String REPLACED = "\uFFFD";
    private static final String ORGANIZATION = "Higglesworth University";
    private static final String NO_COMPRESSION = "MS:1000576";
    private static final String ZLIB = "MS:1000574";
    private static final String FLOAT_64 = "MS:1000523";
    private static final String NUMPRESS_LINEAR = "MS:1002312";
    private static final String NUMPRESS_PIC = "MS:1002313";
    /** MS-Numpress short logged float compression followed by zlib compression, one of the combined terms. */
    private static final String NUMPRESS_SLOF_ZLIB = "MS:1002748";
    /** A zlib stream with a preset dictionary (FDICT set): jmzML's inflater loop never ends on it. */
    private static final String PRESET_DICTIONARY_ZLIB = "78bb0018000b6380020000080001";
    /**
     * SHA-256 of tiny as {@link MzMLMarshaller} writes it under UTC (item h): the same bytes on Temurin 17.0.21,
     * Temurin 21.0.13 and OpenJDK 27 (JZ-1).
     */
    private static final String TINY_MARSHALLED_SHA256 =
            "ad70adfa7f90e87b8711c804f402ebd6a6f6b2bae518e5c44df3eec495fb5843";

    @TempDir
    Path dir;

    private TimeZone zone;
    private String outputFactory;

    @BeforeEach
    void remember() {
        zone = TimeZone.getDefault();
        outputFactory = System.getProperty(OUTPUT_FACTORY);
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(zone);
        if (outputFactory == null) {
            System.clearProperty(OUTPUT_FACTORY);
        } else {
            System.setProperty(OUTPUT_FACTORY, outputFactory);
        }
    }

    /** (a) A userParam loses its unit on read; diaPASEF declares MS:1002814 for both ion mobility limits. */
    @Test
    void a_userParamUnitsAreDroppedOnRead() throws IOException {
        Path diaPasef = MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir);
        assertThat(Files.readString(diaPasef, UTF_8)).contains("<userParam name=\"ion mobility lower limit\" "
                + "value=\"0.60156745661641153\" type=\"xsd:double\" unitAccession=\"MS:1002814\" "
                + "unitName=\"volt-second per square centimeter\"/>");

        Spectrum first = spectra(open(diaPasef)).next();

        assertThat(first.getUserParam()).filteredOn(p -> p.getName().startsWith("ion mobility "))
                .extracting(UserParam::getName)
                .containsExactly("ion mobility lower limit", "ion mobility upper limit");
        assertThat(first.getUserParam()).filteredOn(p -> p.getName().startsWith("ion mobility "))
                .allSatisfy(p -> {
                    assertThat(p.getType()).isEqualTo("xsd:double");
                    assertThat(p.getUnitAccession()).isNull();
                    assertThat(p.getUnitName()).isNull();
                    assertThat(p.getUnitCvRef()).isNull();
                });
    }

    /** (b) The params of a referenceable group come after the element's own, flagged as inferred. */
    @Test
    void b_groupParamsComeAfterTheElementsOwn() throws IOException {
        Spectrum scan19 = spectra(open(tinyFile())).next();

        assertThat(scan19.getId()).isEqualTo("scan=19");
        assertThat(scan19.getCvParam()).extracting(CVParam::getAccession).containsExactly("MS:1000511",
                "MS:1000127", "MS:1000528", "MS:1000527", "MS:1000504", "MS:1000505", "MS:1000285", "MS:1000579",
                "MS:1000130");
        assertThat(scan19.getCvParam()).extracting(CVParam::isInferredFromReferenceableParamGroupRef)
                .containsExactly(false, false, false, false, false, false, false, true, true);
    }

    /** (c) A run param is not indexed in an indexedmzML, but is in the same content as plain mzML. */
    @Test
    void c_runParamsAreIndexedOnlyInPlainMzML() throws IOException {
        String withRunParam = afterRunStartTag(tiny(),
                "\n      <cvParam cvRef=\"MS\" accession=\"MS:1000857\" name=\"run attribute\" value=\"\"/>");
        Path indexed = write("tiny-run-param.mzML", withRunParam);
        Path plain = write("tiny-run-param-plain.mzML", plain(withRunParam, PLAIN_DECLARATION));

        assertThat(open(indexed).getObjectCountForXpath("/run/cvParam")).isZero();
        assertThat(open(plain).getObjectCountForXpath("/run/cvParam")).isEqualTo(1);
    }

    /** (d) A C1 control character (byte 0x85 of the ISO-8859-1 file) is read as U+FFFD. */
    @Test
    void d_aC1ControlIsReadAsReplacementCharacter() throws IOException {
        Path c1 = write("tiny-c1.mzML", edit(tiny(), ORGANIZATION, "Higglesworth Univ\u0085ersity"));
        assertThat(Files.readAllBytes(c1)).contains((byte) 0x85);

        assertThat(organization(c1)).isEqualTo("Higglesworth Univ" + REPLACED + "ersity");
    }

    /** (e) Without an encoding declaration a file is read as US-ASCII; with one, in that encoding. */
    @Test
    void e_aFileWithoutEncodingDeclarationIsReadAsAscii() throws IOException {
        String accented = edit(tiny(), ORGANIZATION, "Higglesworth Universit\u00e9");
        Path undeclared = dir.resolve("tiny-utf8-undeclared.mzML");
        Files.writeString(undeclared, plain(accented, "<?xml version=\"1.0\"?>"), UTF_8);
        Path declared = dir.resolve("tiny-utf8-declared.mzML");
        Files.writeString(declared, plain(accented, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), UTF_8);

        assertThat(organization(undeclared)).isEqualTo("Higglesworth Universit" + REPLACED + REPLACED);
        assertThat(organization(declared)).isEqualTo("Higglesworth Universit\u00e9");
    }

    /**
     * (f) Run attributes come verbatim (a character reference stays as written); a list's attributes are there
     * whenever the list element is, also empty or self-closed, and an absent list throws.
     */
    @Test
    void f_runAndListAttributes() throws IOException {
        String tiny = tiny();
        MzMLUnmarshaller unmarshaller = open(tinyFile());
        assertThat(unmarshaller.getSingleElementAttributes("/run")).isEqualTo(Map.of(
                "id", "Experiment_x0020_1",
                "defaultInstrumentConfigurationRef", "LCQ_x0020_Deca",
                "sampleRef", "_x0032_0090101_x0020_-_x0020_Sample_x0020_1",
                "startTimeStamp", "2007-06-27T15:23:45.00035",
                "defaultSourceFileRef", "tiny1.yep"));
        Path reference = write("tiny-run-id-reference.mzML",
                edit(tiny, "<run id=\"Experiment_x0020_1\"", "<run id=\"Experiment&#x2D;1\""));
        assertThat(open(reference).getSingleElementAttributes("/run")).containsEntry("id", "Experiment&#x2D;1");

        assertThat(unmarshaller.getObjectCountForXpath("/run/chromatogramList")).isEqualTo(1);
        assertThat(unmarshaller.getSingleElementAttributes("/run/chromatogramList"))
                .isEqualTo(Map.of("defaultDataProcessingRef", "pwiz_processing", "count", "2"));

        MzMLUnmarshaller noChromatograms = open(write("tiny-no-chromatograms.mzML",
                plain(cut(tiny, "<chromatogramList", "</chromatogramList>"), PLAIN_DECLARATION)));
        assertThat(noChromatograms.getObjectCountForXpath("/run/chromatogramList")).isZero();
        assertThat(noChromatograms.getObjectCountForXpath("/run/chromatogramList/chromatogram")).isZero();
        assertThatThrownBy(() -> noChromatograms.getSingleElementAttributes("/run/chromatogramList"))
                .isInstanceOf(IndexOutOfBoundsException.class);

        String emptyList = "<spectrumList count=\"0\" defaultDataProcessingRef=\"pwiz_processing\">";
        String empty = replace(tiny, "<spectrumList", "</spectrumList>", emptyList + "</spectrumList>");
        String selfClosed = replace(tiny, "<spectrumList", "</spectrumList>",
                "<spectrumList count=\"0\" defaultDataProcessingRef=\"pwiz_processing\"/>");
        for (String document : List.of(empty, selfClosed)) {
            MzMLUnmarshaller noSpectra = open(write("tiny-no-spectra.mzML", plain(document, PLAIN_DECLARATION)));
            assertThat(noSpectra.getObjectCountForXpath("/run/spectrumList")).isEqualTo(1);
            assertThat(noSpectra.getObjectCountForXpath(SPECTRA)).isZero();
            assertThat(noSpectra.getSingleElementAttributes("/run/spectrumList"))
                    .isEqualTo(Map.of("defaultDataProcessingRef", "pwiz_processing", "count", "0"));
        }
    }

    /** (g) {@code getSpectrumIDs()} collapses a repeated id; the spectrum iterator yields every spectrum. */
    @Test
    void g_repeatedSpectrumIdsCollapseOnlyInTheIdSet() throws IOException {
        MzMLUnmarshaller unmarshaller = open(write("tiny-repeated-id.mzML",
                edit(tiny(), "id=\"scan=20\"", "id=\"scan=19\"")));

        assertThat(unmarshaller.getSpectrumIDs()).hasSize(3);
        List<String> ids = new ArrayList<>();
        MzMLObjectIterator<Spectrum> spectra = spectra(unmarshaller);
        while (spectra.hasNext()) {
            ids.add(spectra.next().getId());
        }
        assertThat(ids).containsExactly("scan=19", "scan=19", "scan=21", CYCLE_22);
    }

    /**
     * (h) {@link MzMLMarshaller} writes tiny as valid plain mzML, pinned by its SHA-256 (design 11, Q67); a null
     * software reference or a null {@code encodedLength} is written as invalid mzML; marshalling sets the JVM-wide
     * {@code javax.xml.stream.XMLOutputFactory} property.
     */
    @Test
    void h_marshallerWritesPlainMzML() throws IOException, XMLStreamException {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        System.clearProperty(OUTPUT_FACTORY);
        MzMLUnmarshaller unmarshaller = open(tinyFile());

        MzML mzml = unmarshaller.unmarshall();
        Path tiny = marshal(mzml, "tiny-marshalled.mzML");

        assertThat(System.getProperty(OUTPUT_FACTORY)).isEqualTo("com.sun.xml.internal.stream.XMLOutputFactoryImpl");
        assertThat(MzMLSchema.rootElement(tiny)).isEqualTo(MzMLSchema.PLAIN_ROOT);
        assertThat(MzMLSchema.validate(tiny)).isEmpty();
        assertThat(Files.readAllLines(tiny, UTF_8).get(0)).isEqualTo("<?xml version=\"1.0\" ?>");
        assertThat(MzMLFixtures.sha256(tiny)).isEqualTo(TINY_MARSHALLED_SHA256);

        mzml.getInstrumentConfigurationList().getInstrumentConfiguration().get(0).setSoftwareRef(null);
        Path noSoftware = marshal(mzml, "tiny-no-software-ref.mzML");
        assertThat(Files.readString(noSoftware, UTF_8)).contains("<softwareRef></softwareRef>");
        assertThat(MzMLSchema.validate(noSoftware))
                .anyMatch(p -> p.contains("cvc-complex-type.4") && p.contains("'ref'"));

        MzML again = unmarshaller.unmarshall();
        for (BinaryDataArray array : again.getRun().getSpectrumList().getSpectrum().get(0).getBinaryDataArrayList()
                .getBinaryDataArray()) {
            array.setEncodedLength(null);
        }
        Path noLength = marshal(again, "tiny-no-encoded-length.mzML");
        assertThat(MzMLSchema.validate(noLength))
                .anyMatch(p -> p.contains("cvc-complex-type.4") && p.contains("'encodedLength'"));
    }

    /**
     * (i) Tab and LF in an attribute come back as spaces, C1 and C0 controls are written as U+FFFD (three UTF-8
     * bytes, read back as three U+FFFD), a supplementary character survives as a character reference, and a lone
     * surrogate makes the output unreadable.
     */
    @Test
    void i_charactersInWrittenValues() throws IOException {
        Path tiny = tinyFile();
        MzML mzml = open(tiny).unmarshall();
        mzml.getRun().getSpectrumList().getSpectrum().get(0).getUserParam()
                .add(userParam("probe", "a\tb\nc\u0085d\u0001e\uD83D\uDE00f"));
        Path written = marshal(mzml, "tiny-characters.mzML");

        assertThat(Files.readString(written, UTF_8)).contains("&#x1f600;");
        assertThat(spectra(open(written)).next().getUserParam()).filteredOn(p -> p.getName().equals("probe"))
                .extracting(UserParam::getValue)
                .containsExactly("a b c" + REPLACED.repeat(3) + "d" + REPLACED.repeat(3) + "e\uD83D\uDE00f");

        MzML surrogate = open(tiny).unmarshall();
        surrogate.getRun().getSpectrumList().getSpectrum().get(0).getUserParam()
                .add(userParam("probe", "x\uD800y"));
        Path unreadable = marshal(surrogate, "tiny-surrogate.mzML");

        assertThat(MzMLSchema.validate(unreadable)).isNotEmpty();
        MzMLObjectIterator<Spectrum> spectra = spectra(open(unreadable));
        assertThatThrownBy(spectra::next).isInstanceOf(IllegalStateException.class);
    }

    /**
     * (j) jmzML's {@link CalendarAdapter} (used for {@code startTimeStamp}): a time without zone takes the default
     * time zone, and a fraction that is not exactly milliseconds or a {@code +00:00} offset is rewritten.
     */
    @Test
    void j_calendarAdapterRewritesTimes() {
        CalendarAdapter adapter = new CalendarAdapter();

        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
        assertThat(adapter.marshal(adapter.unmarshal("2007-06-27T15:23:45.00035")))
                .isEqualTo("2007-06-27T15:23:45-05:00");
        assertThat(adapter.marshal(adapter.unmarshal("2007-06-27T15:23:45.123")))
                .isEqualTo("2007-06-27T15:23:45.123-05:00");

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Map<String, String> rewritten = Map.of(
                "2007-06-27T15:23:45.00035", "2007-06-27T15:23:45Z",
                "2005-07-20T19:44:22.1Z", "2005-07-20T19:44:22.100Z",
                "2005-07-20T19:44:22.000Z", "2005-07-20T19:44:22Z",
                "2005-07-20T19:44:22.1234Z", "2005-07-20T19:44:22.123Z",
                "2005-07-20T19:44:22+00:00", "2005-07-20T19:44:22Z",
                "2005-07-20T19:44:22Z", "2005-07-20T19:44:22Z",
                "2019-08-24T18:27:02.345+02:00", "2019-08-24T18:27:02.345+02:00");
        rewritten.forEach((value, written) -> assertThat(adapter.marshal(adapter.unmarshal(value)))
                .as(value).isEqualTo(written));
    }

    /**
     * (k) jmzML's array codec: an empty zlib array decodes to nothing, PIC counts of 2^31 and above decode negative,
     * a combined Numpress term is not understood, an empty array is encoded without compression, and with zlib the
     * encoder never returns (a child JVM, since the thread cannot be stopped).
     */
    @Test
    void k_binaryDataArrayCodec() throws IOException, InterruptedException {
        assertThat(array(new byte[0], ZLIB, FLOAT_64).getBinaryDataAsNumberArray()).isEmpty();

        double[] counts = {3e9, 2147483648.0, 5};
        byte[] buffer = new byte[counts.length * 5];
        byte[] pic = Arrays.copyOf(buffer, MSNumpress.encodePic(counts, counts.length, buffer));
        assertThat(doubles(array(pic, NUMPRESS_PIC, FLOAT_64).getBinaryDataAsNumberArray()))
                .containsExactly(-1294967296.0, -2147483648.0, 5.0);
        assertThatThrownBy(() -> array(pic, NUMPRESS_SLOF_ZLIB, FLOAT_64).getBinaryDataAsNumberArray())
                .isInstanceOf(IllegalStateException.class);

        BinaryDataArray empty = new BinaryDataArray();
        empty.set64BitFloatArrayAsBinaryData(new double[0], false, null);
        assertThat(empty.getBinary()).isEmpty();
        assertThat(empty.getCvParam()).extracting(CVParam::getAccession).containsExactly(NO_COMPRESSION, FLOAT_64);

        ChildJvm.Result child = ChildJvm.run(logs(), List.of(), EncodeEmptyArrayWithZlib.class);
        assertThat(child.exitStatus()).isZero();
        assertThat(child.stdout()).isEqualTo("running" + System.lineSeparator());
    }

    /** (l) A zlib payload with a preset dictionary makes the decoder loop forever (in a child JVM). */
    @Test
    void l_zlibWithPresetDictionaryNeverReturns() throws IOException, InterruptedException {
        ChildJvm.Result child = ChildJvm.run(logs(), List.of(), DecodePresetDictionary.class);

        assertThat(child.exitStatus()).isZero();
        assertThat(child.stdout()).isEqualTo("running" + System.lineSeparator());
    }

    /** (m) Decoding MS-Numpress overwrites {@code arrayLength} with the decoded length. */
    @Test
    void m_numpressDecodingOverwritesArrayLength() {
        double[] values = new double[15];
        for (int i = 0; i < values.length; i++) {
            values[i] = 400.5 + 13.25 * i;
        }
        byte[] buffer = new byte[8 + values.length * 5];
        int length = MSNumpress.encodeLinear(values, values.length, buffer,
                MSNumpress.optimalLinearFixedPoint(values, values.length));
        BinaryDataArray array = array(Arrays.copyOf(buffer, length), NUMPRESS_LINEAR, FLOAT_64);
        array.setArrayLength(14);

        double[] decoded = doubles(array.getBinaryDataAsNumberArray());

        assertThat(decoded).containsExactly(values);
        assertThat(decoded[0]).isEqualTo(400.5);
        assertThat(decoded[14]).isEqualTo(586.0);
        assertThat(array.getArrayLength()).isEqualTo(15);
    }

    /**
     * (n) gzip input: under a lower-case {@code .gz} name the constructor returns but reads the header from the
     * compressed bytes; under any other name it fails.
     */
    @Test
    void n_gzipInputCannotBeRead() throws IOException {
        byte[] gzipped = gzip(Files.readAllBytes(tinyFile()));
        Path lowerCase = dir.resolve("tiny.mzML.gz");
        Files.write(lowerCase, gzipped);

        MzMLUnmarshaller unmarshaller = open(lowerCase);
        assertThat(unmarshaller.getMzMLVersion()).isNull();
        assertThat(unmarshaller.getMzMLId()).isNull();
        MzMLObjectIterator<Spectrum> spectra = spectra(unmarshaller);
        assertThatThrownBy(spectra::next).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not instantiate reference resolver");

        // a directory of its own: the file system may not tell TINY.MZML.GZ from tiny.mzML.gz
        Path upperCase = Files.createDirectory(dir.resolve("upper")).resolve("TINY.MZML.GZ");
        Files.write(upperCase, gzipped);
        Path misnamed = dir.resolve("gzipped.mzML");
        Files.write(misnamed, gzipped);
        for (Path file : List.of(upperCase, misnamed)) {
            assertThatThrownBy(() -> open(file)).as(file.getFileName().toString())
                    .isInstanceOf(IllegalStateException.class).hasMessage(NO_MZML_ROOT);
        }
    }

    /**
     * (o) What xxindex cannot index fails in the constructor: a mismatched end tag (xxindex prints the mismatch to
     * {@code System.out}), a cut file, an empty file and an attribute without its closing quote.
     */
    @Test
    void o_indexTimeFailures() throws IOException {
        String tiny = tiny();
        Path misspelt = write("tiny-misspelt-end-tag.mzML", edit(tiny,
                "</scanList>\n          <precursorList", "</scanLis>\n          <precursorList"));

        Printed printed = printed(() -> open(misspelt));

        assertThat(printed.thrown()).isInstanceOf(IllegalStateException.class)
                .hasMessage("Internal stack of XML tags was corrupted!");
        assertThat(printed.out().lines())
                .anyMatch(line -> line.startsWith("Tag name mismatch! Found 'scanLis' but 'scanList' on stack."));

        Path cut = write("tiny-cut.mzML", tiny.substring(0, tiny.indexOf("id=\"scan=21\"")));
        Path empty = write("empty.mzML", "");
        Path unclosedQuote = write("tiny-unclosed-quote.mzML",
                edit(tiny, "name=\"ms level\" value=\"2\"/>", "name=\"ms level\" value=\"2/>"));
        for (Path file : List.of(cut, empty, unclosedQuote)) {
            assertThatThrownBy(() -> open(file)).as(file.getFileName().toString())
                    .isInstanceOf(IllegalStateException.class).hasMessage(NO_MZML_ROOT);
        }
    }

    /**
     * (p) A duplicated attribute is indexed silently; only its spectrum fails to unmarshal, without cause, and the
     * iterator goes on with the next.
     */
    @Test
    void p_aDuplicatedAttributeFailsOnlyItsSpectrum() throws IOException {
        String msLevel2 = "<cvParam cvRef=\"MS\" accession=\"MS:1000511\" name=\"ms level\" value=\"2\"/>";
        Path file = write("tiny-duplicated-attribute.mzML",
                edit(tiny(), msLevel2, msLevel2.replace("cvRef=\"MS\"", "cvRef=\"MS\" cvRef=\"MS\"")));
        MzMLUnmarshaller[] opened = new MzMLUnmarshaller[1];

        Printed printed = printed(() -> opened[0] = open(file));

        assertThat(printed.thrown()).isNull();
        assertThat(printed.out()).isEmpty();
        assertThat(printed.err()).isEmpty();
        MzMLObjectIterator<Spectrum> spectra = spectra(opened[0]);
        assertThat(spectra.next().getId()).isEqualTo("scan=19");
        assertThatThrownBy(spectra::next).isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not unmarshal object at xpath:/run/spectrumList/spectrum")
                .hasNoCause();
        assertThat(spectra.next().getId()).isEqualTo("scan=21");
        assertThat(spectra.next().getId()).isEqualTo(CYCLE_22);
        assertThat(spectra.hasNext()).isFalse();
    }

    /** Child JVM of (k): encodes an empty array with zlib, which never returns. */
    static final class EncodeEmptyArrayWithZlib {
        public static void main(String[] args) throws InterruptedException {
            report(() -> new BinaryDataArray().set64BitFloatArrayAsBinaryData(new double[0], true, null));
        }
    }

    /** Child JVM of (l): decodes the zlib payload with a preset dictionary, which never returns. */
    static final class DecodePresetDictionary {
        public static void main(String[] args) throws InterruptedException {
            BinaryDataArray array = array(HexFormat.of().parseHex(PRESET_DICTIONARY_ZLIB), ZLIB, FLOAT_64);
            report(array::getBinaryDataAsNumberArray);
        }
    }

    /**
     * Starts {@code call} in a daemon thread, waits 3 s for it, prints {@code running} if it has not returned by then,
     * else {@code returned}, and ends the JVM with status 0 (the thread cannot be stopped otherwise).
     */
    private static void report(Runnable call) throws InterruptedException {
        Thread thread = new Thread(call, "jmzml-call");
        thread.setDaemon(true);
        thread.start();
        thread.join(3000);
        System.out.println(thread.isAlive() ? "running" : "returned");
        System.exit(0);
    }

    /** What an action threw (null if nothing) and printed to {@code System.out} and {@code System.err}. */
    private record Printed(Throwable thrown, String out, String err) {
    }

    /** Runs {@code action} with {@code System.out} and {@code System.err} captured; both are restored. */
    private static Printed printed(ThrowingCallable action) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outBytes, true, UTF_8));
        System.setErr(new PrintStream(errBytes, true, UTF_8));
        try {
            Throwable thrown = catchThrowable(action);
            return new Printed(thrown, outBytes.toString(UTF_8), errBytes.toString(UTF_8));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private Path tinyFile() throws IOException {
        return MzMLFixtures.extract(MzMLFixtures.TINY, dir);
    }

    /** tiny's text, read as ISO-8859-1. */
    private String tiny() throws IOException {
        return Files.readString(tinyFile(), ISO_8859_1);
    }

    /** Writes {@code text} as ISO-8859-1 to {@code dir/name}. */
    private Path write(String name, String text) throws IOException {
        return Files.writeString(dir.resolve(name), text, ISO_8859_1);
    }

    private Path logs() throws IOException {
        return Files.createDirectories(dir.resolve("logs"));
    }

    /** Marshals {@code mzml} through a UTF-8 {@link Writer} to {@code dir/name}. */
    private Path marshal(MzML mzml, String name) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file);
             Writer writer = new OutputStreamWriter(out, UTF_8)) {
            new MzMLMarshaller().marshall(mzml, writer);
        }
        return file;
    }

    private static MzMLUnmarshaller open(Path file) {
        return new MzMLUnmarshaller(file.toFile(), false, null);
    }

    private static MzMLObjectIterator<Spectrum> spectra(MzMLUnmarshaller unmarshaller) {
        return unmarshaller.unmarshalCollectionFromXpath(SPECTRA, Spectrum.class);
    }

    /** The MS:1000590 contact organization of the first contact of {@code file}'s fileDescription. */
    private static String organization(Path file) {
        FileDescription description = open(file).unmarshalFromXpath("/fileDescription", FileDescription.class);
        return description.getContact().get(0).getCvParam().stream()
                .filter(p -> p.getAccession().equals("MS:1000590"))
                .map(CVParam::getValue)
                .findFirst().orElseThrow();
    }

    /** {@code text} with the first {@code from} replaced by {@code to}; {@code from} must occur. */
    private static String edit(String text, String from, String to) {
        int at = text.indexOf(from);
        if (at < 0) {
            throw new IllegalArgumentException("not in the document: " + from);
        }
        return text.substring(0, at) + to + text.substring(at + from.length());
    }

    /** {@code text} with the region from the first {@code start} to the next {@code end}, inclusive, replaced. */
    private static String replace(String text, String start, String end, String by) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from);
        if (from < 0 || to < 0) {
            throw new IllegalArgumentException("not in the document: " + start + " ... " + end);
        }
        return text.substring(0, from) + by + text.substring(to + end.length());
    }

    /** {@code text} without the region from the first {@code start} to the next {@code end}, inclusive. */
    private static String cut(String text, String start, String end) {
        return replace(text, start, end, "");
    }

    /** {@code text} with {@code insert} directly after the {@code run} start tag. */
    private static String afterRunStartTag(String text, String insert) {
        int tagEnd = text.indexOf('>', text.indexOf("<run ")) + 1;
        return text.substring(0, tagEnd) + insert + text.substring(tagEnd);
    }

    /** {@code declaration} followed by {@code indexed}'s text from {@code <mzML} to {@code </mzML>}. */
    private static String plain(String indexed, String declaration) {
        return declaration + indexed.substring(indexed.indexOf("<mzML"),
                indexed.indexOf("</mzML>") + "</mzML>".length());
    }

    private static UserParam userParam(String name, String value) {
        UserParam param = new UserParam();
        param.setName(name);
        param.setValue(value);
        return param;
    }

    /** A jmzML array of {@code binary} with the given MS terms. */
    private static BinaryDataArray array(byte[] binary, String... accessions) {
        BinaryDataArray array = new BinaryDataArray();
        array.setBinary(binary);
        for (String accession : accessions) {
            CVParam term = new CVParam();
            term.setCvRef("MS");
            term.setAccession(accession);
            array.getCvParam().add(term);
        }
        return array;
    }

    private static double[] doubles(Number[] numbers) {
        double[] values = new double[numbers.length];
        for (int i = 0; i < numbers.length; i++) {
            values[i] = numbers[i].doubleValue();
        }
        return values;
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        }
        return out.toByteArray();
    }
}
