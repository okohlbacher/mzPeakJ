package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Contact;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-7: the contacts of the file description ({@link Contact}, {@code file_description.contacts}) and the source file
 * references and targets of scan settings ({@code scan_settings_list[].source_file_refs} and {@code targets}) are read
 * and written in both metadata layouts, kept for scan settings the split layout gives an id, and scan-settings params
 * and targets keep their JSON type. File-level params are JSON, so a non-finite {@code Double} comes back as a
 * {@code String} (design §5.3, B-35).
 *
 * <p>Oracle: the contact and scan settings of tiny.pwiz.1.1.mzML (pwiz), written with the spectra and file metadata
 * of legacy small.unpacked, and the vendored fixtures.
 */
class FileMetadataContactsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "mzpeak_index.json";
    private static final Source SMALL = ForwardBackwardCorpusTest.readSource(Fixtures.legacy("small.unpacked.mzpeak"));

    /** tiny.pwiz.1.1.mzML's contact: MS:1000586 gives the name, MS:1000590 the affiliation. */
    private static final Contact TINY_CONTACT = new Contact("William Pennington", "Higglesworth University", List.of(
            new Param("contact address", "MS:1000587", "12 Higglesworth Avenue, 12045, HI, USA", null),
            new Param("contact URL", "MS:1000588", "http://www.higglesworth.edu/", null),
            new Param("contact email", "MS:1000589", "wpennington@higglesworth.edu", null)));

    /** tiny.pwiz.1.1.mzML's scan settings: one source file reference and two targets. */
    private static final ScanSettings TINY_SETTINGS = new ScanSettings("tiny_x0020_scan_x0020_settings", List.of(),
            List.of("sf_parameters"), List.of(
                    List.of(new Param("selected ion m/z", "MS:1000744", 1000L, "MS:1000040")),
                    List.of(new Param("selected ion m/z", "MS:1000744", 1200L, "MS:1000040"))));

    static Stream<Arguments> writes() {
        return Stream.of(MetadataLayout.SPLIT, MetadataLayout.PACKED).flatMap(layout -> Stream.of(
                Arguments.of(layout, false), Arguments.of(layout, true)));
    }

    // ---- (a) contacts, refs and targets round trip ---------------------------------------------------

    /**
     * (a) The tiny contact and scan settings, written in either layout, read back equal, from the index and, with the
     * index's file description removed, from the footer. The index holds the reference's keys.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void tinyContactAndScanSettingsReadBackEqual(MetadataLayout layout, @TempDir Path tmp) throws IOException {
        FileMetadata meta = withFileDescription(new FileDescription(SMALL.fileMetadata.fileDescription().contents(),
                SMALL.fileMetadata.fileDescription().sourceFiles(), List.of(TINY_CONTACT)));
        Path out = write(tmp.resolve("tiny.mzpeak"), List.of(TINY_SETTINGS), meta, layout, false);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.fileMetadata()).isEqualTo(meta);
            assertThat(reader.fileMetadata().fileDescription().contacts()).containsExactly(TINY_CONTACT);
            assertThat(reader.scanSettings()).containsExactly(TINY_SETTINGS);
        }

        JsonNode metadata = MAPPER.readTree(out.resolve(INDEX).toFile()).path("metadata");
        JsonNode contact = metadata.path("file_description").path("contacts").path(0);
        assertThat(contact.path("contact_name").textValue()).isEqualTo("William Pennington");
        assertThat(contact.path("contact_affiliation").textValue()).isEqualTo("Higglesworth University");
        assertThat(contact.path("parameters").findValuesAsText("accession"))
                .containsExactly("MS:1000587", "MS:1000588", "MS:1000589");
        JsonNode settings = metadata.path("scan_settings_list").path(0);
        assertThat(settings.path("source_file_refs").toString()).isEqualTo("[\"sf_parameters\"]");
        assertThat(settings.path("targets")).hasSize(2);
        assertThat(settings.path("targets").path(1).path(0).path("value").isIntegralNumber()).isTrue();
        assertThat(settings.path("targets").path(1).path(0).path("value").longValue()).isEqualTo(1200L);

        ObjectNode index = (ObjectNode) MAPPER.readTree(out.resolve(INDEX).toFile());
        ((ObjectNode) index.path("metadata")).remove("file_description");
        MAPPER.writeValue(out.resolve(INDEX).toFile(), index);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.fileMetadata().fileDescription()).isEqualTo(meta.fileDescription());
        }
    }

    /**
     * (a) A contact without name or affiliation is written without those keys and reads back with them {@code null};
     * the footer's contacts are read like the index's.
     */
    @Test
    void aContactWithoutNameOrAffiliationLeavesTheKeysOut() throws IOException {
        Contact anonymous = new Contact(null, null, null);
        FileMetadata meta = withFileDescription(new FileDescription(List.of(), List.of(), List.of(anonymous,
                TINY_CONTACT)));
        Map<String, String> footer = FooterMetadataWriter.serialize(meta);
        JsonNode contacts = MAPPER.readTree(footer.get("file_description")).path("contacts");
        assertThat(contacts.path(0).toString()).isEqualTo("{\"parameters\":[]}");
        assertThat(FooterMetadataReader.parse(footer).fileDescription().contacts())
                .containsExactly(new Contact(null, null, List.of()), TINY_CONTACT);
    }

    /** (a) A file description without contacts has no {@code contacts} key, as the reference writes it. */
    @Test
    void aFileDescriptionWithoutContactsHasNoContactsKey() throws IOException {
        Map<String, String> footer = FooterMetadataWriter.serialize(SMALL.fileMetadata);
        assertThat(MAPPER.readTree(footer.get("file_description")).has("contacts")).isFalse();
        assertThat(FooterMetadataReader.parse(footer).fileDescription().contacts()).isEmpty();
    }

    // ---- (b) WritePlanner keeps refs and targets ------------------------------------------------------

    /**
     * (b) A scan setting without an id, written in the split layout, gets {@code scan_settings_0} and keeps its
     * source file references and target; the packed layout writes it without an id and with both.
     */
    @Test
    void anIdlessScanSettingKeepsItsRefsAndTargets(@TempDir Path tmp) throws IOException {
        List<Param> target = List.of(new Param("selected ion m/z", "MS:1000744", 445.3, "MS:1000040"));
        ScanSettings idless = new ScanSettings(null, List.of(new Param("top down", "IMS:1000401", null, null)),
                List.of("sf1"), List.of(target));

        Path split = write(tmp.resolve("split.mzpeak"), List.of(idless), SMALL.fileMetadata, MetadataLayout.SPLIT,
                false);
        try (MzPeakReader reader = MzPeakReader.open(split)) {
            assertThat(reader.scanSettings()).containsExactly(new ScanSettings("scan_settings_0",
                    idless.parameters(), List.of("sf1"), List.of(target)));
        }

        Path packed = write(tmp.resolve("packed.mzpeak"), List.of(idless), SMALL.fileMetadata,
                MetadataLayout.PACKED, false);
        JsonNode entry = MAPPER.readTree(packed.resolve(INDEX).toFile()).path("metadata").path("scan_settings_list")
                .path(0);
        assertThat(entry.toString()).isEqualTo("{\"source_file_refs\":[\"sf1\"],\"targets\":[[{\"name\":"
                + "\"selected ion m/z\",\"accession\":\"MS:1000744\",\"value\":445.3,"
                + "\"unit_accession\":\"MS:1000040\"}]],"
                + "\"parameters\":[{\"name\":\"top down\",\"accession\":\"IMS:1000401\"}]}");
        try (MzPeakReader reader = MzPeakReader.open(packed)) {
            assertThat(reader.scanSettings()).containsExactly(idless);
        }
    }

    /** (b) The packed layout leaves empty source file references and targets out; the split layout writes them. */
    @Test
    void emptyRefsAndTargetsAreLeftOutOnlyInThePackedLayout(@TempDir Path tmp) throws IOException {
        ScanSettings plain = new ScanSettings("plain", List.of());
        Path packed = write(tmp.resolve("packed.mzpeak"), List.of(plain), SMALL.fileMetadata, MetadataLayout.PACKED,
                false);
        assertThat(MAPPER.readTree(packed.resolve(INDEX).toFile()).path("metadata").path("scan_settings_list")
                .toString()).isEqualTo("[{\"id\":\"plain\",\"parameters\":[]}]");
        Path split = write(tmp.resolve("split.mzpeak"), List.of(plain), SMALL.fileMetadata, MetadataLayout.SPLIT,
                false);
        assertThat(MAPPER.readTree(split.resolve(INDEX).toFile()).path("metadata").path("scan_settings_list")
                .toString()).isEqualTo("[{\"id\":\"plain\",\"source_file_refs\":[],\"targets\":[],\"parameters\":[]}]");
    }

    // ---- (c) the fixtures ----------------------------------------------------------------------------

    static Stream<Arguments> allFixtures() {
        return IndexMetadataTest.allFixtures();
    }

    /** (c) No fixture has contacts, source file references or targets. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("allFixtures")
    void theFixturesHaveNoContactsRefsOrTargets(String set, String fixture, Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            FileDescription description = reader.fileMetadata().fileDescription();
            assertThat(description).isNotNull();
            assertThat(description.contacts()).isEmpty();
            for (ScanSettings settings : reader.scanSettings()) {
                assertThat(settings.sourceFileRefs()).isEmpty();
                assertThat(settings.targets()).isEmpty();
            }
        }
    }

    /** (c) Example_Processed.img's footer stores JSON numbers, which keep their type: 3 and 300 Long, 100.0 Double. */
    @Test
    void exampleProcessedScanSettingsKeepTheirNumberTypes() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            List<Param> params = reader.scanSettings().get(0).parameters();
            assertThat(value(params, "IMS:1000042")).isEqualTo(3L);
            assertThat(value(params, "IMS:1000043")).isEqualTo(3L);
            assertThat(value(params, "IMS:1000044")).isEqualTo(300L);
            assertThat(value(params, "IMS:1000045")).isEqualTo(300L);
            assertThat(value(params, "IMS:1000046")).isEqualTo(100.0);
            assertThat(value(params, "IMS:1000047")).isEqualTo(100.0);
            assertThat(value(params, "IMS:1000401")).isNull();
        }
    }

    // ---- (d) the model -------------------------------------------------------------------------------

    /** (d) The constructors that existed before MZ-7 give empty contacts, references and targets. */
    @Test
    void theOldConstructorsGiveEmptyLists() {
        assertThat(new FileDescription(List.of(), List.of()).contacts()).isEmpty();
        ScanSettings settings = new ScanSettings("s", List.of());
        assertThat(settings.sourceFileRefs()).isEmpty();
        assertThat(settings.targets()).isEmpty();
        assertThat(settings).isEqualTo(new ScanSettings("s", List.of(), List.of(), List.of()));
    }

    /** (d) Equality covers the new components. */
    @Test
    void equalityCoversTheNewComponents() {
        Param p = new Param("n", "MS:1", 1L, null);
        assertThat(new ScanSettings("s", List.of(), List.of("sf1"), List.of()))
                .isNotEqualTo(new ScanSettings("s", List.of()))
                .isEqualTo(new ScanSettings("s", List.of(), List.of("sf1"), List.of()));
        assertThat(new ScanSettings("s", List.of(), List.of(), List.of(List.of(p))))
                .isNotEqualTo(new ScanSettings("s", List.of(), List.of(), List.of(List.of())))
                .isEqualTo(new ScanSettings("s", List.of(), List.of(), List.of(List.of(p))));
        assertThat(new FileDescription(List.of(), List.of(), List.of(TINY_CONTACT)))
                .isNotEqualTo(new FileDescription(List.of(), List.of()))
                .isEqualTo(new FileDescription(List.of(), List.of(), List.of(TINY_CONTACT)));
        assertThat(new Contact("a", "b", List.of(p))).isNotEqualTo(new Contact("a", "b", List.of()))
                .isNotEqualTo(new Contact("a", "c", List.of(p))).isNotEqualTo(new Contact("x", "b", List.of(p)))
                .isEqualTo(new Contact("a", "b", List.of(p)));
    }

    /**
     * (d) The new lists are copied, the targets deeply, and cannot be modified; a {@code null} list, also a
     * {@code null} target, becomes an empty one.
     */
    @Test
    void theNewListsAreCopiedAndNullBecomesEmpty() {
        Param p = new Param("n", "MS:1", 1L, null);
        List<String> refs = new ArrayList<>(List.of("sf1"));
        List<Param> target = new ArrayList<>(List.of(p));
        List<List<Param>> targets = new ArrayList<>(Arrays.asList(target, null));
        ScanSettings settings = new ScanSettings("s", null, refs, targets);
        refs.add("sf2");
        target.add(p);
        targets.add(List.of());
        assertThat(settings.parameters()).isEmpty();
        assertThat(settings.sourceFileRefs()).containsExactly("sf1");
        assertThat(settings.targets()).containsExactly(List.of(p), List.of());
        assertThatThrownBy(() -> settings.sourceFileRefs().add("x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.targets().add(List.of())).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> settings.targets().get(0).add(p)).isInstanceOf(UnsupportedOperationException.class);

        ScanSettings nulls = new ScanSettings("s", List.of(), null, null);
        assertThat(nulls.sourceFileRefs()).isEmpty();
        assertThat(nulls.targets()).isEmpty();

        List<Contact> contacts = new ArrayList<>(List.of(TINY_CONTACT));
        FileDescription description = new FileDescription(List.of(), List.of(), contacts);
        contacts.clear();
        assertThat(description.contacts()).containsExactly(TINY_CONTACT);
        assertThatThrownBy(() -> description.contacts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new FileDescription(List.of(), List.of(), null).contacts()).isEmpty();

        List<Param> contactParams = new ArrayList<>(List.of(p));
        Contact contact = new Contact("a", "b", contactParams);
        contactParams.clear();
        assertThat(contact.parameters()).containsExactly(p);
        assertThat(new Contact("a", "b", null).parameters()).isEmpty();
    }

    // ---- (e) type-preserving scan-settings params and targets ----------------------------------------

    /**
     * (e) Scan-settings params and targets read back with the type they were written with, in either layout, as a
     * directory and as an archive. Until MZ-7 the text {@code 007} came back as the {@code Long} 7 and the text
     * {@code NaN} as the {@code Double} NaN.
     */
    @ParameterizedTest(name = "{0} archive={1}")
    @MethodSource("writes")
    void scanSettingsParamsAndTargetsKeepTheirType(MetadataLayout layout, boolean archive, @TempDir Path tmp) {
        ScanSettings typed = new ScanSettings("typed", List.of(
                new Param("zero padded", null, "007", null), new Param("nan text", null, "NaN", null),
                new Param("long", null, 7L, null), new Param("double", null, 1.5, null),
                new Param("boolean", null, true, null)),
                List.of(), List.of(List.of(new Param("zero padded", null, "007", null))));
        Path out = write(tmp.resolve("typed.mzpeak"), List.of(typed), SMALL.fileMetadata, layout, archive);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            ScanSettings back = reader.scanSettings().get(0);
            assertThat(back).isEqualTo(typed);
            assertThat(back.parameters()).extracting(p -> (Object) p.value().getClass())
                    .containsExactly(String.class, String.class, Long.class, Double.class, Boolean.class);
            assertThat(back.targets().get(0).get(0).value()).isInstanceOf(String.class).isEqualTo("007");
        }
    }

    /**
     * (e) The footer's scan settings are read the same way: references and targets, values with their JSON type, a
     * target's unit from {@code unit_accession} too. A {@code source_file_refs} or {@code targets} that is not an
     * array reads as empty, a {@code null} reference is skipped, and a target that is not an array is empty.
     */
    @Test
    void footerScanSettingsReadRefsAndTargetsTypePreserving() {
        String json = "[{\"id\": \"a\", \"source_file_refs\": [\"sf1\", null, \"sf2\"], \"targets\": ["
                + "[{\"name\": \"t\", \"accession\": \"MS:1000744\", \"value\": \"007\", \"unit_accession\": "
                + "\"MS:1000040\"}], {}],"
                + " \"parameters\": [{\"name\": \"p\", \"value\": \"NaN\"}]},"
                + " {\"id\": \"b\", \"source_file_refs\": \"sf1\", \"targets\": {}},"
                + " {\"id\": \"c\"}]";
        List<ScanSettings> settings = FooterMetadataReader.scanSettings(null,
                () -> Map.of("scan_settings_list", json));
        assertThat(settings).containsExactly(
                new ScanSettings("a", List.of(new Param("p", null, "NaN", null)), List.of("sf1", "sf2"),
                        List.of(List.of(new Param("t", "MS:1000744", "007", "MS:1000040")), List.of())),
                new ScanSettings("b", List.of()),
                new ScanSettings("c", List.of()));
    }

    // ---- (f) non-finite file-level params ------------------------------------------------------------

    /**
     * (f) Pinned: file-level params are JSON, which has no non-finite numbers, so a file content param of
     * {@code Double} infinity or NaN reads back as the {@code String} {@code Infinity}, {@code -Infinity} or
     * {@code NaN} in either layout (design §5.3, B-35).
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void nonFiniteFileLevelParamsReadBackAsStrings(MetadataLayout layout, @TempDir Path tmp) {
        FileMetadata meta = withFileDescription(new FileDescription(List.of(
                new Param("positive infinity", "MS:1000001", Double.POSITIVE_INFINITY, null),
                new Param("not a number", "MS:1000002", Double.NaN, null),
                new Param("negative infinity", "MS:1000003", Double.NEGATIVE_INFINITY, null)),
                SMALL.fileMetadata.fileDescription().sourceFiles()));
        Path out = write(tmp.resolve("nonfinite.mzpeak"), List.of(), meta, layout, false);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.fileMetadata().fileDescription().contents()).extracting(Param::value)
                    .containsExactly("Infinity", "NaN", "-Infinity");
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------

    /** Writes the first spectrum of small with {@code scanSettings} and {@code meta} into {@code out}. */
    private static Path write(Path out, List<ScanSettings> scanSettings, FileMetadata meta, MetadataLayout layout,
                              boolean archive) {
        Options options = Options.defaults().withMetadataLayout(layout);
        if (archive) {
            MzPeakWriter.writeArchive(out, SMALL.spectra.subList(0, 1), List.of(), List.of(), scanSettings, meta,
                    options);
        } else {
            MzPeakWriter.writeDirectory(out, SMALL.spectra.subList(0, 1), List.of(), List.of(), scanSettings, meta,
                    options);
        }
        return out;
    }

    /** Small's file metadata with {@code description} in place of its file description. */
    private static FileMetadata withFileDescription(FileDescription description) {
        FileMetadata m = SMALL.fileMetadata;
        return new FileMetadata(description, m.instrumentConfigurations(), m.dataProcessingMethods(), m.samples(),
                m.software(), m.run());
    }

    private static Object value(List<Param> params, String accession) {
        return Param.find(params, accession).orElseThrow().value();
    }
}
