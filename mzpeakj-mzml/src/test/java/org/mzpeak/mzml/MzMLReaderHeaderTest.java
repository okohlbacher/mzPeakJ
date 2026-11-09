package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Param;
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
import org.mzpeak.mzml.ModelAssert.Mode;
import org.mzpeak.mzml.ModelAssert.Model;

import javax.xml.stream.XMLStreamException;
import java.io.CharConversionException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * MZ-16: {@link MzMLReader#header()} (mzML design 5.1-5.3, 6.1, 8.1, 10). The header of each vendored mzML fixture is
 * pinned: tiny in full (a), small (b), TOFsulfas and diaPASEF (c); the software, data processings, samples and source
 * files of small and TOFsulfas equal those of the mzPeak files the reference converter made from them (d). Branch
 * documents derived from tiny by string edits pin each rule and each rejection (e).
 */
class MzMLReaderHeaderTest {

    private static final String TINY = MzMLFixtures.TINY;

    // ---- (a) tiny ------------------------------------------------------------------------------------------------

    @Test
    void tinyHeaderIsReadInFull(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(TINY, dir));
        MzMLReader.Header header = reader.header();
        FileMetadata m = header.fileMetadata();

        assertThat(header.cvList()).containsExactly(
                new CvEntry("MS", "Proteomics Standards Initiative Mass Spectrometry Ontology",
                        "http://psidev.cvs.sourceforge.net/*checkout*/psidev/psi/psi-ms/mzML/controlledVocabulary/"
                                + "psi-ms.obo", "2.26.0"),
                new CvEntry("UO", "Unit Ontology",
                        "http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/unit.obo",
                        "14:07:2009"));

        FileDescription fd = m.fileDescription();
        assertThat(fd.contents()).containsExactly(
                new Param("MSn spectrum", "MS:1000580", null, null),
                new Param("centroid spectrum", "MS:1000127", null, null));
        assertThat(fd.sourceFiles()).containsExactly(
                new SourceFile("tiny1.yep", "tiny1.yep", "file://F:/data/Exp01", List.of(
                        new Param("Bruker/Agilent YEP file", "MS:1000567", null, null),
                        new Param("SHA-1", "MS:1000569", "1234567890123456789012345678901234567890", null),
                        new Param("Bruker/Agilent YEP nativeID format", "MS:1000771", null, null))),
                new SourceFile("tiny.wiff", "tiny.wiff", "file://F:/data/Exp01", List.of(
                        new Param("ABI WIFF file", "MS:1000562", null, null),
                        new Param("SHA-1", "MS:1000569", "2345678901234567890123456789012345678901", null),
                        new Param("WIFF nativeID format", "MS:1000770", null, null))),
                new SourceFile("sf_parameters", "parameters.par", "file://C:/settings/", List.of(
                        new Param("parameter file", "MS:1000740", null, null),
                        new Param("SHA-1", "MS:1000569", "3456789012345678901234567890123456789012", null),
                        new Param("no nativeID format", "MS:1000824", null, null))));
        assertThat(fd.contacts()).containsExactly(new Contact("William Pennington", "Higglesworth University",
                List.of(new Param("contact address", "MS:1000587", "12 Higglesworth Avenue, 12045, HI, USA", null),
                        new Param("contact URL", "MS:1000588", "http://www.higglesworth.edu/", null),
                        new Param("contact email", "MS:1000589", "wpennington@higglesworth.edu", null))));

        assertThat(m.samples()).containsExactly(
                new Sample("_x0032_0090101_x0020_-_x0020_Sample_x0020_1", "Sample 1", List.of()));
        assertThat(m.software()).containsExactly(
                new Software("Bioworks", "3.3.1 sp1", List.of(new Param("Bioworks", "MS:1000533", null, null))),
                new Software("pwiz", "1.0", List.of(new Param("ProteoWizard", "MS:1000615", null, null))),
                new Software("CompassXtract", "2.0.5",
                        List.of(new Param("CompassXtract", "MS:1000718", null, null))));

        assertThat(header.scanSettings()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo("tiny_x0020_scan_x0020_settings");
            assertThat(s.parameters()).isEmpty();
            assertThat(s.sourceFileRefs()).containsExactly("sf_parameters");
            assertThat(s.targets()).containsExactly(
                    List.of(new Param("selected ion m/z", "MS:1000744", 1000L, "MS:1000040")),
                    List.of(new Param("selected ion m/z", "MS:1000744", 1200L, "MS:1000040")));
        });

        assertThat(m.instrumentConfigurations()).containsExactly(new InstrumentConfiguration(0, List.of(
                new Component(ComponentType.ION_SOURCE, 1, List.of(new Param("nanoelectrospray", "MS:1000398", null,
                        null))),
                new Component(ComponentType.ANALYZER, 2, List.of(new Param("quadrupole ion trap", "MS:1000082", null,
                        null))),
                new Component(ComponentType.DETECTOR, 3, List.of(new Param("electron multiplier", "MS:1000253", null,
                        null)))),
                "CompassXtract",
                List.of(new Param("LCQ Deca", "MS:1000554", null, null),
                        new Param("instrument serial number", "MS:1000529", 23433L, null))));

        assertThat(m.dataProcessingMethods()).containsExactly(
                new DataProcessing("CompassXtract_x0020_processing", List.of(new ProcessingMethod(1, "CompassXtract",
                        List.of(new Param("deisotoping", "MS:1000033", null, null),
                                new Param("charge deconvolution", "MS:1000034", null, null),
                                new Param("peak picking", "MS:1000035", null, null))))),
                new DataProcessing("pwiz_processing", List.of(new ProcessingMethod(2, "pwiz",
                        List.of(new Param("Conversion to mzML", "MS:1000544", null, null))))));

        assertThat(m.run()).isEqualTo(new MsRun("Experiment_x0020_1", 0, "pwiz_processing", "tiny1.yep",
                "2007-06-27T15:23:45.00035", List.of()));

        assertThat(reader.warnings()).containsExactlyInAnyOrder(
                "B-3 mzML@id and mzML@accession lost (1)",
                "B-1 referenceableParamGroups expanded inline (2)",
                "B-5 instrumentConfiguration ids [LCQ_x0020_Deca] numbered by position (1)",
                "B-7 run@sampleRef lost (1)");
    }

    @Test
    void theHeaderIsReadOnceAndCached(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(TINY, dir));
        assertThat(reader.warnings()).isEmpty();

        MzMLReader.Header first = reader.header();
        List<String> warnings = reader.warnings();

        assertThat(reader.header()).isSameAs(first);
        assertThat(reader.warnings()).isEqualTo(warnings).hasSize(4);
        assertThat(first.scanSettings()).isUnmodifiable();
        assertThat(first.fileMetadata().software()).isUnmodifiable();
        assertThat(first.fileMetadata().software().get(0).parameters()).isUnmodifiable();
    }

    // ---- (b) small -----------------------------------------------------------------------------------------------

    @Test
    void smallExpandsTheInstrumentGroupIntoBothConfigurations(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(MzMLFixtures.SMALL, dir));
        FileMetadata m = reader.header().fileMetadata();

        List<Param> common = List.of(new Param("LTQ FT", "MS:1000448", null, null),
                new Param("instrument serial number", "MS:1000529", "SN06061F", null));
        assertThat(m.instrumentConfigurations()).extracting(InstrumentConfiguration::id).containsExactly(0, 1);
        assertThat(m.instrumentConfigurations()).allSatisfy(ic -> {
            assertThat(ic.parameters()).isEqualTo(common);
            assertThat(ic.softwareReference()).isEqualTo("Xcalibur");
            assertThat(ic.components()).extracting(Component::componentType, Component::order).containsExactly(
                    tuple(ComponentType.ION_SOURCE, 1), tuple(ComponentType.ANALYZER, 2),
                    tuple(ComponentType.DETECTOR, 3));
        });
        assertThat(m.samples()).containsExactly(
                new Sample("_x0031_", "", List.of(new Param("sample name", "MS:1000002", 1L, null))));
        assertThat(m.run()).isEqualTo(new MsRun("small", 0, "pwiz_Reader_Thermo_conversion", "RAW1",
                "2005-07-20T19:44:22Z", List.of()));
        assertThat(reader.header().scanSettings()).isEmpty();
        assertThat(reader.warnings()).containsExactly(
                "B-3 mzML@id and mzML@accession lost (1)",
                "B-1 referenceableParamGroups expanded inline (1)");
    }

    // ---- (c) TOFsulfas and diaPASEF ------------------------------------------------------------------------------

    @Test
    void tofSulfasHasEighteenSourceFilesAndOneConfigurationWithTwoDetectors(@TempDir Path dir) throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, dir));
        FileMetadata m = reader.header().fileMetadata();

        assertThat(m.fileDescription().sourceFiles()).hasSize(18);
        assertThat(m.fileDescription().sourceFiles().get(17).location()).isEqualTo("file:///C:\\Users\\mobiu\\Downloads");
        assertThat(m.instrumentConfigurations()).singleElement().satisfies(ic -> {
            assertThat(ic.id()).isZero();
            assertThat(ic.parameters()).containsExactly(
                    new Param("Agilent instrument model", "MS:1000490", null, null),
                    new Param("instrument serial number", "MS:1000529", 0L, null),
                    new Param("instrument model", null, "TOF", null));
            assertThat(ic.components()).extracting(Component::componentType, Component::order).containsExactly(
                    tuple(ComponentType.ION_SOURCE, 1), tuple(ComponentType.ANALYZER, 2),
                    tuple(ComponentType.DETECTOR, 3), tuple(ComponentType.DETECTOR, 4));
        });
        assertThat(m.run().id()).isEqualTo("TOFsulfasMS4GHzDualMode_x002b_DADSpectra_x002b_UVSignal272-NoProfile");
        assertThat(m.run().defaultInstrumentId()).isZero();
        assertThat(m.run().defaultDataProcessingId()).isEqualTo("pwiz_Reader_conversion");
        assertThat(m.samples()).isEmpty();
    }

    @Test
    void diaPasefConfigurationIc1IsIdZeroWithTheGroupFirstAndATypedSerialNumber(@TempDir Path dir)
            throws IOException {
        MzMLReader reader = new MzMLReader(MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir));
        FileMetadata m = reader.header().fileMetadata();

        assertThat(m.instrumentConfigurations()).singleElement().satisfies(ic -> {
            assertThat(ic.id()).isZero();
            assertThat(ic.parameters()).containsExactly(
                    new Param("Bruker Daltonics timsTOF series", "MS:1003123", null, null),
                    new Param("instrument serial number", "MS:1000529", 1854399.63, null));
            assertThat(ic.parameters().get(1).value()).isInstanceOf(Double.class);
            assertThat(ic.components()).extracting(Component::componentType).containsExactly(
                    ComponentType.ION_SOURCE, ComponentType.ANALYZER, ComponentType.ANALYZER,
                    ComponentType.DETECTOR, ComponentType.DETECTOR);
        });
        assertThat(m.software().get(0).parameters()).containsExactly(
                new Param("Bruker software", "MS:1000692", null, null),
                new Param("software name", null, "TIMS SDK", null));
        assertThat(m.run().defaultInstrumentId()).isZero();
        assertThat(reader.warnings()).noneMatch(w -> w.startsWith("B-5"));
    }

    // ---- (d) against the mzPeak files of the reference converter --------------------------------------------------

    @Test
    void smallAndTofSulfasMatchTheirMzPeakFilesInSoftwareProcessingSamplesAndSourceFiles(@TempDir Path dir)
            throws IOException {
        assertSamePartsAsMzPeak(MzMLFixtures.extract(MzMLFixtures.SMALL, dir), "small.mzpeak");
        assertSamePartsAsMzPeak(MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, dir), "has_uv.mzpeak");
    }

    private static void assertSamePartsAsMzPeak(Path mzml, String mzpeak) {
        FileMetadata expected = new MzMLReader(mzml).header().fileMetadata();
        FileMetadata actual;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(mzpeak))) {
            actual = reader.fileMetadata();
        }
        List<Software> converterSoftware = actual.software().stream()
                .filter(s -> s.id().startsWith("mzpeak_")).toList();
        List<DataProcessing> converterProcessing = actual.dataProcessingMethods().stream()
                .filter(d -> d.id().startsWith("mzpeak_")).toList();
        assertThat(converterSoftware).as("the converter's own software in %s", mzpeak).hasSize(1);
        assertThat(converterProcessing).as("the converter's own data processing in %s", mzpeak).hasSize(1);

        ModelAssert.assertEqual(mzml.getFileName() + " vs " + mzpeak, parts(expected), parts(actual),
                Mode.MZML_VS_MZPEAK);
        assertThat(expected.software()).isNotEmpty();
        assertThat(expected.fileDescription().sourceFiles()).isNotEmpty();
    }

    /** A model holding only the software, data processings, samples and source files of {@code m}, not mzpeak_*. */
    private static Model parts(FileMetadata m) {
        FileMetadata only = new FileMetadata(
                new FileDescription(List.of(), m.fileDescription().sourceFiles()),
                List.of(),
                m.dataProcessingMethods().stream().filter(d -> !d.id().startsWith("mzpeak_")).toList(),
                m.samples(),
                m.software().stream().filter(s -> !s.id().startsWith("mzpeak_")).toList(),
                null);
        return new Model(List.of(), List.of(), List.of(), only, List.of(), List.of());
    }

    // ---- (e) branch documents ------------------------------------------------------------------------------------

    @Test
    void aVersionOtherThan11IsRejected(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "version=\"1.1.0\"", "version=\"1.0.0\""));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + file + " line 3 column " + endOfTag(file, "<mzML ")
                        + ": mzML version 1.0.0 is not supported; version 1.1 is required");
    }

    @Test
    void aDocumentWithoutVersionIsRejected(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, " version=\"1.1.0\"", ""));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageEndingWith(": mzML version null is not supported; version 1.1 is required");
    }

    @Test
    void aDtdIsRejected(@TempDir Path dir) throws IOException {
        String doctype = "<!DOCTYPE indexedmzML [<!ENTITY lab \"Higglesworth\">]>";
        Path file = tinyWith(dir, s -> replaceOnce(s, "?>\n<indexedmzML ", "?>\n" + doctype + "\n<indexedmzML "));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageMatching("mzML " + Pattern.quote(file.toString())
                        + " line 2 column \\d+: a DTD is not supported");
    }

    @Test
    void malformedXmlIsRejectedWithItsLineAndColumn(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "</cvList>", "</cvLis>"));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasCauseInstanceOf(XMLStreamException.class)
                .hasMessageMatching("mzML " + Pattern.quote(file.toString()) + " line 7 column \\d+: "
                        + "The element type \"cvList\" must be terminated by the matching end-tag \"</cvList>\".");
    }

    @Test
    void anEmptyFileIsRejectedAsMalformed(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("empty.mzML"), "");

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + file + " line 1 column 1: Premature end of file.");
    }

    @Test
    void aParserErrorWithoutLocationNamesTheFileOnly() {
        Path file = Path.of("x.mzML");
        XMLStreamException cause = new XMLStreamException("boom");

        MzPeakException e = MzMLReader.malformed(file, cause);

        assertThat(e).hasMessage("mzML x.mzML: boom").hasCause(cause);
        assertThat(MzMLReader.malformed(file, new XMLStreamException())).hasMessage("mzML x.mzML: null");
    }

    @Test
    void aPlainMzMLReadsLikeTheIndexedOne(@TempDir Path dir) throws IOException {
        MzMLReader indexed = new MzMLReader(MzMLFixtures.extract(TINY, dir));
        MzMLReader plain = new MzMLReader(plainTiny(dir, s -> s));

        assertThat(plain.header()).isEqualTo(indexed.header());
        assertThat(plain.warnings()).isEqualTo(indexed.warnings());
    }

    @Test
    void aDocumentWithoutNamespaceReadsTheSame(@TempDir Path dir) throws IOException {
        MzMLReader indexed = new MzMLReader(MzMLFixtures.extract(TINY, dir));
        Path file = tinyWith(dir, s -> s.replace(" xmlns=\"http://psi.hupo.org/ms/mzml\"", ""));
        assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).doesNotContain("psi.hupo.org/ms/mzml\"");

        MzMLReader bare = new MzMLReader(file);

        assertThat(bare.header()).isEqualTo(indexed.header());
        assertThat(bare.warnings()).isEqualTo(indexed.warnings());
    }

    @Test
    void absentOptionalListsGiveEmptyLists(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> s
                .replaceAll("(?s)\\s*<sourceFileList .*?</sourceFileList>", "")
                .replaceAll("(?s)\\s*<contact>.*?</contact>", "")
                .replaceAll("(?s)\\s*<referenceableParamGroupList .*?</referenceableParamGroupList>", "")
                .replaceAll("\\s*<referenceableParamGroupRef ref=\"[^\"]*\"/>", "")
                .replaceAll("(?s)\\s*<sampleList .*?</sampleList>", "")
                .replaceAll("(?s)\\s*<scanSettingsList .*?</scanSettingsList>", "")
                .replace(" sampleRef=\"_x0032_0090101_x0020_-_x0020_Sample_x0020_1\"", ""));

        MzMLReader reader = new MzMLReader(file);
        MzMLReader.Header header = reader.header();

        assertThat(header.fileMetadata().fileDescription().sourceFiles()).isEmpty();
        assertThat(header.fileMetadata().fileDescription().contacts()).isEmpty();
        assertThat(header.fileMetadata().fileDescription().contents()).hasSize(2);
        assertThat(header.fileMetadata().samples()).isEmpty();
        assertThat(header.scanSettings()).isEmpty();
        assertThat(header.fileMetadata().software()).hasSize(3);
        assertThat(reader.warnings()).containsExactly(
                "B-3 mzML@id and mzML@accession lost (1)",
                "B-5 instrumentConfiguration ids [LCQ_x0020_Deca] numbered by position (1)");
    }

    @Test
    void negativeConfigurationIdsFollowRuleIcExactly(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> s.replace("LCQ_x0020_Deca", "IC_-1"));

        MzMLReader reader = new MzMLReader(file);
        FileMetadata m = reader.header().fileMetadata();

        assertThat(m.instrumentConfigurations()).extracting(InstrumentConfiguration::id).containsExactly(-1);
        assertThat(m.run().defaultInstrumentId()).isEqualTo(-1);
        assertThat(reader.warnings()).noneMatch(w -> w.startsWith("B-5"));
    }

    @Test
    void configurationIdsOutsideRuleIcAreNumberedInDocumentOrderWithOneWarning(@TempDir Path dir)
            throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s.replace("LCQ_x0020_Deca", "IC1"),
                "</instrumentConfiguration>\n",
                "</instrumentConfiguration>\n      <instrumentConfiguration id=\"foo\">\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000554\" name=\"LCQ Deca\" value=\"\"/>\n"
                        + "      </instrumentConfiguration>\n"));

        MzMLReader reader = new MzMLReader(file);
        FileMetadata m = reader.header().fileMetadata();

        assertThat(m.instrumentConfigurations()).extracting(InstrumentConfiguration::id).containsExactly(0, 1);
        assertThat(m.instrumentConfigurations().get(1)).isEqualTo(new InstrumentConfiguration(1, List.of(), null,
                List.of(new Param("LCQ Deca", "MS:1000554", null, null))));
        assertThat(m.run().defaultInstrumentId()).isZero();
        assertThat(reader.warnings()).filteredOn(w -> w.startsWith("B-5"))
                .containsExactly("B-5 instrumentConfiguration ids [IC1, foo] numbered by position (1)");
    }

    @Test
    void aDanglingParamGroupRefIsRejected(@TempDir Path dir) throws IOException {
        String ref = "<referenceableParamGroupRef ref=\"nope\"/>";
        Path file = tinyWith(dir, s -> replaceOnce(s,
                "<cvParam cvRef=\"MS\" accession=\"MS:1000554\"", ref + "\n<cvParam cvRef=\"MS\" accession=\"MS:1000554\""));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + file + " line " + lineOf(file, ref) + " column " + endOfTag(file, ref)
                        + ": referenceableParamGroupRef 'nope' names no referenceableParamGroup");
    }

    /**
     * Each cvParam whose cvRef is not its accession's prefix counts once, a cvRef that is the start of the prefix
     * without its colon ('M') or empty included.
     */
    @Test
    void aCvRefAliasIsCountedOnce(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(replaceOnce(s, "<cvList count=\"2\">",
                        "<cvList count=\"3\">\n      <cv id=\"FOO\" fullName=\"Foo\" version=\"1\" URI=\"http://example.org/foo\"/>"),
                "<fileContent>", "</fileContent>",
                "  <cvParam cvRef=\"FOO\" accession=\"MS:1000511\" name=\"ms level\" value=\"1\"/>\n"
                        + "      <cvParam cvRef=\"M\" accession=\"MS:1000580\" name=\"MSn spectrum\" value=\"\"/>\n"
                        + "      <cvParam cvRef=\"\" accession=\"MS:1000128\" name=\"profile spectrum\" value=\"\"/>\n"
                        + "      </fileContent>"));

        MzMLReader reader = new MzMLReader(file);
        MzMLReader.Header header = reader.header();

        assertThat(header.cvList()).extracting(CvEntry::id).containsExactly("FOO", "MS", "UO");
        assertThat(header.fileMetadata().fileDescription().contents())
                .contains(new Param("ms level", "MS:1000511", 1L, null));
        assertThat(header.fileMetadata().fileDescription().contents()).extracting(Param::accession)
                .contains("MS:1000580", "MS:1000128");
        assertThat(reader.warnings()).contains("B-30 cvRef aliases replaced by the accession prefix (3)");
    }

    @Test
    void anInfValueIsTypedAsPositiveInfinity(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, "<fileContent>",
                "<cvParam cvRef=\"MS\" accession=\"MS:1000127\" name=\"centroid spectrum\" value=\"\"/>",
                "<cvParam cvRef=\"MS\" accession=\"MS:1000127\" name=\"centroid spectrum\" value=\"INF\"/>"));

        List<Param> contents = new MzMLReader(file).header().fileMetadata().fileDescription().contents();

        assertThat(contents).extracting(Param::accession, Param::value).containsExactly(
                tuple("MS:1000580", null), tuple("MS:1000127", Double.POSITIVE_INFINITY));
    }

    @Test
    void aRootOtherThanMzMLIsRejected(@TempDir Path dir) throws IOException {
        Path foo = Files.writeString(dir.resolve("foo.mzML"), "<?xml version=\"1.0\"?>\n<foo version=\"1.1.0\"/>\n");
        Path empty = Files.writeString(dir.resolve("empty-index.mzML"), "<?xml version=\"1.0\"?>\n<indexedmzML/>\n");

        assertThatThrownBy(() -> new MzMLReader(foo).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessage("mzML " + foo + " line 2 column 23: not an mzML document: no <mzML> element at the root "
                        + "or inside <indexedmzML>");
        assertThatThrownBy(() -> new MzMLReader(empty).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("not an mzML document");
    }

    @Test
    void aDocumentWithoutRunIsRejected(@TempDir Path dir) throws IOException {
        Path file = plainTiny(dir, s -> s.substring(0, s.indexOf("    <run ")) + "</mzML>");

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageMatching("mzML " + Pattern.quote(file.toString()) + " line \\d+ column \\d+: no <run> element");
    }

    @Test
    void aRunWithoutListsIsReadToItsEndWithItsParams(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> s.replaceAll("(?s)<spectrumList .*</chromatogramList>",
                "<userParam name=\"checked\" value=\"yes\" type=\"xsd:boolean\"/>"));

        MzMLReader reader = new MzMLReader(file);
        MsRun run = reader.header().fileMetadata().run();

        assertThat(run.defaultDataProcessingId()).isNull();
        assertThat(run.parameters()).containsExactly(new Param("checked", null, "yes", null));
        assertThat(run.startTime()).isEqualTo("2007-06-27T15:23:45.00035");
        assertThat(reader.warnings())
                .contains("B-14 userParam values that do not match their type kept as text (1)");
    }

    @Test
    void theChromatogramListDefaultIsNoRunDefault(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> s.replaceAll("(?s)<spectrumList .*</spectrumList>\\s*", ""));
        assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
                .contains("<chromatogramList count=\"2\" defaultDataProcessingRef=\"pwiz_processing\">");

        MsRun run = new MzMLReader(file).header().fileMetadata().run();

        assertThat(run.defaultDataProcessingId()).isNull();
        assertThat(run.id()).isEqualTo("Experiment_x0020_1");
    }

    @Test
    void aMissingFileIsAnMzPeakExceptionWithTheIoCause(@TempDir Path dir) {
        Path missing = dir.resolve("missing.mzML");

        assertThatThrownBy(() -> new MzMLReader(missing).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("mzML " + missing + ": cannot read: ")
                .hasCauseInstanceOf(NoSuchFileException.class);
        assertThatThrownBy(() -> new MzMLReader(null)).isInstanceOf(NullPointerException.class);
    }

    /** A directory fails while the parser reads it, not when it is opened; it is still unreadable, not malformed. */
    @Test
    void aDirectoryIsAnMzPeakExceptionWithTheIoCause(@TempDir Path dir) {
        assertThatThrownBy(() -> new MzMLReader(dir).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("mzML " + dir + ": cannot read: ")
                .hasCauseInstanceOf(IOException.class);
    }

    /**
     * The parser reports a byte that is invalid in the declared encoding through an IOException (the JDK's
     * MalformedByteSequenceException, a CharConversionException), but it is a well-formedness error: malformed, with
     * its line and column, not unreadable.
     */
    @Test
    void anInvalidUtf8ByteIsMalformedWithItsLineAndColumn(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(replaceOnce(s, "encoding=\"ISO-8859-1\"", "encoding=\"UTF-8\""),
                "<fileContent>", "<!-- ÿ --><fileContent>"));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasCauseInstanceOf(XMLStreamException.class)
                .hasRootCauseInstanceOf(CharConversionException.class)
                .hasMessage("mzML " + file + " line " + lineOf(file, "<!-- ") + " column 11: Invalid byte 1 of 1-byte"
                        + " UTF-8 sequence.");
    }

    @Test
    void aParserErrorThatAnIoErrorCausedIsUnreadableAndAnyOtherMalformed() {
        Path file = Path.of("x.mzML");
        IOException io = new IOException("disk gone");
        XMLStreamException causeOnly = new XMLStreamException("wrapped");
        causeOnly.initCause(io);
        XMLStreamException other = new XMLStreamException("boom", new IllegalStateException("state"));
        XMLStreamException badByte = new XMLStreamException("bad byte", new CharConversionException("invalid"));

        assertThat(MzMLReader.failure(file, new XMLStreamException("wrapped", io)))
                .hasMessage("mzML x.mzML: cannot read: disk gone").hasCause(io);
        assertThat(MzMLReader.failure(file, causeOnly)).hasMessage("mzML x.mzML: cannot read: disk gone").hasCause(io);
        assertThat(MzMLReader.failure(file, other)).hasMessage("mzML x.mzML: boom").hasCause(other);
        assertThat(MzMLReader.failure(file, badByte)).hasMessage("mzML x.mzML: bad byte").hasCause(badByte);
    }

    /**
     * JDK 17 and 21 set no element depth limit by default (set here as the system default), and the reader's
     * recursion over nested elements ended in a StackOverflowError; the reader limits the depth to 100 itself.
     */
    @Test
    void elementsNestedDeeperThan100AreRejectedWithTheirLineAndColumn(@TempDir Path dir) throws IOException {
        String nested = "<x>".repeat(200_000) + "</x>".repeat(200_000);
        Path file = tinyWith(dir, s -> replaceOnce(s, "<fileContent>", nested + "\n      <fileContent>"));
        String property = "jdk.xml.maxElementDepth";
        String previous = System.getProperty(property);
        System.setProperty(property, "0");
        try {
            assertThatThrownBy(() -> new MzMLReader(file).header())
                    .isInstanceOf(MzPeakException.class)
                    .hasCauseInstanceOf(XMLStreamException.class)
                    .hasMessageMatching("mzML " + Pattern.quote(file.toString()) + " line " + lineOf(file, "<x>")
                            + " column \\d+: JAXP00010006: .*\"101\".*\"100\".*");
        } finally {
            if (previous == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, previous);
            }
        }
    }

    @Test
    void aRunDefaultInstrumentConfigurationRefNamingNoneReadsAsNullWithAWarning(@TempDir Path dir)
            throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "defaultInstrumentConfigurationRef=\"LCQ_x0020_Deca\"",
                "defaultInstrumentConfigurationRef=\"missing_IC\""));

        MzMLReader reader = new MzMLReader(file);

        assertThat(reader.header().fileMetadata().run().defaultInstrumentId()).isNull();
        assertThat(reader.warnings()).contains(
                "B-39 instrument configuration refs that name no instrument configuration read as null (1)");

        // no ref at all (the attribute is required) is null too, but names nothing, so it is no B-39
        Path none = tinyWith(dir, s -> replaceOnce(s, " defaultInstrumentConfigurationRef=\"LCQ_x0020_Deca\"", ""));
        MzMLReader noRef = new MzMLReader(none);
        assertThat(noRef.header().fileMetadata().run().defaultInstrumentId()).isNull();
        assertThat(noRef.warnings()).noneMatch(w -> w.startsWith("B-39"));
    }

    @Test
    void anOrderThatIsNoIntegerIsRejected(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<processingMethod order=\"2\"",
                "<processingMethod order=\"second\""));

        assertThatThrownBy(() -> new MzMLReader(file).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageMatching("mzML " + Pattern.quote(file.toString())
                        + " line \\d+ column \\d+: <processingMethod> order 'second' is not an integer");
    }

    @Test
    void aMissingRequiredAttributeIsRejected(@TempDir Path dir) throws IOException {
        Path noAccession = tinyWith(dir, s -> replaceOnce(s, "accession=\"MS:1000554\" ", ""));
        Path noOrder = tinyWith(dir, s -> replaceOnce(s, "<source order=\"1\">", "<source>"));

        assertThatThrownBy(() -> new MzMLReader(noAccession).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageMatching("mzML .* line \\d+ column \\d+: <cvParam> has no accession");
        assertThatThrownBy(() -> new MzMLReader(noOrder).header())
                .isInstanceOf(MzPeakException.class)
                .hasMessageMatching("mzML .* line \\d+ column \\d+: <source> has no order");
    }

    @Test
    void emptySoftwareVersionAndSourceFileNameAndLocationAreNull(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(replaceOnce(s, "<software id=\"pwiz\" version=\"1.0\">",
                        "<software id=\"pwiz\" version=\"\">"),
                "<sourceFile id=\"tiny.wiff\" name=\"tiny.wiff\" location=\"file://F:/data/Exp01\">",
                "<sourceFile id=\"tiny.wiff\" name=\"\" location=\"\">"));

        FileMetadata m = new MzMLReader(file).header().fileMetadata();

        assertThat(m.software().get(1).id()).isEqualTo("pwiz");
        assertThat(m.software().get(1).version()).isNull();
        assertThat(m.fileDescription().sourceFiles().get(1))
                .extracting(SourceFile::id, SourceFile::name, SourceFile::location)
                .containsExactly("tiny.wiff", null, null);
    }

    @Test
    void scanSettingsRefAndDocumentAccessionAreCountedLosses(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(replaceOnce(s,
                        "<instrumentConfiguration id=\"LCQ_x0020_Deca\">",
                        "<instrumentConfiguration id=\"LCQ_x0020_Deca\" scanSettingsRef=\"tiny_x0020_scan_x0020_settings\">"),
                " version=\"1.1.0\">", " accession=\"PXD000000\" version=\"1.1.0\">"));

        MzMLReader reader = new MzMLReader(file);
        reader.header();

        assertThat(reader.warnings()).contains("B-3 mzML@id and mzML@accession lost (2)",
                "B-6 instrumentConfiguration@scanSettingsRef lost (1)");
    }

    @Test
    void contactsTakeTheFirstNameAndAffiliationAndKeepTheRestAsParams(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(replaceOnce(s, "</contact>",
                "  <cvParam cvRef=\"MS\" accession=\"MS:1000586\" name=\"contact name\" value=\"1.50\"/>\n"
                        + "      </contact>\n      <contact>\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000586\" name=\"contact name\" value=\"007\"/>\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000590\" name=\"contact affiliation\""
                        + " value=\"1e3\"/>\n"
                        + "      </contact>\n      <contact>\n"
                        + "        <referenceableParamGroupRef ref=\"ContactParams\"/>\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000586\" name=\"contact name\" value=\"Own\"/>\n"
                        + "      </contact>\n      <contact>\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000589\" name=\"contact email\" value=\"x@y\"/>\n"
                        + "      </contact>"),
                "<referenceableParamGroupList count=\"2\">",
                "<referenceableParamGroupList count=\"3\">\n"
                        + "      <referenceableParamGroup id=\"ContactParams\">\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000586\" name=\"contact name\" value=\"0.10\"/>\n"
                        + "        <cvParam cvRef=\"MS\" accession=\"MS:1000590\" name=\"contact affiliation\""
                        + " value=\"\"/>\n"
                        + "      </referenceableParamGroup>"));

        List<Contact> contacts = new MzMLReader(file).header().fileMetadata().fileDescription().contacts();

        assertThat(contacts).hasSize(4);
        assertThat(contacts.get(0).name()).isEqualTo("William Pennington");
        assertThat(contacts.get(0).affiliation()).isEqualTo("Higglesworth University");
        assertThat(contacts.get(0).parameters()).extracting(Param::accession, Param::value)
                .containsExactly(tuple("MS:1000587", "12 Higglesworth Avenue, 12045, HI, USA"),
                        tuple("MS:1000588", "http://www.higglesworth.edu/"),
                        tuple("MS:1000589", "wpennington@higglesworth.edu"), tuple("MS:1000586", 1.5));
        // Name and affiliation are text, verbatim: no typing of numeric-looking values.
        assertThat(contacts.get(1)).isEqualTo(new Contact("007", "1e3", List.of()));
        // The referenced group's params come first; its empty affiliation is null.
        assertThat(contacts.get(2)).isEqualTo(new Contact("0.10", null,
                List.of(new Param("contact name", "MS:1000586", "Own", null))));
        assertThat(contacts.get(3)).isEqualTo(new Contact(null, null,
                List.of(new Param("contact email", "MS:1000589", "x@y", null))));
    }

    @Test
    void surveyIsAValueRecord() {
        Set<String> arrays = new LinkedHashSet<>(List.of("MS:1000516", "MS:1003006"));
        MzMLReader.Survey survey = new MzMLReader.Survey(List.of("scan=1"), List.of("uv=1"), arrays, 2);

        assertThat(survey.spectrumIds()).containsExactly("scan=1");
        assertThat(survey.wavelengthSpectrumIds()).containsExactly("uv=1");
        assertThat(survey.spectrumArrays()).containsExactly("MS:1000516", "MS:1003006");
        assertThat(survey.chromatograms()).isEqualTo(2);
        assertThat(survey).isEqualTo(new MzMLReader.Survey(List.of("scan=1"), List.of("uv=1"), arrays, 2));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** tiny after {@code edit}, which must change it, in a new file in {@code dir}. */
    private static Path tinyWith(Path dir, UnaryOperator<String> edit) throws IOException {
        String original = Files.readString(MzMLFixtures.extract(TINY, dir), StandardCharsets.ISO_8859_1);
        String edited = edit.apply(original);
        assertThat(edited).as("the edit changes tiny").isNotEqualTo(original);
        Path out = Files.createTempFile(dir, "tiny-", ".mzML");
        return Files.writeString(out, edited, StandardCharsets.ISO_8859_1);
    }

    /** tiny's {@code <mzML>} element as a plain document, after {@code edit}. */
    private static Path plainTiny(Path dir, UnaryOperator<String> edit) throws IOException {
        String indexed = Files.readString(MzMLFixtures.extract(TINY, dir), StandardCharsets.ISO_8859_1);
        String mzml = indexed.substring(indexed.indexOf("<mzML "), indexed.indexOf("</mzML>") + "</mzML>".length());
        String plain = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" + edit.apply(mzml) + "\n";
        Path out = Files.createTempFile(dir, "plain-", ".mzML");
        return Files.writeString(out, plain, StandardCharsets.ISO_8859_1);
    }

    /** {@code s} with its single occurrence of {@code target} replaced. */
    private static String replaceOnce(String s, String target, String replacement) {
        int at = s.indexOf(target);
        assertThat(at).as("'%s' occurs", target).isNotNegative();
        assertThat(s.indexOf(target, at + 1)).as("'%s' occurs once", target).isNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    /** {@code s} with the first occurrence of {@code target} after {@code anchor} replaced. */
    private static String replaceAfter(String s, String anchor, String target, String replacement) {
        int from = s.indexOf(anchor);
        assertThat(from).as("'%s' occurs", anchor).isNotNegative();
        int at = s.indexOf(target, from);
        assertThat(at).as("'%s' occurs after '%s'", target, anchor).isNotNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    /** The 1-based line of the first occurrence of {@code text} in {@code file}. */
    private static int lineOf(Path file, String text) throws IOException {
        String s = Files.readString(file, StandardCharsets.ISO_8859_1);
        return (int) s.substring(0, s.indexOf(text)).chars().filter(c -> c == '\n').count() + 1;
    }

    /** The 1-based column just after the end of the start tag that begins with {@code tagStart} (first occurrence). */
    private static int endOfTag(Path file, String tagStart) throws IOException {
        String s = Files.readString(file, StandardCharsets.ISO_8859_1);
        int at = s.indexOf(tagStart);
        int end = s.indexOf('>', at) + 1;
        return end - (s.lastIndexOf('\n', at) + 1) + 1;
    }
}
