package org.mzpeak.mzml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.Contact;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.ScanSettings;
import org.mzpeak.mzml.ModelAssert.Exclusion;
import org.mzpeak.mzml.ModelAssert.Mode;
import org.mzpeak.mzml.ModelAssert.Model;
import org.mzpeak.mzml.MzMLWriter.Contents;
import uk.ac.ebi.jmzml.model.mzml.BinaryDataArray;
import uk.ac.ebi.jmzml.model.mzml.CVParam;
import uk.ac.ebi.jmzml.xml.io.MzMLObjectIterator;
import uk.ac.ebi.jmzml.xml.io.MzMLUnmarshaller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * JZ-5: {@link MzMLWriter} part 1, the header, the run and the chromatograms (mzML design 5.0-5.3, 5.7, 5.8, 5.10,
 * 5.11, 6.2, 8.1, 11). Every document is written twice, with and without zlib, each checked by
 * {@link MzMLAssert#valid}, and read back through {@link MzMLReader}: (a) the header and chromatograms of every mzPeak
 * fixture; (b) the id registry; (c) A-25; (d) A-26 and A-10; (e) the text helper (A-27, A-28, A-29); (f) guard 1; (g)
 * the writer's contract and the publishing without replace; (h) the output's first lines; (i) the MS:1000786 key rule;
 * (j) the remaining rules of the mapping, one document each.
 */
class MzMLWriterTest {

    private static final Param SOURCE = new Param("nanoelectrospray", "MS:1000398", null, null);
    private static final Param ANALYZER = new Param("quadrupole ion trap", "MS:1000082", null, null);
    private static final Param DETECTOR = new Param("electron multiplier", "MS:1000253", null, null);
    private static final String NO_COMPRESSION = "MS:1000576";
    private static final String ZLIB = "MS:1000574";

    @TempDir
    Path dir;

    private TimeZone zone;

    @BeforeEach
    void rememberTimeZone() {
        zone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    // ---- (a) every mzPeak fixture ------------------------------------------------------------------------------

    static Stream<Arguments> fixtures() {
        List<Arguments> out = new ArrayList<>();
        for (String name : Fixtures.CURRENT_NAMES) {
            List<Exclusion> exclusions = name.startsWith("diaPASEF") ? List.of(ModelAssert.A_2, ModelAssert.A_8,
                    ModelAssert.A_18) : name.startsWith("Example_Processed") ? List.of(ModelAssert.A_17) : List.of();
            out.add(Arguments.of("current " + name, Fixtures.current(name), exclusions));
        }
        for (String name : Fixtures.LEGACY_NAMES) {
            out.add(Arguments.of("legacy " + name, Fixtures.legacy(name), List.of(ModelAssert.A_17)));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void theHeaderAndChromatogramsOfEveryFixtureReadBackEqual(String name, Path fixture, List<Exclusion> exclusions) {
        try (MzPeakReader reader = MzPeakReader.open(fixture)) {
            FileMetadata metadata = reader.fileMetadata();
            Contents contents = new Contents(metadata, reader.scanSettings(), reader.manifest().cvList(), List.of(),
                    List.of(), reader.chromatograms());
            Model expected = new Model(List.of(), reader.chromatograms(), List.of(), metadata,
                    reader.scanSettings(), reader.manifest().cvList());

            for (Written w : write(name.replace(' ', '-'), contents)) {
                ReadBack back = read(w.file());
                Model actual = new Model(List.of(), back.chromatograms(), List.of(), back.header().fileMetadata(),
                        back.header().scanSettings(), back.header().cvList());
                ModelAssert.Result result = ModelAssert.assertEqual(name + " " + w, expected, actual,
                        Mode.MZPEAK_VS_MZML, exclusions.toArray(Exclusion[]::new));
                assertThat(result.hidden().values()).allSatisfy(n -> assertThat(n).isPositive());
                if (!exclusions.contains(ModelAssert.A_8)) {
                    assertThat(back.header().fileMetadata().run().defaultDataProcessingId())
                            .isEqualTo(metadata.run().defaultDataProcessingId()).isNotNull();
                }
                assertThat(w.warnings()).startsWith(MzMLWriter.LIMITS.toArray(String[]::new))
                        .noneMatch(line -> line.matches("A-2[5-9] .*"));
            }
        }
    }

    // ---- (b) the id registry ----------------------------------------------------------------------------------

    @Test
    void headerIdsThatAreNoNCNamesAreEncodedAndRefsFollowThem() {
        FileMetadata m = metadata(List.of(software("sw\u0001"), software("sw\u0002")),
                List.of(instrument(0, "sw\u0001")), List.of(processing("dp", "sw\u0002")), run("run"));

        for (Written w : write("ncname", contents(m))) {
            FileMetadata back = read(w.file()).header().fileMetadata();
            assertThat(back.software()).extracting(Software::id).containsExactly("sw_x0001_", "sw_x0002_");
            assertThat(back.instrumentConfigurations().get(0).softwareReference()).isEqualTo("sw_x0001_");
            assertThat(back.dataProcessingMethods().get(0).methods().get(0).softwareReference())
                    .isEqualTo("sw_x0002_");
            assertThat(w.warnings()).contains(MzMLWriter.A_2 + " (2)");
        }
    }

    @Test
    void aCvWhoseIdIsAnInstrumentConfigurationIdIsRenamed() throws IOException {
        CvEntry ic1 = new CvEntry("IC1", "IC one", "http://example.org/ic1.obo", "1");
        FileMetadata m = metadata(List.of(new Software("sw", "1", List.of(new Param("ic term", "IC1:0000001", null,
                null)))), List.of(instrument(0, "sw")), List.of(processing("dp", "sw")), run("run"));
        Contents contents = new Contents(m, List.of(), List.of(known("MS"), known("UO"), ic1), List.of(), List.of(),
                List.of());

        for (Written w : write("cv-ic1", contents)) {
            assertThat(read(w.file()).header().cvList()).containsExactly(known("MS"), known("UO"),
                    new CvEntry("IC1_2", "IC one", "http://example.org/ic1.obo", "1"));
            assertThat(Files.readString(w.file(), UTF_8)).contains("<cvParam cvRef=\"IC1_2\" accession=\"IC1:0000001\"");
            assertThat(w.warnings()).contains(MzMLWriter.A_24 + " (1)").noneMatch(l -> l.startsWith("A-21"));
        }
    }

    @Test
    void spectrumAndChromatogramIdsShareOneSetAndAreRepairedAsIndexIds() {
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of("bad id", "scan=1", "scan=1"),
                List.of(), List.of(chromatogram("scan=1")));
        Path target = dir.resolve("ids.mzML");

        MzMLWriter writer = MzMLWriter.open(target, contents, true);
        writer.close();

        assertThat(target).doesNotExist();
        assertThat(writer.warnings()).contains(MzMLWriter.A_3 + " (3)");
    }

    @Test
    void chromatogramIdsAreMadeXmlSafeAndRepairedWhenNullOrTaken() {
        List<Chromatogram> chromatograms = List.of(chromatogram(null), chromatogram("c\u0085"),
                chromatogram("c\ufffd"), chromatogram(""), chromatogram("tab\tid"));
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of(), List.of("w=1", "w=1"),
                chromatograms);
        MzMLWriter closed = MzMLWriter.open(dir.resolve("unused.mzML"), contents, true);
        closed.close();
        assertThat(closed.warnings()).contains(MzMLWriter.A_3 + " (4)", MzMLWriter.A_28 + " (1)");

        Contents chromatogramsOnly = new Contents(metadata(), List.of(), List.of(), List.of(), List.of(),
                chromatograms);
        for (Written w : write("chromatogram-ids", chromatogramsOnly)) {
            assertThat(read(w.file()).chromatograms()).extracting(Chromatogram::id).containsExactly("index=0",
                    "c\ufffd\ufffd\ufffd", "index=2", "index=3", "tab id");
            assertThat(w.warnings()).contains(MzMLWriter.A_3 + " (3)", MzMLWriter.A_28 + " (1)",
                    MzMLWriter.A_27 + " (1)");
        }
    }

    @Test
    void chromatogramIdsThatXmlReadsAlikeAreRepaired() {
        // XML reads tab, LF, CR and CR LF in an attribute as one space each, so these pairs would break
        // KEY_CHROMATOGRAM_ID; a spectrum id with them breaks the pattern, but takes part in the same set.
        Contents contents = contents(metadata(), chromatogram("c\tid"), chromatogram("c id"), chromatogram("d\r\nid"),
                chromatogram("d id"), chromatogram("e\rid"), chromatogram("e\nid"), chromatogram("e id"),
                chromatogram("f  id"), chromatogram("f\t\nid"));

        for (Written w : write("normalized-ids", contents)) {
            assertThat(read(w.file()).chromatograms()).extracting(Chromatogram::id).containsExactly("c id", "index=1",
                    "d id", "index=3", "e id", "index=5", "index=6", "f  id", "index=8");
            assertThat(w.warnings()).contains(MzMLWriter.A_3 + " (5)", MzMLWriter.A_27 + " (3)");
        }
    }

    @Test
    void nullEmptyAndRepeatedHeaderIdsAreRenamed() {
        FileDescription description = new FileDescription(List.of(), List.of(
                new SourceFile(null, "a", "file:///a", List.of()), new SourceFile("x", "b", "file:///b", List.of())));
        FileMetadata m = new FileMetadata(description,
                List.of(instrument(0, "x"), instrument(0, "x")),
                List.of(new DataProcessing("", List.of(new ProcessingMethod(1, "x", List.of()))),
                        processing("x", "x")),
                List.of(new Sample(null, "s", List.of())),
                List.of(software("x"), software(null)),
                new MsRun("x", 0, "x", "x", null, List.of()));
        Contents contents = new Contents(m, List.of(new ScanSettings("", List.of(), List.of("x"), List.of())),
                List.of(), List.of(), List.of(), List.of());

        for (Written w : write("renamed", contents)) {
            ReadBack back = read(w.file());
            FileMetadata b = back.header().fileMetadata();
            assertThat(b.fileDescription().sourceFiles()).extracting(SourceFile::id).containsExactly("sf_0", "x");
            assertThat(b.instrumentConfigurations()).extracting(InstrumentConfiguration::id).containsExactly(0, 1);
            assertThat(b.samples()).extracting(Sample::id).containsExactly("sample_0");
            assertThat(b.software()).extracting(Software::id).containsExactly("x_2", "software_1");
            assertThat(back.header().scanSettings()).extracting(ScanSettings::id).containsExactly("scan_settings_0");
            assertThat(back.header().scanSettings().get(0).sourceFileRefs()).containsExactly("x");
            assertThat(b.dataProcessingMethods()).extracting(DataProcessing::id).containsExactly("dp_0", "x_3");
            assertThat(b.run().id()).isEqualTo("x_4");
            assertThat(b.run().defaultDataProcessingId()).isEqualTo("x_3");
            assertThat(b.run().defaultSourceFileId()).isEqualTo("x");
            assertThat(b.instrumentConfigurations()).extracting(InstrumentConfiguration::softwareReference)
                    .containsExactly("x_2", "x_2");
            // sf, sample, software and dp without id; IC1, software, dp and run renamed
            assertThat(w.warnings()).contains(MzMLWriter.A_21 + " (9)");
        }
    }

    @Test
    void theCvListIsCompletedWithTheCvsInUse() {
        FileMetadata m = metadata(List.of(new Software("sw", "1", List.of(
                        new Param("a", "XY:1", null, "IMS:1000001"), new Param("b", "foo", null, null),
                        new Param("c", "1X:2", null, null), new Param("d", "", null, "PRIDE:1")))),
                List.of(instrument(0, "sw")), List.of(processing("dp", "sw")), run("run"));
        List<CvEntry> given = List.of(new CvEntry(null, "no id", "u", "1"), new CvEntry("", "empty id", "u", "1"),
                new CvEntry("MS", null, "http://example.org/ms.obo", null), new CvEntry("1X", "one X", null, "2"),
                new CvEntry("XY", "xy", "http://example.org/xy.obo", "3"));
        Contents contents = new Contents(m, List.of(), given, List.of(), List.of(), List.of());

        for (Written w : write("cv-list", contents)) {
            assertThat(read(w.file()).header().cvList()).containsExactly(
                    new CvEntry("MS", known("MS").fullName(), "http://example.org/ms.obo", null),
                    new CvEntry("_x0031_X", "one X", "unknown", "2"),
                    new CvEntry("XY", "xy", "http://example.org/xy.obo", "3"),
                    known("UO"), known("PRIDE"), known("IMS"), new CvEntry("foo", "foo", "unknown", null));
            assertThat(w.warnings()).contains(MzMLWriter.CV_WITHOUT_ID + " (2)", MzMLWriter.A_17 + " (4)",
                    MzMLWriter.CV_COMPLETED + " (3)", MzMLWriter.A_2 + " (1)");
        }
    }

    // ---- (c) A-25 ---------------------------------------------------------------------------------------------

    @Test
    void anInstrumentConfigurationWithoutSoftwareReferenceGetsTheFirstSoftware() {
        FileMetadata m = metadata(List.of(software("first"), software("second")),
                List.of(instrument(0, null), instrument(1, "second"), instrument(2, "missing")),
                List.of(processing("dp", "second")), run("run"));

        for (Written w : write("a25", contents(m))) {
            assertThat(read(w.file()).header().fileMetadata().instrumentConfigurations())
                    .extracting(InstrumentConfiguration::softwareReference).containsExactly("first", "second", "first");
            assertThat(w.warnings()).contains(MzMLWriter.A_25 + " (1)", String.format(MzMLWriter.A_18,
                    "instrumentConfiguration softwareRef redirected to the first software") + " (1)");
        }
    }

    // ---- (d) A-26 and A-10 ------------------------------------------------------------------------------------

    @Test
    void aRunStartTimeIsWrittenAsJmzmlWritesItOrDropped() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Chicago"));
        assertStartTime("chicago", "2007-06-27T15:23:45.00035", "2007-06-27T15:23:45-05:00");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertStartTime("fraction", "2005-07-20T19:44:22.1234Z", "2005-07-20T19:44:22.123Z");

        for (Written w : write("unchanged", contents(metadata(run("run", "2005-07-20T19:44:22Z"))))) {
            assertThat(read(w.file()).header().fileMetadata().run().startTime()).isEqualTo("2005-07-20T19:44:22Z");
            assertThat(w.warnings()).noneMatch(l -> l.startsWith("A-26") || l.startsWith("A-10"));
        }
        for (Written w : write("no-date", contents(metadata(run("run", "not a date"))))) {
            assertThat(read(w.file()).header().fileMetadata().run().startTime()).isNull();
            assertThat(w.warnings()).contains(MzMLWriter.A_10 + " (1)").noneMatch(l -> l.startsWith("A-26"));
        }
    }

    private void assertStartTime(String name, String time, String written) {
        for (Written w : write(name, contents(metadata(run("run", time))))) {
            assertThat(read(w.file()).header().fileMetadata().run().startTime()).isEqualTo(written);
            assertThat(w.warnings()).contains(String.format(MzMLWriter.A_26, time, written) + " (1)")
                    .anyMatch(l -> l.contains(time + " -> " + written));
        }
    }

    // ---- (e) the text helper ----------------------------------------------------------------------------------

    @Test
    void valuesWithTabsControlsOrNonAsciiAreCountedAndReadBackAsJmzmlReadsThem() {
        FileDescription description = new FileDescription(List.of(),
                List.of(new SourceFile("sf", "a\tb", "file:///a", List.of())));
        FileMetadata m = new FileMetadata(description, List.of(instrument(0, "sw")),
                List.of(processing("dp", "sw")), List.of(new Sample("s", "c\u0085d", List.of())),
                List.of(new Software("sw", "1", List.of(new Param("note", null, "\u00e9", null)))), run("run"));

        for (Written w : write("text", contents(m))) {
            FileMetadata back = read(w.file()).header().fileMetadata();
            assertThat(back.fileDescription().sourceFiles().get(0).name()).isEqualTo("a b");
            assertThat(back.samples().get(0).name()).isEqualTo("c\ufffd\ufffd\ufffdd");
            assertThat(back.software().get(0).parameters()).containsExactly(new Param("note", null, "\ufffd\ufffd",
                    null));
            assertThat(w.warnings()).contains(MzMLWriter.A_27 + " (1)", MzMLWriter.A_28 + " (1)",
                    MzMLWriter.A_29 + " (2)");
        }
    }

    // ---- (f) guard 1 ------------------------------------------------------------------------------------------

    @Test
    void emptyArraysAreWrittenWithoutCompression() {
        Chromatogram empty = new Chromatogram(0, "empty", "MS:1000235", new double[0], new double[0]);
        Chromatogram full = new Chromatogram(1, "full", "MS:1000235", new double[]{1, 2}, new double[]{3, 4});
        Contents contents = new Contents(metadata(), List.of(), List.of(), List.of(), List.of(), List.of(empty, full));
        Path target = dir.resolve("guard1.mzML");

        MzMLWriter writer = MzMLWriter.open(target, contents, true);
        assertTimeoutPreemptively(Duration.ofSeconds(10), writer::finish);

        MzMLAssert.valid(target);
        List<List<String>> terms = arrayTerms(target);
        assertThat(terms.get(0)).containsExactly(NO_COMPRESSION, "MS:1000523", "MS:1000595");
        assertThat(terms.get(1)).containsExactly(NO_COMPRESSION, "MS:1000523", "MS:1000515");
        assertThat(terms.get(2)).containsExactly(ZLIB, "MS:1000523", "MS:1000595");
        assertThat(terms.get(3)).containsExactly(ZLIB, "MS:1000523", "MS:1000515");
        List<Chromatogram> back = read(target).chromatograms();
        assertThat(back.get(0).time()).isEmpty();
        assertThat(back.get(0).intensity()).isEmpty();
        assertThat(back.get(1).time()).containsExactly(1, 2);
        assertThat(back.get(1).intensity()).containsExactly(3, 4);

        Path plain = dir.resolve("guard1-plain.mzML");
        MzMLWriter.open(plain, contents, false).finish();
        MzMLAssert.valid(plain);
        assertThat(arrayTerms(plain)).allSatisfy(t -> assertThat(t.get(0)).isEqualTo(NO_COMPRESSION));
    }

    /**
     * D-12a (V-21): small.unpacked's TIC in seconds ({@link Chromatogram#withTimeUnit}, as the reader gives a file
     * that stores seconds) is written in minutes with {@code unitAccession="UO:0000031"}, never seconds labelled as
     * minutes; it reads back as the fixture's minutes within a relative 1e-12. V-23 writes the stored unit instead.
     */
    @Test
    void aChromatogramInSecondsIsWrittenInMinutes() throws IOException {
        Chromatogram minutes;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            minutes = reader.chromatograms().get(0);
        }
        Chromatogram seconds = minutes.withTimeUnit("UO:0000010");
        assertThat(seconds.time()[1]).isEqualTo(minutes.time()[1] * 60);
        Contents contents = new Contents(metadata(), List.of(), List.of(known("MS"), known("UO")), List.of(),
                List.of(), List.of(seconds));

        for (Written w : write("seconds", contents)) {
            String xml = Files.readString(w.file(), UTF_8);
            assertThat(xml).contains("accession=\"MS:1000595\" name=\"time array\" unitAccession=\"UO:0000031\"");
            assertThat(xml).doesNotContain("unitAccession=\"UO:0000010\"");
            Chromatogram back = read(w.file()).chromatograms().get(0);
            assertThat(back.timeUnit()).isEqualTo("UO:0000031");
            assertThat(back.time()).hasSameSizeAs(minutes.time()).isNotEmpty();
            for (int i = 0; i < minutes.size(); i++) {
                assertThat(Math.abs(back.time()[i] - minutes.time()[i])).as("time[%d]", i)
                        .isLessThanOrEqualTo(1e-12 * Math.abs(minutes.time()[i]));
            }
            assertThat(back.intensity()).containsExactly(minutes.intensity());
        }
    }

    // ---- (g) contract and publishing --------------------------------------------------------------------------

    @Test
    void fewerSpectraThanAnnouncedWriteNothing() throws IOException {
        for (List<String> wavelength : List.of(List.<String>of(), List.of("w=1"))) {
            List<String> spectra = wavelength.isEmpty() ? List.of("scan=1") : List.of();
            Contents contents = new Contents(metadata(), List.of(), List.of(), spectra, wavelength, List.of());
            Path target = dir.resolve("announced.mzML");

            MzMLWriter writer = MzMLWriter.open(target, contents, true);
            assertThatThrownBy(writer::finish).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("0 spectra written, 1 announced");

            assertThat(target).doesNotExist();
            assertThat(listing()).isEmpty();
            assertThatThrownBy(writer::finish).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finished or closed");
        }
    }

    @Test
    void anExistingTargetIsRefusedAndKept() throws IOException {
        Path target = Files.writeString(dir.resolve("exists.mzML"), "keep");

        assertThatThrownBy(() -> MzMLWriter.open(target, contents(metadata()), true))
                .isInstanceOf(MzPeakException.class).hasMessageContaining(target.toString());

        assertThat(target).hasContent("keep");
    }

    @Test
    void aTargetInADirectoryThatDoesNotExistFailsInFinishAndCreatesNothing() throws IOException {
        Path target = dir.resolve("missing").resolve("out.mzML");
        MzMLWriter writer = MzMLWriter.open(target, contents(metadata()), true);

        assertThatThrownBy(writer::finish).isInstanceOf(MzPeakException.class)
                .hasMessageContaining(target.toString());

        assertThat(listing()).isEmpty();
    }

    @Test
    void aTargetThatAppearsBeforeFinishIsNeverReplaced() throws IOException {
        Path target = dir.resolve("late.mzML");
        MzMLWriter writer = MzMLWriter.open(target, contents(metadata()), true);
        Files.writeString(target, "keep");

        assertThatThrownBy(writer::finish).isInstanceOf(MzPeakException.class)
                .hasMessageContaining(target.toString()).hasMessageContaining("FileAlreadyExistsException");

        assertThat(target).hasContent("keep");
        assertThat(listing()).containsExactly("late.mzML");
    }

    /** An Error or RuntimeException while the document is marshalled leaves no temp file and no target. */
    @Test
    void aFailureWhileMarshallingLeavesNoTempFile() throws IOException {
        Path target = dir.resolve("failed.mzML");
        MzMLWriter oom = MzMLWriter.open(target, contents(metadata()), true);

        assertThatThrownBy(() -> oom.finishWith((document, w) -> {
            throw new OutOfMemoryError("Java heap space");
        })).isInstanceOf(OutOfMemoryError.class).hasMessage("Java heap space");
        assertThat(listing()).isEmpty();

        MzMLWriter failing = MzMLWriter.open(target, contents(metadata()), true);
        assertThatThrownBy(() -> failing.finishWith((document, w) -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(MzPeakException.class).hasMessage("mzML " + target
                + ": cannot be written: java.lang.IllegalArgumentException: boom");
        assertThat(listing()).isEmpty();
    }

    @Test
    void closeWithoutFinishWritesNothingAndCanBeRepeated() throws IOException {
        Path target = dir.resolve("closed.mzML");
        MzMLWriter writer = MzMLWriter.open(target, contents(metadata()), true);

        writer.close();
        writer.close();

        assertThat(listing()).isEmpty();
        assertThatThrownBy(writer::finish).isInstanceOf(IllegalStateException.class);
        try (MzMLWriter finished = MzMLWriter.open(target, contents(metadata()), false)) {
            finished.finish();
        }
        MzMLAssert.valid(target);
    }

    @Test
    void badArgumentsAreRejected() {
        Contents contents = contents(metadata());
        assertThatThrownBy(() -> MzMLWriter.open(null, contents, true)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> MzMLWriter.open(dir.resolve("x.mzML"), null, true))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Contents(null, List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(NullPointerException.class);
        List<String> ids = new ArrayList<>(Arrays.asList("scan=1", null));
        Contents copied = new Contents(metadata(), List.of(), List.of(), ids, ids, List.of());
        ids.clear();
        assertThat(copied.spectrumIds()).containsExactly("scan=1", null);
        assertThat(copied.wavelengthSpectrumIds()).isUnmodifiable();
    }

    // ---- (h) the output's first lines -------------------------------------------------------------------------

    @Test
    void theOutputIsPlainMzMLWithComponentsGrouped() throws IOException {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("diaPASEF.ref.mzpeak"))) {
            Contents contents = new Contents(reader.fileMetadata(), reader.scanSettings(), reader.manifest().cvList(),
                    List.of(), List.of(), reader.chromatograms());
            for (Written w : write("dia", contents)) {
                List<String> lines = Files.readAllLines(w.file(), UTF_8);
                assertThat(lines.get(0)).isEqualTo("<?xml version=\"1.0\" ?>");
                assertThat(lines.get(1)).isEqualTo("<mzML xmlns=\"http://psi.hupo.org/ms/mzml\" version=\"1.1.0\">");
                assertThat(Files.readString(w.file(), UTF_8)).contains("<componentList count=\"5\">");
            }
        }
    }

    // ---- (i) the key rule on chromatograms --------------------------------------------------------------------

    @Test
    void arrayKeysOutsideTheArrayTypesAreWrittenAsNonStandardDataArrays() throws IOException {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        arrays.put("MS:9999999", new double[]{0.1, Double.MIN_VALUE});
        arrays.put("XX:0000001", new double[]{-0.0, Double.MAX_VALUE});
        arrays.put("ms level", new double[]{1, 2});
        arrays.put("MS:1000516", new double[]{3, 4});
        Chromatogram c = new Chromatogram(0, "tic", "MS:1000235", new double[]{1, 2}, new double[]{5, 6},
                "MS:1000131", arrays);
        Contents contents = new Contents(metadata(), List.of(), List.of(known("MS"), known("UO")), List.of(),
                List.of(), List.of(c));

        for (Written w : write("keys", contents)) {
            String xml = Files.readString(w.file(), UTF_8);
            for (String key : List.of("MS:9999999", "XX:0000001")) {
                assertThat(xml).contains("<cvParam cvRef=\"MS\" accession=\"MS:1000786\" value=\"" + key
                        + "\" name=\"non-standard data array\"></cvParam>");
            }
            assertThat(xml).contains("<cvParam cvRef=\"MS\" accession=\"MS:1000786\" value=\"ms level\" "
                    + "name=\"non-standard data array\" unitAccession=\"UO:0000186\" unitName=\"dimensionless unit\" "
                    + "unitCvRef=\"UO\"></cvParam>");
            assertThat(xml).contains("<cvParam cvRef=\"MS\" accession=\"MS:1000516\" name=\"charge array\">");
            ReadBack back = read(w.file());
            assertThat(back.header().cvList()).extracting(CvEntry::id).containsExactly("MS", "UO");
            Chromatogram read = back.chromatograms().get(0);
            assertThat(read.arrays()).containsOnlyKeys("MS:9999999", "XX:0000001", "ms level", "MS:1000516");
            arrays.forEach((key, values) -> assertThat(bits(read.arrays().get(key))).as(key).isEqualTo(bits(values)));
        }
    }

    // ---- (j) the other rules of the mapping -------------------------------------------------------------------

    @Test
    void whatMzMLRequiresAndTheModelLacksIsSynthesized() throws IOException {
        for (Written w : write("empty", contents(FileMetadata.EMPTY))) {
            assertThat(MzMLMappingCheck.violations(w.file())).containsExactly("instrumentconfiguration_must @ IC1");
            FileMetadata back = read(w.file()).header().fileMetadata();
            assertThat(back.software()).containsExactly(new Software("mzPeakJ", "unknown",
                    List.of(new Param("custom unreleased software tool", "MS:1000799", "mzPeakJ", null))));
            assertThat(back.dataProcessingMethods()).containsExactly(new DataProcessing("mzpeakj_conversion",
                    List.of(new ProcessingMethod(0, "mzPeakJ",
                            List.of(new Param("Conversion to mzML", "MS:1000544", null, null))))));
            assertThat(back.instrumentConfigurations()).containsExactly(new InstrumentConfiguration(0, List.of(),
                    "mzPeakJ", List.of()));
            assertThat(back.run()).isEqualTo(new MsRun("mzpeakj_run", 0, "mzpeakj_conversion", null, null,
                    List.of()));
            assertThat(back.fileDescription()).isEqualTo(new FileDescription(List.of(), List.of(), List.of()));
            assertThat(w.warnings()).containsExactly(MzMLWriter.LIMITS.get(0), MzMLWriter.LIMITS.get(1),
                    MzMLWriter.NOT_VISIBLE.get(0), MzMLWriter.NOT_VISIBLE.get(1),
                    String.format(MzMLWriter.A_8, "instrument configuration IC1") + " (1)",
                    MzMLWriter.A_17 + " (2)",
                    String.format(MzMLWriter.A_8, "software mzPeakJ") + " (1)",
                    String.format(MzMLWriter.A_8, "data processing mzpeakj_conversion") + " (1)",
                    String.format(MzMLWriter.A_8, "run id mzpeakj_run") + " (1)",
                    MzMLWriter.INSTRUMENT_MUST + " (1)",
                    String.format(MzMLWriter.A_8, "run defaultInstrumentConfigurationRef (the first instrument"
                            + " configuration)") + " (1)",
                    String.format(MzMLWriter.A_8, "spectrumList defaultDataProcessingRef (the first data"
                            + " processing)") + " (1)");
        }
    }

    @Test
    void componentsAreGroupedAndWhatMzMLCannotHoldIsDropped() {
        List<Component> complete = List.of(component(ComponentType.DETECTOR, 3, DETECTOR),
                component(ComponentType.UNKNOWN, 4, null), component(ComponentType.ION_SOURCE, 1, SOURCE),
                component(ComponentType.ANALYZER, 2, ANALYZER));
        List<Component> noDetector = List.of(component(ComponentType.ION_SOURCE, 1, SOURCE),
                component(ComponentType.ANALYZER, 2, ANALYZER));
        List<Component> unknownOnly = List.of(component(ComponentType.UNKNOWN, 1, null));
        FileMetadata m = metadata(List.of(software("sw")), List.of(
                new InstrumentConfiguration(0, complete, "sw", List.of()),
                new InstrumentConfiguration(1, noDetector, "sw", List.of()),
                new InstrumentConfiguration(2, unknownOnly, "sw", List.of()),
                new InstrumentConfiguration(-1, List.of(), "sw", List.of())), List.of(processing("dp", "sw")),
                run("run"));

        for (Written w : write("components", contents(m))) {
            List<InstrumentConfiguration> back = read(w.file()).header().fileMetadata().instrumentConfigurations();
            assertThat(back).extracting(InstrumentConfiguration::id).containsExactly(0, 1, 2, -1);
            assertThat(back.get(0).components()).containsExactly(complete.get(2), complete.get(3), complete.get(0));
            assertThat(back.subList(1, 4)).allSatisfy(ic -> assertThat(ic.components()).isEmpty());
            assertThat(w.warnings()).contains(MzMLWriter.A_12_UNKNOWN + " (2)", MzMLWriter.A_12_LIST + " (1)");
        }
    }

    @Test
    void dataProcessingsGetAMethodAndARequiredSoftwareReference() throws IOException {
        FileMetadata m = metadata(List.of(software("first"), software("second")), List.of(instrument(0, "second")),
                List.of(new DataProcessing("none", List.of()), new DataProcessing("refs", List.of(
                        new ProcessingMethod(1, null, List.of()), new ProcessingMethod(2, "gone", List.of()),
                        new ProcessingMethod(3, "second", List.of())))),
                new MsRun("run", 0, "gone", "gone", null, List.of(new Param("run note", null, 1.5, "UO:0000010"))));

        for (Written w : write("processing", contents(m))) {
            FileMetadata back = read(w.file()).header().fileMetadata();
            assertThat(back.dataProcessingMethods()).containsExactly(
                    new DataProcessing("none", List.of(new ProcessingMethod(0, "first",
                            List.of(new Param("Conversion to mzML", "MS:1000544", null, null))))),
                    new DataProcessing("refs", List.of(new ProcessingMethod(1, "first", List.of()),
                            new ProcessingMethod(2, "first", List.of()), new ProcessingMethod(3, "second",
                                    List.of()))));
            assertThat(back.run().defaultDataProcessingId()).isEqualTo("none");
            assertThat(back.run().defaultSourceFileId()).isNull();
            assertThat(Files.readString(w.file(), UTF_8)).contains("<userParam name=\"run note\" type=\"xsd:double\""
                    + " value=\"1.5\" unitAccession=\"UO:0000010\" unitName=\"second\" unitCvRef=\"UO\">");
            assertThat(w.warnings()).contains(
                    String.format(MzMLWriter.A_8, "processingMethod MS:1000544 of a data processing without methods")
                            + " (1)",
                    String.format(MzMLWriter.A_8, "processingMethod softwareRef (the first software)") + " (1)",
                    String.format(MzMLWriter.A_18, "processingMethod softwareRef redirected to the first software")
                            + " (1)",
                    String.format(MzMLWriter.A_18, "run defaultSourceFileRef dropped") + " (1)",
                    String.format(MzMLWriter.A_18, "spectrumList defaultDataProcessingRef redirected to the first"
                            + " data processing") + " (1)");
        }
    }

    @Test
    void aNegativeProcessingMethodOrderIsRejected() throws IOException {
        FileMetadata m = metadata(List.of(software("sw")), List.of(instrument(0, "sw")), List.of(processing("dp", "sw"),
                new DataProcessing("bad", List.of(new ProcessingMethod(0, "sw", List.of()),
                        new ProcessingMethod(-1, "sw", List.of())))), run("run"));
        Path target = dir.resolve("negative-order.mzML");

        assertThatThrownBy(() -> MzMLWriter.open(target, contents(m), true)).isInstanceOf(MzPeakException.class)
                .hasMessageContaining(target.toString()).hasMessageContaining("dataProcessing 'bad'")
                .hasMessageContaining("processingMethod #1").hasMessageContaining("order -1");

        assertThat(listing()).isEmpty();
    }

    @Test
    void sourceFilesContactsSamplesAndScanSettingsAreWritten() {
        FileDescription description = new FileDescription(
                List.of(new Param(null, "MS:1000580", null, null)),
                List.of(new SourceFile("sf", null, null, List.of()),
                        new SourceFile("bad uri", "b.raw", "file:///x%zz", List.of())),
                List.of(new Contact("Ann", null, List.of(new Param("contact email", "MS:1000589", "a@b.c", null))),
                        new Contact(null, "Uni", List.of())));
        List<Param> target = List.of(new Param("selected ion m/z", "MS:1000744", 1000L, "MS:1000040"));
        List<ScanSettings> scanSettings = List.of(
                new ScanSettings("ss", List.of(new Param("max count of pixels x", "IMS:1000042", 3L, null)),
                        List.of("sf", "gone", ""),
                        List.of(target, List.of())),
                new ScanSettings("bare", List.of()));
        FileMetadata m = new FileMetadata(description, List.of(instrument(0, "sw")), List.of(processing("dp", "sw")),
                List.of(new Sample("s", null, List.of())), List.of(new Software("sw", null, List.of())),
                new MsRun("run", 0, "dp", "sf", null, List.of()));
        Contents contents = new Contents(m, scanSettings, List.of(), List.of(), List.of(), List.of());

        for (Written w : write("description", contents)) {
            ReadBack back = read(w.file());
            FileMetadata b = back.header().fileMetadata();
            assertThat(b.fileDescription().contents()).containsExactly(new Param("MSn spectrum", "MS:1000580", null,
                    null));
            assertThat(b.fileDescription().sourceFiles()).containsExactly(new SourceFile("sf", null, null, List.of()),
                    new SourceFile("bad_x0020_uri", "b.raw", "file:///x%25zz", List.of()));
            assertThat(b.fileDescription().contacts()).isEqualTo(description.contacts());
            assertThat(b.samples()).containsExactly(new Sample("s", null, List.of()));
            assertThat(b.software()).containsExactly(new Software("sw", null, List.of()));
            assertThat(b.run().defaultSourceFileId()).isEqualTo("sf");
            assertThat(back.header().scanSettings()).containsExactly(
                    new ScanSettings("ss", scanSettings.get(0).parameters(), List.of("sf"), List.of(target,
                            List.of())),
                    new ScanSettings("bare", List.of()));
            assertThat(back.header().cvList()).extracting(CvEntry::id).containsExactly("MS", "UO", "IMS");
            assertThat(w.warnings()).contains(MzMLWriter.A_22 + " (1)", MzMLWriter.A_2 + " (1)",
                    String.format(MzMLWriter.A_18, "scanSettings sourceFileRef dropped") + " (2)");
        }
    }

    @Test
    void paramsAreWrittenWithTheirTypesAndNames() throws IOException {
        List<Param> params = List.of(
                new Param("count", "MS:1000001", 7L, null),
                new Param(null, "MS:1000580", null, null),
                new Param(null, "MS:0000000", null, null),
                new Param("flag", null, true, null),
                new Param("ratio", null, Double.POSITIVE_INFINITY, null),
                new Param("absent", null, null, null),
                new Param("text", null, "x", "MS:9999999"),
                new Param(null, null, "nameless", null));
        FileMetadata m = metadata(List.of(new Software("sw", "1", params)), List.of(instrument(0, "sw")),
                List.of(processing("dp", "sw")), run("run"));

        for (Written w : write("params", contents(m))) {
            assertThat(read(w.file()).header().fileMetadata().software().get(0).parameters()).containsExactly(
                    new Param("count", "MS:1000001", 7L, null),
                    new Param("MSn spectrum", "MS:1000580", null, null),
                    new Param("MS:0000000", "MS:0000000", null, null),
                    new Param("flag", null, true, null),
                    new Param("ratio", null, Double.POSITIVE_INFINITY, null),
                    new Param("absent", null, null, null),
                    new Param("text", null, "x", null));
            assertThat(Files.readString(w.file(), UTF_8)).contains("<userParam name=\"text\" type=\"xsd:string\""
                    + " value=\"x\" unitAccession=\"MS:9999999\" unitCvRef=\"MS\">");
            assertThat(w.warnings()).contains(MzMLWriter.NAMED_BY_ACCESSION + " (1)",
                    MzMLWriter.PARAM_SKIPPED + " (1)");
        }
    }

    /**
     * A String cvParam value the mzML read types comes back typed (A-5), except a contact name the read takes
     * verbatim; a userParam keeps its String by its type; an empty value comes back as none (A-6).
     */
    @Test
    void valuesTheMzMLReadGivesBackOtherwiseAreCounted() {
        List<Param> params = List.of(
                new Param("number", "MS:1000001", "1.5", null),
                new Param("flag", "MS:1000002", "true", null),
                new Param("infinite", "MS:1000003", "INF", null),
                new Param("text", "MS:1000004", "abc", null),
                new Param("empty", "MS:1000005", "", null),
                new Param("user number", null, "42", null),
                new Param("user empty", null, "", null));
        FileMetadata m = new FileMetadata(new FileDescription(List.of(), List.of(new SourceFile("sf", "a.raw",
                "file:///data", List.of())), List.of(new Contact("007", null, List.of()))),
                List.of(instrument(0, "sw")), List.of(processing("dp", "sw")), List.of(),
                List.of(new Software("sw", "1", params)), run("run"));

        for (Written w : write("retyped", contents(m))) {
            FileMetadata back = read(w.file()).header().fileMetadata();
            assertThat(back.software().get(0).parameters()).extracting(Param::value)
                    .containsExactly(1.5, true, Double.POSITIVE_INFINITY, "abc", null, "42", null);
            assertThat(back.fileDescription().contacts()).extracting(Contact::name).containsExactly("007");
            assertThat(w.warnings()).contains(MzMLWriter.A_5 + " (3)", MzMLWriter.A_6 + " (2)");
        }
    }

    @Test
    void chromatogramsKeepTheirTypePolarityParamsAndDataProcessing() {
        FileMetadata m = metadata(List.of(software("sw")), List.of(instrument(0, "sw")),
                List.of(processing("dp", "sw"), processing("dp2", "sw")), run("run"));
        Chromatogram positive = new Chromatogram(0, "pos", "MS:1000235", new double[]{1}, new double[]{2}, null,
                Map.of(), Polarity.POSITIVE, "dp2", List.of(new Param("note", null, "n", null)), List.of());
        Chromatogram negative = new Chromatogram(1, "neg", null, new double[]{1}, new double[]{2}, "UO:0000269",
                Map.of(), Polarity.NEGATIVE, "dp2", List.of(), List.of());

        for (Written w : write("same-dp", contents(m, positive, negative))) {
            List<Chromatogram> back = read(w.file()).chromatograms();
            assertThat(back).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", null);
            assertThat(back).extracting(Chromatogram::polarity).containsExactly(Polarity.POSITIVE, Polarity.NEGATIVE);
            assertThat(back).extracting(Chromatogram::dataProcessingRef).containsExactly("dp2", "dp2");
            assertThat(back).extracting(Chromatogram::intensityUnit).containsExactly(null, "UO:0000269");
            assertThat(back.get(0).parameters()).containsExactly(new Param("note", null, "n", null));
        }
        Chromatogram own = new Chromatogram(2, "own", "MS:1000235", new double[]{1}, new double[]{2}, null, Map.of(),
                Polarity.UNKNOWN, "gone", List.of(), List.of());
        for (Written w : write("mixed-dp", contents(m, positive, own, negative))) {
            assertThat(read(w.file()).chromatograms()).extracting(Chromatogram::dataProcessingRef)
                    .containsExactly("dp2", null, "dp2");
            assertThat(w.warnings()).contains(String.format(MzMLWriter.A_18, "chromatogram dataProcessingRef dropped")
                    + " (1)");
        }
    }

    /**
     * A chromatogram carries exactly one type (chromatogram_must): a param repeating its typeCurie is dropped without
     * a count, one naming another type is dropped and counted (A-31), and without typeCurie the first type param is
     * the type.
     */
    @Test
    void aChromatogramCarriesExactlyOneType() throws IOException {
        Param tic = new Param("total ion current chromatogram", "MS:1000235", null, null);
        Param bpc = new Param("basepeak chromatogram", "MS:1000628", null, null);
        Param note = new Param("note", null, "n", null);
        FileMetadata m = metadata(List.of(software("sw")), List.of(new InstrumentConfiguration(0, List.of(),
                "sw", List.of(new Param("LTQ", "MS:1000447", null, null)))), List.of(processing("dp", "sw")),
                run("run"));
        Chromatogram repeated = new Chromatogram(0, "repeated", "MS:1000235", new double[]{1}, new double[]{2}, null,
                Map.of(), Polarity.UNKNOWN, null, List.of(tic, note), List.of());

        for (Written w : write("repeated-type", contents(m, repeated))) {
            assertThat(MzMLMappingCheck.violations(w.file())).isEmpty();
            assertThat(Files.readString(w.file(), UTF_8)).containsOnlyOnce("accession=\"MS:1000235\"");
            List<Chromatogram> back = read(w.file()).chromatograms();
            assertThat(back.get(0).typeCurie()).isEqualTo("MS:1000235");
            assertThat(back.get(0).parameters()).containsExactly(note);
            assertThat(w.warnings()).noneMatch(l -> l.startsWith("A-31"));
        }
        Chromatogram other = new Chromatogram(0, "other", "MS:1000235", new double[]{1}, new double[]{2}, null,
                Map.of(), Polarity.UNKNOWN, null, List.of(bpc, tic), List.of());
        Chromatogram fromParams = new Chromatogram(1, "params", null, new double[]{1}, new double[]{2}, null,
                Map.of(), Polarity.UNKNOWN, null, List.of(note, bpc, tic), List.of());
        for (Written w : write("other-type", contents(m, other, fromParams))) {
            assertThat(MzMLMappingCheck.violations(w.file())).isEmpty();
            List<Chromatogram> back = read(w.file()).chromatograms();
            assertThat(back).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235", "MS:1000628");
            assertThat(back).extracting(Chromatogram::parameters).containsExactly(List.of(), List.of(note));
            assertThat(w.warnings()).contains(MzMLWriter.A_31 + " (2)");
        }
    }

    @Test
    void aChromatogramKeepsItsFirstPrecursorAsAnExternalReference() {
        Precursor first = new Precursor(5L, "scan=9",
                new IsolationWindow(445.3, 0.5, null, List.of(new Param("ms level", null, 1L, null))),
                List.of(new SelectedIon(445.34, 2, 120053.0, List.of(new Param("other", null, "o", null),
                                new Param("inverse reduced ion mobility", "MS:1002815", 1.1, "MS:1002814"))),
                        new SelectedIon(Double.NaN, null, null, List.of())),
                new Activation(List.of(new Param("other", null, "o", null), new Param(null, "MS:1000045", 35.0,
                        "UO:0000266"), new Param(null, "MS:1000133", null, null))));
        Precursor second = new Precursor(null, "scan=10", null, List.of(), null);
        Chromatogram withTwo = withPrecursors(0, first, second);
        Chromatogram userWindow = withPrecursors(1, new Precursor(null, null,
                new IsolationWindow(null, null, null, List.of(new Param("w", null, null, null))), List.of(), null));
        Chromatogram emptyWindow = withPrecursors(2, new Precursor(null, "ext", new IsolationWindow(null, null, null),
                List.of(), null));
        Chromatogram noWindow = withPrecursors(3, new Precursor(null, "ext", null, List.of(), null));

        for (Written w : write("precursors", contents(metadata(), withTwo, userWindow, emptyWindow, noWindow))) {
            List<Chromatogram> back = read(w.file()).chromatograms();
            assertThat(back.get(0).precursors()).containsExactly(new Precursor(null, "scan=9",
                    new IsolationWindow(445.3, 0.5, null, List.of(new Param("ms level", null, 1L, null))),
                    List.of(new SelectedIon(445.34, 2, 120053.0, List.of(
                                    new Param("inverse reduced ion mobility", "MS:1002815", 1.1, "MS:1002814"),
                                    new Param("other", null, "o", null))),
                            new SelectedIon(Double.NaN, null, null, List.of())),
                    new Activation(List.of(new Param("collision-induced dissociation", "MS:1000133", null, null),
                            new Param("collision energy", "MS:1000045", 35.0, "UO:0000266"),
                            new Param("other", null, "o", null)))));
            assertThat(back.get(1).precursors()).containsExactly(new Precursor(null, null,
                    new IsolationWindow(null, null, null, List.of(new Param("w", null, null, null))), List.of(), null));
            assertThat(back.subList(2, 4)).allSatisfy(c -> assertThat(c.precursors())
                    .containsExactly(new Precursor(null, "ext", null, List.of(), null)));
            assertThat(w.warnings()).contains(MzMLWriter.A_11 + " (1)", MzMLWriter.A_19 + " (1)");
        }
        for (Written w : write("source-file", contents(metadata(), withTwo))) {
            uk.ac.ebi.jmzml.model.mzml.Precursor p = jmzmlChromatograms(w.file()).get(0).getPrecursor();
            assertThat(p.getExternalSpectrumID()).isEqualTo("scan=9");
            assertThat(p.getSourceFileRef()).isEqualTo("sf");
            assertThat(p.getSpectrumRef()).isNull();
        }
        FileMetadata noSourceFile = new FileMetadata(new FileDescription(List.of(), List.of()),
                List.of(instrument(0, "sw")), List.of(processing("dp", "sw")), List.of(), List.of(software("sw")),
                run("run"));
        for (Written w : write("no-source-file", contents(noSourceFile, withTwo))) {
            uk.ac.ebi.jmzml.model.mzml.Precursor p = jmzmlChromatograms(w.file()).get(0).getPrecursor();
            assertThat(p.getExternalSpectrumID()).isNull();
            assertThat(p.getSourceFileRef()).isNull();
            assertThat(w.warnings()).contains(String.format(MzMLWriter.A_18, "run defaultSourceFileRef dropped")
                    + " (1)", String.format(MzMLWriter.A_18, "spectrum refs to an external document without a source"
                    + " file dropped") + " (1)");
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** A written document: its file, whether it is zlib-compressed, and its writer's warnings. */
    private record Written(Path file, boolean zlib, List<String> warnings) {
        @Override
        public String toString() {
            return zlib ? "zlib" : "no zlib";
        }
    }

    /** The header and chromatograms {@link MzMLReader} reads from a document without spectra. */
    private record ReadBack(MzMLReader.Header header, List<Chromatogram> chromatograms) {
    }

    /** Writes {@code contents} with and without zlib to {@code <name>.zlib.mzML} and {@code <name>.plain.mzML}. */
    private List<Written> write(String name, Contents contents) {
        List<Written> written = new ArrayList<>();
        for (boolean zlib : List.of(true, false)) {
            Path file = dir.resolve(name + (zlib ? ".zlib" : ".plain") + ".mzML");
            MzMLWriter writer = MzMLWriter.open(file, contents, zlib);
            writer.finish();
            MzMLAssert.valid(file);
            written.add(new Written(file, zlib, writer.warnings()));
        }
        assertThat(written.get(0).warnings()).isEqualTo(written.get(1).warnings());
        return written;
    }

    private static ReadBack read(Path file) {
        MzMLReader reader = new MzMLReader(file);
        List<Chromatogram> chromatograms = new ArrayList<>();
        reader.read(new MzMLReader.Visitor() {
            @Override
            public void spectrum(Spectrum spectrum) {
                throw new AssertionError("no spectrum expected: " + spectrum.description().id());
            }

            @Override
            public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
                throw new AssertionError("no wavelength spectrum expected: " + spectrum.id());
            }

            @Override
            public void chromatogram(Chromatogram chromatogram) {
                chromatograms.add(chromatogram);
            }
        });
        return new ReadBack(reader.header(), chromatograms);
    }

    /** The cvParam accessions of every binaryDataArray of every chromatogram in {@code file}, in document order. */
    private static List<List<String>> arrayTerms(Path file) {
        List<List<String>> terms = new ArrayList<>();
        for (uk.ac.ebi.jmzml.model.mzml.Chromatogram c : jmzmlChromatograms(file)) {
            for (BinaryDataArray a : c.getBinaryDataArrayList().getBinaryDataArray()) {
                terms.add(a.getCvParam().stream().map(CVParam::getAccession).toList());
            }
        }
        return terms;
    }

    /** The chromatograms of {@code file} as jmzML reads them. */
    private static List<uk.ac.ebi.jmzml.model.mzml.Chromatogram> jmzmlChromatograms(Path file) {
        List<uk.ac.ebi.jmzml.model.mzml.Chromatogram> chromatograms = new ArrayList<>();
        MzMLObjectIterator<uk.ac.ebi.jmzml.model.mzml.Chromatogram> it = new MzMLUnmarshaller(file.toFile(), false,
                null).unmarshalCollectionFromXpath("/run/chromatogramList/chromatogram",
                uk.ac.ebi.jmzml.model.mzml.Chromatogram.class);
        while (it.hasNext()) {
            chromatograms.add(it.next());
        }
        return chromatograms;
    }

    private List<String> listing() throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static long[] bits(double[] values) {
        return Arrays.stream(values).mapToLong(Double::doubleToRawLongBits).toArray();
    }

    private static CvEntry known(String id) {
        return CvEntry.known().stream().filter(cv -> cv.id().equals(id)).findFirst().orElseThrow();
    }

    private static Contents contents(FileMetadata m, Chromatogram... chromatograms) {
        return new Contents(m, List.of(), List.of(), List.of(), List.of(), List.of(chromatograms));
    }

    /** A complete small header: source file sf, software sw, IC 0, data processing dp, run run. */
    private static FileMetadata metadata() {
        return metadata(run("run"));
    }

    private static FileMetadata metadata(MsRun run) {
        return metadata(List.of(software("sw")), List.of(instrument(0, "sw")), List.of(processing("dp", "sw")), run);
    }

    private static FileMetadata metadata(List<Software> software, List<InstrumentConfiguration> instruments,
                                         List<DataProcessing> processing, MsRun run) {
        return new FileMetadata(new FileDescription(List.of(), List.of(new SourceFile("sf", "a.raw",
                "file:///data", List.of()))), instruments, processing, List.of(), software, run);
    }

    private static MsRun run(String id) {
        return run(id, null);
    }

    private static MsRun run(String id, String startTime) {
        return new MsRun(id, 0, "dp", "sf", startTime, List.of());
    }

    private static Software software(String id) {
        return new Software(id, "1.0", List.of());
    }

    private static InstrumentConfiguration instrument(int id, String software) {
        return new InstrumentConfiguration(id, List.of(component(ComponentType.ION_SOURCE, 1, SOURCE),
                component(ComponentType.ANALYZER, 2, ANALYZER), component(ComponentType.DETECTOR, 3, DETECTOR)),
                software, List.of());
    }

    private static Component component(ComponentType type, int order, Param term) {
        return new Component(type, order, term == null ? List.of() : List.of(term));
    }

    private static DataProcessing processing(String id, String software) {
        return new DataProcessing(id, List.of(new ProcessingMethod(0, software,
                List.of(new Param("Conversion to mzML", "MS:1000544", null, null)))));
    }

    private static Chromatogram chromatogram(String id) {
        return new Chromatogram(0, id, "MS:1000235", new double[]{1}, new double[]{2});
    }

    private static Chromatogram withPrecursors(long index, Precursor... precursors) {
        return new Chromatogram(index, "c" + index, "MS:1000627", new double[]{1}, new double[]{2}, null, Map.of(),
                Polarity.UNKNOWN, null, List.of(), List.of(precursors));
    }
}
