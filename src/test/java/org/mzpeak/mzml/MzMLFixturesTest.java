package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.CvTerms;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-12: the vendored mzML fixtures and the test oracles every converter unit uses ({@link MzMLFixtures},
 * {@link MzMLSchema}, {@link IndexedMzMLVerifier}, {@link MzMLMappingCheck}, {@link MzMLAssert}). Each oracle is
 * run on the four inputs, which it accepts (small.mzML apart, whose stored checksum is stale), and on mutated copies
 * of tiny, which it must reject with a message naming the line, offset, count or element at fault. The facts of
 * mzML design 12.1 that later units rely on are pinned at the end.
 */
class MzMLFixturesTest {

    private static final String TINY = MzMLFixtures.TINY;
    private static final String CID_PARAM =
            "<cvParam cvRef=\"MS\" accession=\"MS:1000133\" name=\"collision-induced dissociation\" value=\"\"/>";
    private static final String NO_COMBINATION_PARAM =
            "<cvParam cvRef=\"MS\" accession=\"MS:1000795\" name=\"no combination\" value=\"\"/>";

    static Stream<String> fixtures() {
        return MzMLFixtures.NAMES.stream();
    }

    // ---- (a) fixtures --------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void anExtractedFixtureHasItsPinnedSha256(String name, @TempDir Path dir) throws IOException {
        Path file = MzMLFixtures.extract(name, dir);

        assertThat(file).isEqualTo(dir.resolve(name));
        assertThat(MzMLFixtures.sha256(file)).isEqualTo(MzMLFixtures.SHA256.get(name));
    }

    @Test
    void extractReplacesAnEarlierCopy(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(MzMLFixtures.SMALL), "stale");

        Path file = MzMLFixtures.extract(MzMLFixtures.SMALL, dir);

        assertThat(MzMLFixtures.sha256(file)).isEqualTo(MzMLFixtures.SHA256.get(MzMLFixtures.SMALL));
    }

    @Test
    void extractRejectsAnUnknownName(@TempDir Path dir) {
        assertThatThrownBy(() -> MzMLFixtures.extract("small.mzML.gz", dir))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no mzML fixture small.mzML.gz");
    }

    @Test
    void theGzippedFixturesStoreNoNameAndNoTimestamp() throws IOException {
        for (String name : List.of(MzMLFixtures.SMALL, MzMLFixtures.TOFSULFAS, MzMLFixtures.DIAPASEF)) {
            byte[] header;
            try (InputStream in = MzMLFixtures.open(name + ".gz")) {
                header = in.readNBytes(10);
            }
            assertThat(header[3]).as("%s FLG (no FNAME)", name).isEqualTo((byte) 0);
            assertThat(new byte[] {header[4], header[5], header[6], header[7]}).as("%s MTIME", name)
                    .containsOnly(0);
        }
    }

    @Test
    void theSchemasAreTheUnmodifiedPsiCopies() throws IOException {
        assertThat(sha256("xsd/mzML1.1.0.xsd"))
                .isEqualTo("141877fd774653617ad1c067533bc74d51e63fb9f19f78558faff50a3e14a206");
        assertThat(sha256("xsd/mzML1.1.2_idx.xsd"))
                .isEqualTo("5a58505a065209c5b964e185c2b2cb077450cfd4246ce794dec7dfcb058b708f");
    }

    // ---- (b) MzMLSchema ------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureIsSchemaValid(String name, @TempDir Path dir) throws IOException {
        assertThat(MzMLSchema.validate(MzMLFixtures.extract(name, dir))).isEmpty();
    }

    @Test
    void aDuplicatedSpectrumIdIsRejected(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "id=\"scan=21\"", "id=\"scan=20\""));

        assertThat(MzMLSchema.validate(file))
                .isNotEmpty()
                .allMatch(MzMLFixturesTest::namesALine)
                .anyMatch(p -> p.contains("Duplicate key value [scan=20]") && p.contains("KEY_SPECTRUM_ID"));
    }

    @Test
    void aChromatogramIdEqualToASpectrumIdIsRejectedByKeyIdIdx(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<chromatogram index=\"0\" id=\"tic\"",
                "<chromatogram index=\"0\" id=\"scan=19\""));

        assertThat(MzMLSchema.validate(file))
                .singleElement()
                .satisfies(p -> assertThat(p).matches("line \\d+, column \\d+: .*")
                        .contains("Duplicate key value [scan=19]").contains("KEY_ID_IDX"));
    }

    @Test
    void aSoftwareIdEqualToASourceFileIdIsRejected(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<software id=\"pwiz\"", "<software id=\"tiny.wiff\"")
                .replace("softwareRef=\"pwiz\"", "softwareRef=\"tiny.wiff\""));

        assertThat(MzMLSchema.validate(file))
                .isNotEmpty()
                .allMatch(MzMLFixturesTest::namesALine)
                .anyMatch(p -> p.contains("cvc-id.2") && p.contains("'tiny.wiff'"));
    }

    @Test
    void aSourceFileLocationThatIsNoUriIsRejected(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, "<sourceFile id=\"tiny1.yep\"",
                "location=\"file://F:/data/Exp01\"", "location=\"file:///x%zz\""));

        assertThat(MzMLSchema.validate(file))
                .isNotEmpty()
                .allMatch(MzMLFixturesTest::namesALine)
                .anyMatch(p -> p.contains("cvc-datatype-valid.1.2.1") && p.contains("'file:///x%zz'")
                        && p.contains("anyURI"));
    }

    @Test
    void aPlainDocumentIsValidatedAgainstMzML110(@TempDir Path dir) throws IOException, XMLStreamException {
        Path plain = plainTiny(dir, "plain.mzML", s -> s);
        Path duplicate = plainTiny(dir, "duplicate.mzML", s -> replaceOnce(s, "id=\"scan=21\"", "id=\"scan=20\""));

        assertThat(MzMLSchema.rootElement(plain)).isEqualTo(MzMLSchema.PLAIN_ROOT);
        assertThat(MzMLSchema.validate(plain)).isEmpty();
        assertThat(MzMLSchema.validate(duplicate))
                .singleElement()
                .satisfies(p -> assertThat(p).matches("line \\d+, column \\d+: .*").contains("KEY_SPECTRUM_ID"));
    }

    @Test
    void anIndexedRootIsValidatedAgainstTheIndexSchema(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "<indexListOffset>24498</indexListOffset>",
                "<indexListOffset>x</indexListOffset>"));

        // indexListOffset is declared in mzML1.1.2_idx.xsd only (xs:long)
        assertThat(MzMLSchema.validate(file)).isNotEmpty().allMatch(MzMLFixturesTest::namesALine)
                .anyMatch(p -> p.contains("The value 'x' of element 'indexListOffset' is not valid"));
    }

    @Test
    void aDocumentThatIsNotMzMLIsRejected(@TempDir Path dir) throws IOException {
        Path other = Files.writeString(dir.resolve("other.xml"), "<?xml version=\"1.0\"?>\n<other/>\n");
        Path empty = Files.writeString(dir.resolve("empty.xml"), "");

        assertThat(MzMLSchema.validate(other)).containsExactly("root element <other> is neither <indexedmzML> "
                + "nor <mzML>");
        assertThat(MzMLSchema.validate(empty)).singleElement().asString().isNotBlank();
    }

    @Test
    void aTruncatedDocumentIsRejectedWithItsLine(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> s.substring(0, s.indexOf("<chromatogramList")));

        assertThat(MzMLSchema.validate(file)).isNotEmpty().allMatch(MzMLFixturesTest::namesALine);
    }

    // ---- (c) IndexedMzMLVerifier ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {MzMLFixtures.TINY, MzMLFixtures.TOFSULFAS, MzMLFixtures.DIAPASEF})
    void theIndexesOfTinyTofSulfasAndDiaPasefAreConsistent(String name, @TempDir Path dir) throws IOException {
        assertThat(IndexedMzMLVerifier.verify(MzMLFixtures.extract(name, dir))).isEmpty();
    }

    @Test
    void smallReportsExactlyItsStaleChecksum(@TempDir Path dir) throws IOException {
        assertThat(IndexedMzMLVerifier.verify(MzMLFixtures.extract(MzMLFixtures.SMALL, dir)))
                .singleElement().asString()
                .contains("fileChecksum")
                .contains("stored ee65c07e9093c5b127674ea5d0fe147f690411d0")
                .contains("computed 9223a7e633782f88ffa85cef7b2a9ff2011baf20");
    }

    @Test
    void aShiftedOffsetIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "\">6883</offset>", "\">6888</offset>")));

        assertThat(IndexedMzMLVerifier.verify(file)).singleElement().asString()
                .startsWith("spectrum offset 6888 for idRef 'scan=19' does not land on '<spectrum '");
    }

    @Test
    void aWrongIndexListCountIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "<indexList count=\"2\">",
                "<indexList count=\"3\">")));

        assertThat(IndexedMzMLVerifier.verify(file))
                .containsExactly("indexList count=\"3\" but 2 <index> elements");
    }

    @Test
    void anOffsetOnAnotherSpectrumIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "<offset idRef=\"scan=19\">",
                "<offset idRef=\"scan=29\">")));

        assertThat(IndexedMzMLVerifier.verify(file))
                .containsExactly("spectrum offset 6883 for idRef 'scan=29' lands on the spectrum with id 'scan=19'");
    }

    @Test
    void offsetsOutOfDocumentOrderAreReported(@TempDir Path dir) throws IOException {
        String tic = "      <offset idRef=\"tic\">20654</offset>\n";
        String sic = "      <offset idRef=\"sic\">22253</offset>\n";
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, tic + sic, sic + tic)));

        assertThat(IndexedMzMLVerifier.verify(file)).singleElement().asString()
                .startsWith("chromatogram offset 20654 for idRef 'tic' is not after the previous offset 22253");
    }

    @Test
    void offsetsOutsideTheFileOrNotNumbersAreReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(replaceOnce(s, ">20654</offset>",
                ">99999</offset>"), ">22253</offset>", ">2225x</offset>")));

        assertThat(IndexedMzMLVerifier.verify(file)).containsExactly(
                "chromatogram offset 99999 for idRef 'tic' is outside the file (25072 bytes)",
                "chromatogram offset 2225x for idRef 'sic' is not a number");
    }

    @Test
    void aWrongIndexListOffsetIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "<indexListOffset>24498<",
                "<indexListOffset>24497<")));
        Path outside = tinyWith(dir, s -> withChecksum(replaceOnce(s, "<indexListOffset>24498<",
                "<indexListOffset>94498<")));

        assertThat(IndexedMzMLVerifier.verify(file)).singleElement().asString()
                .startsWith("indexListOffset 24497 does not land on '<indexList' but on ' <indexList count=");
        assertThat(IndexedMzMLVerifier.verify(outside))
                .containsExactly("indexListOffset 94498 is outside the file (25072 bytes)");
    }

    @Test
    void anEmptyIndexNextToAnotherIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "      <offset idRef=\"tic\">20654</offset>\n"
                + "      <offset idRef=\"sic\">22253</offset>\n", "")));

        assertThat(IndexedMzMLVerifier.verify(file)).containsExactly(
                "<index name=\"chromatogram\"> is empty but not the only <index>",
                "chromatogram index has 0 offsets for 2 <chromatogram> elements");
    }

    @Test
    void indexAttributesThatDoNotRunFromZeroAreReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "<spectrum index=\"2\"", "<spectrum index=\"7\"")));

        assertThat(IndexedMzMLVerifier.verify(file))
                .containsExactly("<spectrum> 'scan=21' at position 2 has index=\"7\"; index must run 0..3");
    }

    @Test
    void aWrongListCountIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> withChecksum(replaceOnce(s, "<spectrumList count=\"4\"",
                "<spectrumList count=\"5\"")));

        assertThat(IndexedMzMLVerifier.verify(file))
                .containsExactly("spectrumList count=\"5\" but 4 <spectrum> elements");
    }

    @Test
    void aChangedByteIsReportedAsAChecksumMismatch(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s, "spectrum with no data", "spectrum with no Data"));

        assertThat(IndexedMzMLVerifier.verify(file)).singleElement().asString()
                .startsWith("fileChecksum: stored 8a908dc1c5c31c43adca79dbe1a5b72e76686cb4, computed ");
    }

    @Test
    void aPlainDocumentHasNoIndexToVerify(@TempDir Path dir) throws IOException {
        assertThat(IndexedMzMLVerifier.verify(plainTiny(dir, "plain.mzML", s -> s)))
                .containsExactly("root element <mzML> is not <indexedmzML>");
    }

    @Test
    void anIdIsComparedAsXmlReadsIt() {
        assertThat(IndexedMzMLVerifier.attribute("<spectrum index=\"0\" id='a&amp;b&#x20;&#62;&lt;&gt;&quot;&apos;\tc'",
                "id")).isEqualTo("a&b ><>\"' c");
        assertThat(IndexedMzMLVerifier.attribute("<spectrum index=\"0\">", "id")).isNull();
    }

    // ---- (d) MzMLMappingCheck ------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyFixtureSatisfiesTheSixMappingRules(String name, @TempDir Path dir) throws IOException {
        assertThat(MzMLMappingCheck.violations(MzMLFixtures.extract(name, dir))).isEmpty();
    }

    @Test
    void anActivationWithoutDissociationMethodIsReported(@TempDir Path dir) throws IOException {
        // the CID term of chromatogram sic's precursor stays: the rule does not cover chromatogram precursors
        Path file = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=20\"", CID_PARAM, ""));

        assertThat(MzMLMappingCheck.violations(file)).containsExactly("precursor_activation_must @ scan=20");
    }

    @Test
    void aScanListWithoutCombinationIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=21\"", NO_COMBINATION_PARAM, ""));

        assertThat(MzMLMappingCheck.violations(file)).containsExactly("scan_must @ scan=21");
    }

    @Test
    void anObsoleteSpectrumTypeIsReported(@TempDir Path dir) throws IOException {
        String obsolete = "<cvParam cvRef=\"MS\" accession=\"MS:1000343\" name=\"product ion spectrum\" value=\"\"/>";
        // scan=19 has its type MS:1000579 from CommonMS1SpectrumParams, which three spectra share: replace its ref
        Path ms1 = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=19\"",
                "<referenceableParamGroupRef ref=\"CommonMS1SpectrumParams\"/>",
                obsolete + "<cvParam cvRef=\"MS\" accession=\"MS:1000130\" name=\"positive scan\" value=\"\"/>"));
        // MS:1000580 is scan=20's type, from CommonMS2SpectrumParams, which only scan=20 uses
        Path ms2 = tinyWith(dir, s -> replaceAfter(s, "id=\"CommonMS2SpectrumParams\"",
                "<cvParam cvRef=\"MS\" accession=\"MS:1000580\" name=\"MSn spectrum\" value=\"\"/>", obsolete));

        assertThat(MzMLMappingCheck.violations(ms1)).containsExactly("spectrum_must @ scan=19");
        assertThat(MzMLMappingCheck.violations(ms2)).containsExactly("spectrum_must @ scan=20");
    }

    @Test
    void aSecondOrMissingRepresentationIsReported(@TempDir Path dir) throws IOException {
        String centroid = "<cvParam cvRef=\"MS\" accession=\"MS:1000127\" name=\"centroid spectrum\" value=\"\"/>";
        Path both = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=20\"",
                "<cvParam cvRef=\"MS\" accession=\"MS:1000128\" name=\"profile spectrum\" value=\"\"/>",
                "<cvParam cvRef=\"MS\" accession=\"MS:1000128\" name=\"profile spectrum\" value=\"\"/>" + centroid));
        Path none = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=21\"", centroid, ""));

        assertThat(MzMLMappingCheck.violations(both)).containsExactly("spectrum_must @ scan=20");
        assertThat(MzMLMappingCheck.violations(none)).containsExactly("spectrum_must @ scan=21");
    }

    @Test
    void aBinaryDataArrayWithoutOrWithTwoTermsOfAKindIsReported(@TempDir Path dir) throws IOException {
        String noCompression = "<cvParam cvRef=\"MS\" accession=\"MS:1000576\" name=\"no compression\" value=\"\"/>";
        String zlib = "<cvParam cvRef=\"MS\" accession=\"MS:1000574\" name=\"zlib compression\" value=\"\"/>";
        Path missing = tinyWith(dir, s -> replaceAfter(s, "id=\"sic\"", noCompression, ""));
        Path twice = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=20\"", noCompression, noCompression + zlib));
        Path noType = tinyWith(dir, s -> replaceAfter(s, "id=\"tic\"", "accession=\"MS:1000595\"",
                "accession=\"MS:1000596\""));
        Path noDataType = tinyWith(dir, s -> replaceAfter(s, "id=\"scan=19\"",
                "<cvParam cvRef=\"MS\" accession=\"MS:1000523\" name=\"64-bit float\" value=\"\"/>", ""));

        assertThat(MzMLMappingCheck.violations(missing)).containsExactly("binarydataarray_must @ sic");
        assertThat(MzMLMappingCheck.violations(twice)).containsExactly("binarydataarray_must @ scan=20");
        assertThat(MzMLMappingCheck.violations(noType)).containsExactly("binarydataarray_must @ tic");
        assertThat(MzMLMappingCheck.violations(noDataType)).containsExactly("binarydataarray_must @ scan=19");
    }

    @Test
    void anInstrumentConfigurationWithoutCurrentModelIsReported(@TempDir Path dir) throws IOException {
        Path obsolete = tinyWith(dir, s -> replaceOnce(s, "accession=\"MS:1000554\" name=\"LCQ Deca\"",
                "accession=\"MS:1000870\" name=\"4000 Q TRAP\""));
        Path parent = tinyWith(dir, s -> replaceOnce(s, "accession=\"MS:1000554\" name=\"LCQ Deca\"",
                "accession=\"MS:1000031\" name=\"instrument model\""));

        assertThat(MzMLMappingCheck.violations(obsolete)).containsExactly("instrumentconfiguration_must @ "
                + "LCQ_x0020_Deca");
        assertThat(MzMLMappingCheck.violations(parent)).containsExactly("instrumentconfiguration_must @ "
                + "LCQ_x0020_Deca");
    }

    @Test
    void anInstrumentModelFromAParamGroupCounts(@TempDir Path dir) throws IOException {
        String model = "<cvParam cvRef=\"MS\" accession=\"MS:1000554\" name=\"LCQ Deca\" value=\"\"/>";
        Path viaGroup = tinyWith(dir, s -> replaceOnce(replaceOnce(s, model,
                "<referenceableParamGroupRef ref=\"Model\"/>"), "<referenceableParamGroupList count=\"2\">",
                "<referenceableParamGroupList count=\"3\"><referenceableParamGroup id=\"Model\">" + model
                        + "</referenceableParamGroup>"));

        assertThat(MzMLSchema.validate(viaGroup)).isEmpty();
        assertThat(MzMLMappingCheck.violations(viaGroup)).isEmpty();
    }

    @Test
    void aChromatogramWithoutCurrentTypeIsReported(@TempDir Path dir) throws IOException {
        Path file = tinyWith(dir, s -> replaceOnce(s,
                "accession=\"MS:1000235\" name=\"total ion current chromatogram\"",
                "accession=\"MS:1001474\" name=\"SRM chromatogram\""));

        assertThat(MzMLMappingCheck.violations(file)).containsExactly("chromatogram_must @ tic");
    }

    @Test
    void anUnreadableDocumentIsAnIOException(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("broken.mzML"), "<mzML><run></mzML>");

        assertThatThrownBy(() -> MzMLMappingCheck.violations(file)).isInstanceOf(IOException.class)
                .hasMessageContaining("broken.mzML");
    }

    @Test
    void theInstrumentModelListHoldsTheCurrentDescendantsOfMs1000031() throws IOException {
        List<String> lines;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                MzMLFixtures.open("ms-instrument-models.txt"), StandardCharsets.US_ASCII))) {
            lines = in.lines().toList();
        }
        List<String> header = lines.stream().takeWhile(l -> l.startsWith("#")).toList();
        List<String> accessions = lines.subList(header.size(), lines.size());

        assertThat(String.join("\n", header)).contains("non-obsolete transitive is_a descendants of MS:1000031")
                .contains("4.2.11").contains("CC BY 4.0");
        assertThat(accessions).hasSize(501).isSorted().doesNotHaveDuplicates()
                .allMatch(a -> a.matches("MS:\\d{7}"))
                .contains("MS:1000448", "MS:1000554", "MS:1000490") // LTQ FT (small), LCQ Deca (tiny), Agilent
                .doesNotContain("MS:1000031", "MS:1000870"); // the parent; the one obsolete descendant
        // MS:1003123 'Bruker Daltonics timsTOF series', diaPASEF's model, is present
        assertThat(accessions).contains("MS:1003123");
        assertThat(MzMLMappingCheck.instrumentModels()).containsExactlyElementsOf(accessions);
    }

    @Test
    void theTermListsHaveTheCountsOfDesign12_2() {
        assertThat(List.copyOf(MzMLMappingCheck.SPECTRUM_TYPES)).hasSize(16).isSorted()
                .doesNotContain("MS:1000343", "MS:1000620");
        assertThat(MzMLMappingCheck.REPRESENTATIONS).containsExactly("MS:1000127", "MS:1000128");
        assertThat(MzMLMappingCheck.COMBINATIONS).containsExactly("MS:1000571", "MS:1000573", "MS:1000575",
                "MS:1000795");
        assertThat(List.copyOf(MzMLMappingCheck.ARRAY_TYPES)).hasSize(31).isSorted();
        assertThat(List.copyOf(MzMLMappingCheck.DATA_TYPES)).hasSize(5).isSorted().doesNotContain("MS:1000520");
        assertThat(List.copyOf(MzMLMappingCheck.COMPRESSIONS)).hasSize(18).isSorted();
        assertThat(List.copyOf(MzMLMappingCheck.CHROMATOGRAM_TYPES)).hasSize(14).isSorted().contains("MS:4000104")
                .doesNotContain("MS:1001474");
        assertThat(CvTerms.DISSOCIATION_METHODS).hasSize(22);
    }

    /**
     * Regenerates every term list of {@link MzMLMappingCheck} and {@code ms-instrument-models.txt} from the psi-ms
     * OBO file that {@code -Dmzpeak.psims.obo} names (plain or gzip, such as mzdata v0.68.0 cv/psi-ms.obo.gz) with
     * the rule of design 12.2, and compares them with the checked-in lists, order included.
     */
    @Test
    @EnabledIfSystemProperty(named = "mzpeak.psims.obo", matches = ".+")
    void theTermListsRegenerateFromThePsiMsObo() throws IOException {
        Obo obo = Obo.read(Path.of(System.getProperty("mzpeak.psims.obo")));

        assertThat(obo.dataVersion).isEqualTo("4.2.11");
        assertThat(obo.current("MS:1000559")).containsExactlyElementsOf(MzMLMappingCheck.SPECTRUM_TYPES);
        assertThat(obo.obsolete("MS:1000559")).containsExactly("MS:1000343", "MS:1000620");
        assertThat(obo.current("MS:1000525")).containsExactlyElementsOf(MzMLMappingCheck.REPRESENTATIONS);
        assertThat(obo.current("MS:1000570")).containsExactlyElementsOf(MzMLMappingCheck.COMBINATIONS);
        assertThat(obo.current("MS:1000044")).containsExactlyElementsOf(CvTerms.DISSOCIATION_METHODS.keySet());
        assertThat(obo.current("MS:1000513")).containsExactlyElementsOf(MzMLMappingCheck.ARRAY_TYPES);
        assertThat(obo.current("MS:1000518")).containsExactlyElementsOf(MzMLMappingCheck.DATA_TYPES);
        assertThat(obo.current("MS:1000572")).containsExactlyElementsOf(MzMLMappingCheck.COMPRESSIONS);
        assertThat(obo.current("MS:1000626")).containsExactlyElementsOf(MzMLMappingCheck.CHROMATOGRAM_TYPES);
        assertThat(obo.obsolete("MS:1000626")).containsExactly("MS:1001474");
        assertThat(obo.current("MS:1000031")).containsExactlyElementsOf(MzMLMappingCheck.instrumentModels());
        assertThat(obo.obsolete("MS:1000031")).containsExactly("MS:1000870");
    }

    // ---- MzMLAssert ----------------------------------------------------------------------------------------------

    @Test
    void mzMLAssertAcceptsValidIndexedAndPlainDocuments(@TempDir Path dir) throws IOException {
        Path tiny = MzMLFixtures.extract(TINY, dir);
        Path plain = plainTiny(dir, "plain.mzML", s -> s);

        assertThatCode(() -> MzMLAssert.valid(tiny)).doesNotThrowAnyException();
        assertThatCode(() -> MzMLAssert.valid(plain)).doesNotThrowAnyException();
    }

    @Test
    void mzMLAssertFailsWithEverySchemaAndIndexProblem(@TempDir Path dir) throws IOException {
        Path small = MzMLFixtures.extract(MzMLFixtures.SMALL, dir);
        Path duplicate = tinyWith(dir, s -> replaceOnce(s, "id=\"scan=21\"", "id=\"scan=20\""));
        Path plainDuplicate = plainTiny(dir, "plain.mzML", s -> replaceOnce(s, "id=\"scan=21\"", "id=\"scan=20\""));

        assertThatThrownBy(() -> MzMLAssert.valid(small)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 problem(s)")
                .hasMessageContaining("index: fileChecksum: stored ee65c07e9093c5b127674ea5d0fe147f690411d0");
        assertThatThrownBy(() -> MzMLAssert.valid(duplicate)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("schema: line 208, column 65: cvc-identity-constraint.4.2.2: Duplicate key "
                        + "value [scan=20]")
                .hasMessageContaining("index: spectrum offset 15411 for idRef 'scan=21' lands on the spectrum with "
                        + "id 'scan=20'")
                .hasMessageContaining("index: fileChecksum: stored ");
        assertThatThrownBy(() -> MzMLAssert.valid(plainDuplicate)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 problem(s)")
                .hasMessageContaining("schema: line ")
                .hasMessageNotContaining("index:");
    }

    @Test
    void mzMLAssertReportsADocumentThatIsNotWellFormed(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("broken.mzML"), "not xml");

        assertThatThrownBy(() -> MzMLAssert.valid(file)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("1 problem(s)").hasMessageContaining("schema: ");
    }

    // ---- (e) facts of design 12.1 --------------------------------------------------------------------------------

    @Test
    void tinyHasTwelveBinaryDataArraysAndFourScanListsWithoutCombination(@TempDir Path dir) throws IOException {
        Facts tiny = Facts.of(MzMLFixtures.extract(TINY, dir));

        assertThat(tiny.spectra).isEqualTo(4);
        assertThat(tiny.spectrumArrays).isEqualTo(8);
        assertThat(tiny.chromatogramArrays).isEqualTo(4);
        assertThat(tiny.scanLists).hasSize(4).allMatch(params -> params.contains("MS:1000795"));
    }

    @Test
    void smallHas48SpectraEachWithNoCombination(@TempDir Path dir) throws IOException {
        Facts small = Facts.of(MzMLFixtures.extract(MzMLFixtures.SMALL, dir));

        assertThat(small.spectra).isEqualTo(48);
        assertThat(small.occurrences.get("MS:1000795")).isEqualTo(48);
        assertThat(small.scanLists).hasSize(48).allMatch(params -> params.contains("MS:1000795"));
    }

    @Test
    void tofSulfasHas732SpectraAndNoMs1003812(@TempDir Path dir) throws IOException {
        Facts tof = Facts.of(MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, dir));

        assertThat(tof.spectra).isEqualTo(732);
        assertThat(tof.occurrences).doesNotContainKey("MS:1003812");
    }

    @Test
    void diaPasefHas9SummedSpectraWithTwoOfTheFiveSummaryParams(@TempDir Path dir) throws IOException {
        Facts dia = Facts.of(MzMLFixtures.extract(MzMLFixtures.DIAPASEF, dir));

        assertThat(dia.spectra).isEqualTo(9);
        assertThat(dia.scanLists).hasSize(9).allMatch(params -> params.contains("MS:1000571"));
        assertThat(dia.spectrumParams).hasSize(9).allSatisfy(params -> assertThat(params)
                .contains("MS:1000505", "MS:1000285")
                .doesNotContain("MS:1000528", "MS:1000527", "MS:1000504"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** Counts of one document: spectra, binary data arrays, cvParams of scan lists and spectra, all accessions. */
    private static final class Facts {
        int spectra;
        int spectrumArrays;
        int chromatogramArrays;
        final List<Set<String>> scanLists = new ArrayList<>();
        final List<Set<String>> spectrumParams = new ArrayList<>();
        final Map<String, Integer> occurrences = new HashMap<>();

        static Facts of(Path file) throws IOException {
            Facts facts = new Facts();
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            try (InputStream in = Files.newInputStream(file)) {
                XMLStreamReader r = factory.createXMLStreamReader(in);
                Deque<String> path = new ArrayDeque<>();
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.END_ELEMENT) {
                        path.pop();
                    } else if (event == XMLStreamConstants.START_ELEMENT) {
                        facts.start(r, path);
                        path.push(r.getLocalName());
                    }
                }
                r.close();
            } catch (XMLStreamException e) {
                throw new IOException(e);
            }
            return facts;
        }

        private void start(XMLStreamReader r, Deque<String> path) {
            String parent = path.peek();
            switch (r.getLocalName()) {
                case "spectrum" -> {
                    if ("spectrumList".equals(parent)) {
                        spectra++;
                        spectrumParams.add(new HashSet<>());
                    }
                }
                case "scanList" -> scanLists.add(new HashSet<>());
                case "binaryDataArray" -> {
                    String owner = path.stream().skip(1).findFirst().orElse("");
                    if (owner.equals("spectrum")) {
                        spectrumArrays++;
                    } else if (owner.equals("chromatogram")) {
                        chromatogramArrays++;
                    }
                }
                case "cvParam" -> {
                    String accession = r.getAttributeValue(null, "accession");
                    occurrences.merge(accession, 1, Integer::sum);
                    if ("spectrum".equals(parent)) {
                        spectrumParams.get(spectrumParams.size() - 1).add(accession);
                    } else if ("scanList".equals(parent)) {
                        scanLists.get(scanLists.size() - 1).add(accession);
                    }
                }
                default -> {
                }
            }
        }
    }

    /** The is_a graph of the [Term] stanzas of an OBO file, obsolete terms included and marked. */
    private static final class Obo {
        String dataVersion;
        final Map<String, List<String>> children = new HashMap<>();
        final Set<String> obsoleteTerms = new HashSet<>();

        static Obo read(Path path) throws IOException {
            Obo obo = new Obo();
            InputStream in = Files.newInputStream(path);
            if (path.getFileName().toString().endsWith(".gz")) {
                in = new GZIPInputStream(in);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                boolean term = false;
                String id = null;
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    if (line.startsWith("[")) {
                        term = line.trim().equals("[Term]");
                        id = null;
                    } else if (line.startsWith("data-version:") && obo.dataVersion == null) {
                        obo.dataVersion = line.substring("data-version:".length()).trim();
                    } else if (term && line.startsWith("id:")) {
                        id = line.substring(3).trim();
                    } else if (term && id != null && line.startsWith("is_a:")) {
                        String parent = line.substring(5).trim().split("[\\s!{]", 2)[0];
                        obo.children.computeIfAbsent(parent, k -> new ArrayList<>()).add(id);
                    } else if (term && id != null && line.trim().equals("is_obsolete: true")) {
                        obo.obsoleteTerms.add(id);
                    }
                }
            }
            return obo;
        }

        /** The non-obsolete transitive is_a descendants of {@code root}, without it, in accession order. */
        Set<String> current(String root) {
            Set<String> out = descendants(root);
            out.removeAll(obsoleteTerms);
            return out;
        }

        /** The obsolete transitive is_a descendants of {@code root}, in accession order. */
        Set<String> obsolete(String root) {
            Set<String> out = descendants(root);
            out.retainAll(obsoleteTerms);
            return out;
        }

        private Set<String> descendants(String root) {
            Set<String> out = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(root));
            while (!todo.isEmpty()) {
                for (String child : children.getOrDefault(todo.pop(), List.of())) {
                    if (out.add(child)) {
                        todo.push(child);
                    }
                }
            }
            return out;
        }
    }

    /** tiny.pwiz.1.1.mzML (ISO-8859-1) after {@code edit}, written to {@code dir}; the edit must change it. */
    private static Path tinyWith(Path dir, UnaryOperator<String> edit) throws IOException {
        String original = Files.readString(MzMLFixtures.extract(TINY, dir), StandardCharsets.ISO_8859_1);
        String edited = edit.apply(original);
        assertThat(edited).as("the mutation changes tiny").isNotEqualTo(original);
        Path out = Files.createTempFile(dir, "tiny-", ".mzML");
        Files.writeString(out, edited, StandardCharsets.ISO_8859_1);
        return out;
    }

    /** tiny's {@code <mzML>} element as a plain document, after {@code edit}. */
    private static Path plainTiny(Path dir, String name, UnaryOperator<String> edit) throws IOException {
        String indexed = Files.readString(MzMLFixtures.extract(TINY, dir), StandardCharsets.ISO_8859_1);
        String mzml = indexed.substring(indexed.indexOf("<mzML "), indexed.indexOf("</mzML>") + "</mzML>".length());
        String plain = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n" + edit.apply(mzml) + "\n";
        return Files.writeString(dir.resolve(name), plain, StandardCharsets.ISO_8859_1);
    }

    /** {@code s} with its single occurrence of {@code target} replaced. */
    private static String replaceOnce(String s, String target, String replacement) {
        int at = s.indexOf(target);
        assertThat(at).as("'%s' occurs in tiny", target).isNotNegative();
        assertThat(s.indexOf(target, at + 1)).as("'%s' occurs once in tiny", target).isNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    /** {@code s} with the first occurrence of {@code target} after {@code anchor} replaced. */
    private static String replaceAfter(String s, String anchor, String target, String replacement) {
        int from = s.indexOf(anchor);
        assertThat(from).as("'%s' occurs in tiny", anchor).isNotNegative();
        int at = s.indexOf(target, from);
        assertThat(at).as("'%s' occurs after '%s'", target, anchor).isNotNegative();
        return s.substring(0, at) + replacement + s.substring(at + target.length());
    }

    /** {@code doc} with its fileChecksum recomputed, so that a mutation leaves only the problem it is about. */
    private static String withChecksum(String doc) {
        String tag = "<fileChecksum>";
        int end = doc.lastIndexOf(tag) + tag.length();
        String sha1 = HexFormat.of().formatHex(digest("SHA-1", doc.substring(0, end)
                .getBytes(StandardCharsets.ISO_8859_1)));
        return doc.substring(0, end) + sha1 + doc.substring(end + sha1.length());
    }

    private static String sha256(String resource) throws IOException {
        try (InputStream in = MzMLFixtures.open(resource)) {
            return HexFormat.of().formatHex(digest("SHA-256", in.readAllBytes()));
        }
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean namesALine(String problem) {
        return problem.matches("line \\d+, column \\d+: [\\s\\S]*");
    }
}
