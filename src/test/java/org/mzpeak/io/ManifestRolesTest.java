package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Manifest roles (RD-1): {@code entity_type} and {@code data_kind} are parsed as the reference implementation does,
 * so the pin-era spellings ({@code data arrays}, {@code wavelength spectrum}) and the current ones
 * ({@code data_arrays}, {@code wavelength_spectrum}) resolve the same members, and the metadata decoders resolve
 * their own members by role. Expected member names come from the vendored manifests.
 */
class ManifestRolesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "mzpeak_index.json";

    static Stream<String> currentFixtures() {
        return Fixtures.CURRENT_NAMES.stream();
    }

    static Stream<String> legacyFixtures() {
        return Fixtures.LEGACY_NAMES.stream();
    }

    static Stream<Arguments> allFixtures() {
        return Stream.concat(
                Fixtures.CURRENT_NAMES.stream().map(n -> Arguments.of("current", n, Fixtures.current(n))),
                Fixtures.LEGACY_NAMES.stream().map(n -> Arguments.of("legacy", n, Fixtures.legacy(n))));
    }

    // ---- (a) role resolution -------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void spectrumMembersResolveOnEveryCurrentFixture(String fixture) {
        MzPeakManifest m = manifest(Fixtures.current(fixture));

        assertThat(name(m, EntityType.SPECTRUM, DataKind.DATA_ARRAYS)).contains("spectra_data.parquet");
        assertThat(name(m, EntityType.SPECTRUM, DataKind.PEAKS)).contains("spectra_peaks.parquet");
        assertThat(name(m, EntityType.SPECTRUM, DataKind.METADATA)).contains("spectra_metadata.parquet");
        assertThat(name(m, EntityType.SPECTRUM, DataKind.SCANS)).contains("spectra_metadata_scans.parquet");
        assertThat(name(m, EntityType.SPECTRUM, DataKind.PRECURSORS))
                .contains("spectra_metadata_precursors.parquet");
        assertThat(name(m, EntityType.SPECTRUM, DataKind.SELECTED_IONS))
                .contains("spectra_metadata_selected_ions.parquet");
    }

    @Test
    void chromatogramDataResolvesInTheSevenCurrentFixturesThatHaveIt() {
        int withChromatograms = 0;
        for (String fixture : Fixtures.CURRENT_NAMES) {
            MzPeakManifest m = manifest(Fixtures.current(fixture));
            Optional<String> data = name(m, EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS);
            if ("Example_Processed.img.mzpeak".equals(fixture)) {
                assertThat(data).as(fixture).isEmpty();
                assertThat(name(m, EntityType.CHROMATOGRAM, DataKind.METADATA)).as(fixture).isEmpty();
            } else {
                assertThat(data).as(fixture).contains("chromatograms_data.parquet");
                assertThat(name(m, EntityType.CHROMATOGRAM, DataKind.METADATA)).as(fixture)
                        .contains("chromatograms_metadata.parquet");
                withChromatograms++;
            }
        }
        assertThat(withChromatograms).isEqualTo(7);
    }

    @Test
    void hasUvWavelengthMembersResolve() {
        MzPeakManifest current = manifest(Fixtures.current("has_uv.mzpeak"));
        assertThat(name(current, EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA))
                .contains("wavelength_spectra_metadata.parquet");
        assertThat(name(current, EntityType.WAVELENGTH_SPECTRUM, DataKind.SCANS))
                .contains("wavelength_spectra_metadata_scans.parquet");
        assertThat(name(current, EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS))
                .contains("wavelength_spectra_data.parquet");

        MzPeakManifest legacy = manifest(Fixtures.legacy("has_uv.mzpeak"));
        assertThat(name(legacy, EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA))
                .contains("wavelength_spectra_metadata.parquet");
        assertThat(name(legacy, EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS))
                .contains("wavelength_spectra_data.parquet");
        assertThat(name(legacy, EntityType.WAVELENGTH_SPECTRUM, DataKind.SCANS)).isEmpty();
    }

    /** Fails on the base code, whose find(String, String) compared the strings exactly. */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("allFixtures")
    void stringLookupAcceptsBothSpellings(String set, String fixture, Path dataset) {
        MzPeakManifest m = manifest(dataset);

        assertThat(m.find("spectrum", "data arrays")).map(Entry::name).contains("spectra_data.parquet");
        assertThat(m.find("spectrum", "data arrays")).isEqualTo(m.find("spectrum", "data_arrays"))
                .isEqualTo(m.find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS));
        assertThat(m.find("chromatogram", "data arrays")).isEqualTo(m.find("chromatogram", "data_arrays"))
                .isEqualTo(m.find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS));
        assertThat(m.find("wavelength spectrum", "metadata")).isEqualTo(m.find("wavelength_spectrum", "metadata"))
                .isEqualTo(m.find(EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA));
        assertThat(m.find("wavelength spectrum", "data arrays"))
                .isEqualTo(m.find("wavelength_spectrum", "data_arrays"));
    }

    /** Every member of both sets has a known role, and that role resolves it (no fixture lists a role twice). */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("allFixtures")
    void everyMemberResolvesByItsOwnRole(String set, String fixture, Path dataset) {
        MzPeakManifest m = manifest(dataset);

        assertThat(m.files()).isNotEmpty().allSatisfy(e -> {
            assertThat(e.entity()).as(e.name()).isNotEqualTo(EntityType.OTHER);
            assertThat(e.kind()).as(e.name()).isNotEqualTo(DataKind.OTHER);
            assertThat(m.find(e.entity(), e.kind())).as(e.name()).containsSame(e);
            assertThat(m.find(e.entityType(), e.dataKind())).as(e.name()).containsSame(e);
        });
    }

    // ---- (b) the resolved data member decodes --------------------------------------------------

    @Test
    void currentSmallUnpackedSpectrumZeroHasThePyarrowPointCount() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.unpacked.mzpeak"))) {
            MzPeakManifest m = MzPeakManifest.fromSource(source);
            String data = m.find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS).orElseThrow().name();
            try (SpectrumArrayStore store = SpectrumArrayStore.load(source.inputFile(data), false, Map.of())) {
                assertThat(store.get(0).mz()).hasSize(11213);
                assertThat(store.get(0).intensity()).hasSize(11213);
            }
        }
    }

    // ---- (c) raw literals ----------------------------------------------------------------------

    @Test
    void entriesKeepTheirLiteralsAndParseToTheCanonicalRole() {
        Entry legacyData = entry(manifest(Fixtures.legacy("small.unpacked.mzpeak")), "spectra_data.parquet");
        assertThat(legacyData.entityType()).isEqualTo("spectrum");
        assertThat(legacyData.dataKind()).isEqualTo("data arrays");
        assertThat(legacyData.kind()).isEqualTo(DataKind.DATA_ARRAYS);

        Entry legacyUv = entry(manifest(Fixtures.legacy("has_uv.mzpeak")), "wavelength_spectra_data.parquet");
        assertThat(legacyUv.entityType()).isEqualTo("wavelength spectrum");
        assertThat(legacyUv.dataKind()).isEqualTo("data arrays");
        assertThat(legacyUv.entity()).isEqualTo(EntityType.WAVELENGTH_SPECTRUM);
        assertThat(legacyUv.kind()).isEqualTo(DataKind.DATA_ARRAYS);

        Entry currentUv = entry(manifest(Fixtures.current("has_uv.mzpeak")), "wavelength_spectra_data.parquet");
        assertThat(currentUv.entityType()).isEqualTo("wavelength_spectrum");
        assertThat(currentUv.dataKind()).isEqualTo("data_arrays");
        assertThat(currentUv.entity()).isEqualTo(legacyUv.entity());
        assertThat(currentUv.kind()).isEqualTo(legacyUv.kind());
        assertThat(currentUv).isNotEqualTo(new Entry(currentUv.name(), legacyUv.entityType(), legacyUv.dataKind(),
                currentUv.columnMapping(), currentUv.parameters(), currentUv.checksum()));
    }

    /** The current set writes every role in its canonical spelling. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("currentFixtures")
    void currentRolesAreCanonical(String fixture) {
        assertThat(manifest(Fixtures.current(fixture)).files()).allSatisfy(e -> {
            assertThat(e.entity().canonical()).as(e.name()).isEqualTo(e.entityType());
            assertThat(e.kind().canonical()).as(e.name()).isEqualTo(e.dataKind());
        });
    }

    /**
     * A legacy dataset whose manifest is rewritten to the canonical spellings reads exactly like the original. Fails
     * on the base code, which found no data arrays and no wavelength spectra under the current spellings.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFixtures")
    void readerResolvesTheCanonicalSpellingsOnThePackedLayout(String fixture, @TempDir Path tmp) throws IOException {
        Path original = Fixtures.legacy(fixture);
        Path rewritten = unpack(original, tmp);
        editIndex(rewritten, index -> {
            for (JsonNode f : index.path("files")) {
                ObjectNode file = (ObjectNode) f;
                file.put("entity_type", EntityType.parse(file.path("entity_type").asText()).canonical());
                file.put("data_kind", DataKind.parse(file.path("data_kind").asText()).canonical());
            }
        });
        assertThat(Files.readString(rewritten.resolve(INDEX))).contains("\"data_arrays\"")
                .doesNotContain("data arrays").doesNotContain("wavelength spectrum");

        assertReadsAlike(original, rewritten);
    }

    // ---- decoder entry points ------------------------------------------------------------------

    @Test
    void decodersResolveTheirOwnMembers() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy("has_uv.mzpeak"))) {
            MzPeakManifest m = MzPeakManifest.fromSource(source);

            assertThat(SpectrumMetadataDecoder.decode(source, m).descriptions()).hasSize(212);
            assertThat(ChromatogramStore.load(source, m).all()).extracting(Chromatogram::id)
                    .containsExactly("TIC", "DAD1 A: Sig=272,16 Ref=360,100");
            assertThat(WavelengthSpectrumStore.load(source, m).all()).hasSize(520)
                    .extracting(WavelengthSpectrum::size).containsOnly(96);
        }
    }

    @Test
    void storesAreNullAndSpectrumMetadataThrowsWhenAMemberIsMissing(@TempDir Path tmp) throws IOException {
        Path dir = unpack(Fixtures.legacy("small.unpacked.mzpeak"), tmp);
        try (MzPeakSource source = MzPeakSource.open(dir)) {
            MzPeakManifest m = MzPeakManifest.fromSource(source);
            assertThat(ChromatogramStore.load(source, m)).isNotNull();
            assertThat(WavelengthSpectrumStore.load(source, m)).isNull();
        }

        editIndex(dir, index -> removeEntries(index, "chromatograms_data.parquet", "spectra_metadata.parquet"));
        try (MzPeakSource source = MzPeakSource.open(dir)) {
            MzPeakManifest m = MzPeakManifest.fromSource(source);
            assertThat(ChromatogramStore.load(source, m)).isNull();
            assertThatThrownBy(() -> SpectrumMetadataDecoder.decode(source, m))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("Manifest has no spectrum metadata file");
        }
        assertThatThrownBy(() -> MzPeakReader.open(dir))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("Manifest has no spectrum metadata file");
    }

    static Stream<Arguments> namelessMembers() {
        return Stream.of(
                Arguments.of("legacy", "has_uv.mzpeak", "chromatograms_metadata.parquet"),
                Arguments.of("legacy", "has_uv.mzpeak", "chromatograms_data.parquet"),
                Arguments.of("legacy", "has_uv.mzpeak", "wavelength_spectra_metadata.parquet"),
                Arguments.of("legacy", "has_uv.mzpeak", "wavelength_spectra_data.parquet"),
                Arguments.of("current", "small.unpacked.mzpeak", "spectra_metadata.parquet"),
                Arguments.of("current", "small.unpacked.mzpeak", "spectra_data.parquet"),
                Arguments.of("current", "small.unpacked.mzpeak", "spectra_metadata_scans.parquet"));
    }

    /**
     * A member listed under its role but without a name makes the manifest invalid, as in both references (Rust
     * {@code FileEntry.name} is required, Python reads {@code data["name"]}). Fails on 1e400ed, where a nameless
     * member counted as missing, so spectra silently lost their arrays or facets.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("namelessMembers")
    void aMemberWithoutANameIsRejected(String set, String fixture, String member, @TempDir Path tmp)
            throws IOException {
        Path dir = unpack(set.equals("legacy") ? Fixtures.legacy(fixture) : Fixtures.current(fixture), tmp);
        int[] position = {-1};
        String[] role = {null};
        editIndex(dir, index -> {
            JsonNode files = index.path("files");
            for (int i = 0; i < files.size(); i++) {
                JsonNode f = files.get(i);
                if (member.equals(f.path("name").asText())) {
                    position[0] = i;
                    role[0] = f.path("entity_type").asText() + "/" + f.path("data_kind").asText();
                    ((ObjectNode) f).remove("name");
                }
            }
        });
        assertThat(position[0]).as(member).isNotNegative();
        String expected = "mzpeak_index.json files[" + position[0] + "] (" + role[0] + ") has no name in ";

        try (MzPeakSource source = MzPeakSource.open(dir)) {
            assertThatThrownBy(() -> MzPeakManifest.fromSource(source))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith(expected)
                    .hasMessageEndingWith(source.describe());
        }
        assertThatThrownBy(() -> MzPeakReader.open(dir))
                .isInstanceOf(MzPeakException.class)
                .hasMessageStartingWith(expected);
    }

    /** Of two entries with the same role, the first in manifest order wins. */
    @Test
    void aDuplicateRoleResolvesToTheFirstEntryInManifestOrder(@TempDir Path tmp) throws IOException {
        Path dir = unpack(Fixtures.current("small.unpacked.mzpeak"), tmp);
        editIndex(dir, index -> ((ArrayNode) index.path("files")).insertObject(0)
                .put("name", "spectra_peaks.parquet").put("entity_type", "spectrum").put("data_kind", "data_arrays"));
        MzPeakManifest m = manifest(dir);

        assertThat(m.files()).filteredOn(e -> e.kind() == DataKind.DATA_ARRAYS && e.entity() == EntityType.SPECTRUM)
                .extracting(Entry::name).containsExactly("spectra_peaks.parquet", "spectra_data.parquet");
        assertThat(m.find(EntityType.SPECTRUM, DataKind.DATA_ARRAYS)).containsSame(m.files().get(0));
        assertThat(m.find("spectrum", "data arrays")).containsSame(m.files().get(0));
    }

    /** Shape errors name the member the manifest resolved, here a data member listed under a metadata role. */
    @Test
    void shapeErrorsNameTheResolvedMember(@TempDir Path tmp) throws IOException {
        Path dir = unpack(Fixtures.legacy("small.unpacked.mzpeak"), tmp);
        editIndex(dir, index -> {
            ArrayNode files = index.putArray("files");
            files.addObject().put("name", "spectra_data.parquet")
                    .put("entity_type", "spectrum").put("data_kind", "metadata");
            files.addObject().put("name", "spectra_peaks.parquet")
                    .put("entity_type", "chromatogram").put("data_kind", "metadata");
            files.addObject().put("name", "chromatograms_data.parquet")
                    .put("entity_type", "chromatogram").put("data_kind", "data_arrays");
            files.addObject().put("name", "chromatograms_data.parquet")
                    .put("entity_type", "wavelength_spectrum").put("data_kind", "metadata");
            files.addObject().put("name", "spectra_data.parquet")
                    .put("entity_type", "wavelength spectrum").put("data_kind", "data arrays");
        });
        try (MzPeakSource source = MzPeakSource.open(dir)) {
            MzPeakManifest m = MzPeakManifest.fromSource(source);

            assertThatThrownBy(() -> SpectrumMetadataDecoder.decode(source, m))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectra_data.parquet has ")
                    .hasMessageContaining("top-level struct 'spectrum'")
                    .hasMessageContaining("columns found: [point]");
            assertThatThrownBy(() -> ChromatogramStore.load(source, m))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectra_peaks.parquet has ")
                    .hasMessageContaining("top-level struct 'chromatogram'")
                    .hasMessageContaining("columns found: [point]");
            assertThatThrownBy(() -> WavelengthSpectrumStore.load(source, m))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("chromatograms_data.parquet has ")
                    .hasMessageContaining("top-level struct 'spectrum'");
        }
    }

    // ---- (d) unknown and non-signal roles ------------------------------------------------------

    @Test
    void unknownAndNonSignalRolesKeepTheirLiteralsAndChangeNoResolution(@TempDir Path tmp) throws IOException {
        Path original = Fixtures.current("small.unpacked.mzpeak");
        Path edited = unpack(original, tmp);
        editIndex(edited, index -> {
            ArrayNode files = (ArrayNode) index.path("files");
            // inserted first, so a wrong match would win over the real member
            files.insertObject(0).put("name", "spectra_peaks.parquet")
                    .put("entity_type", "spectrum").put("data_kind", "bar");
            files.insertObject(0).put("name", "spectra_data.parquet")
                    .put("entity_type", "Foo").put("data_kind", "bar");
            files.insertObject(0).put("name", "spectra_data.parquet")
                    .put("entity_type", "vendor").put("data_kind", "proprietary");
            files.insertObject(0).put("name", "provenance.sig")
                    .put("entity_type", "provenance").put("data_kind", "signature");
        });
        MzPeakManifest before = manifest(original);
        MzPeakManifest after = manifest(edited);

        List<Entry> added = after.files().subList(0, 4);
        assertThat(added).extracting(Entry::entityType).containsExactly("provenance", "vendor", "Foo", "spectrum");
        assertThat(added).extracting(Entry::dataKind).containsExactly("signature", "proprietary", "bar", "bar");
        assertThat(added).extracting(Entry::entity)
                .containsExactly(EntityType.PROVENANCE, EntityType.OTHER, EntityType.OTHER, EntityType.SPECTRUM);
        assertThat(added).extracting(Entry::kind)
                .containsExactly(DataKind.SIGNATURE, DataKind.PROPRIETARY, DataKind.OTHER, DataKind.OTHER);
        assertThat(after.files().subList(4, after.files().size())).isEqualTo(before.files());

        for (EntityType entity : EntityType.values()) {
            for (DataKind kind : DataKind.values()) {
                if (entity == EntityType.PROVENANCE && kind == DataKind.SIGNATURE) {
                    assertThat(before.find(entity, kind)).isEmpty();
                    assertThat(after.find(entity, kind)).containsSame(added.get(0));
                } else {
                    assertThat(after.find(entity, kind)).as("%s/%s", entity, kind).isEqualTo(before.find(entity, kind));
                }
            }
        }
        assertThat(after.find(EntityType.OTHER, DataKind.PROPRIETARY)).isEmpty();
        assertThat(after.find(EntityType.OTHER, DataKind.OTHER)).isEmpty();
        assertThat(after.find(EntityType.SPECTRUM, DataKind.OTHER)).isEmpty();
        assertThat(after.find("vendor", "proprietary")).isEmpty();
        assertThat(after.find("Foo", "bar")).isEmpty();
        assertThat(after.find("spectrum", "bar")).isEmpty();
        assertThat(after.find("other", "other")).isEmpty();
    }

    @Test
    void readerIgnoresUnknownAndNonSignalRoles(@TempDir Path tmp) throws IOException {
        Path original = Fixtures.legacy("small.unpacked.mzpeak");
        Path edited = unpack(original, tmp);
        editIndex(edited, index -> {
            ArrayNode files = (ArrayNode) index.path("files");
            files.insertObject(0).put("name", "chromatograms_data.parquet")
                    .put("entity_type", "Foo").put("data_kind", "metadata");
            files.insertObject(0).put("name", "spectra_peaks.parquet")
                    .put("entity_type", "spectrum").put("data_kind", "bar");
            files.insertObject(0).put("name", "spectra_peaks.parquet")
                    .put("entity_type", "vendor").put("data_kind", "proprietary");
            files.insertObject(0).put("name", "provenance.sig")
                    .put("entity_type", "provenance").put("data_kind", "signature");
        });

        assertReadsAlike(original, edited);
    }

    // ---- parse and canonical -------------------------------------------------------------------

    @Test
    void parseLowercasesTrimsAndAcceptsTheReferenceAliases() {
        assertThat(EntityType.parse("spectrum")).isEqualTo(EntityType.SPECTRUM);
        assertThat(EntityType.parse("mass spectrum")).isEqualTo(EntityType.SPECTRUM);
        assertThat(EntityType.parse("mass_spectrum")).isEqualTo(EntityType.SPECTRUM);
        assertThat(EntityType.parse(" Mass Spectrum\t")).isEqualTo(EntityType.SPECTRUM);
        assertThat(EntityType.parse("CHROMATOGRAM")).isEqualTo(EntityType.CHROMATOGRAM);
        assertThat(EntityType.parse("wavelength spectrum")).isEqualTo(EntityType.WAVELENGTH_SPECTRUM);
        assertThat(EntityType.parse("Wavelength_Spectrum ")).isEqualTo(EntityType.WAVELENGTH_SPECTRUM);
        assertThat(EntityType.parse("provenance")).isEqualTo(EntityType.PROVENANCE);
        assertThat(EntityType.parse("other")).isEqualTo(EntityType.OTHER);
        assertThat(EntityType.parse("spectra")).isEqualTo(EntityType.OTHER);
        assertThat(EntityType.parse("mass-spectrum")).isEqualTo(EntityType.OTHER);
        assertThat(EntityType.parse("")).isEqualTo(EntityType.OTHER);
        assertThat(EntityType.parse(null)).isEqualTo(EntityType.OTHER);

        assertThat(DataKind.parse("data arrays")).isEqualTo(DataKind.DATA_ARRAYS);
        assertThat(DataKind.parse("data_arrays")).isEqualTo(DataKind.DATA_ARRAYS);
        assertThat(DataKind.parse(" Data Arrays ")).isEqualTo(DataKind.DATA_ARRAYS);
        assertThat(DataKind.parse("PEAKS")).isEqualTo(DataKind.PEAKS);
        assertThat(DataKind.parse("Metadata")).isEqualTo(DataKind.METADATA);
        assertThat(DataKind.parse("scans")).isEqualTo(DataKind.SCANS);
        assertThat(DataKind.parse("precursors")).isEqualTo(DataKind.PRECURSORS);
        assertThat(DataKind.parse("selected_ions")).isEqualTo(DataKind.SELECTED_IONS);
        assertThat(DataKind.parse("products")).isEqualTo(DataKind.PRODUCTS);
        assertThat(DataKind.parse("signature")).isEqualTo(DataKind.SIGNATURE);
        assertThat(DataKind.parse("proprietary")).isEqualTo(DataKind.PROPRIETARY);
        assertThat(DataKind.parse("selected ions")).isEqualTo(DataKind.OTHER);
        assertThat(DataKind.parse("data-arrays")).isEqualTo(DataKind.OTHER);
        assertThat(DataKind.parse("other")).isEqualTo(DataKind.OTHER);
        assertThat(DataKind.parse("")).isEqualTo(DataKind.OTHER);
        assertThat(DataKind.parse(null)).isEqualTo(DataKind.OTHER);
    }

    @Test
    void canonicalIsTheReferenceSpellingAndParsesBack() {
        assertThat(Stream.of(EntityType.values()).map(EntityType::canonical)).containsExactly(
                "spectrum", "chromatogram", "wavelength_spectrum", "provenance", "other");
        assertThat(Stream.of(DataKind.values()).map(DataKind::canonical)).containsExactly(
                "data_arrays", "peaks", "metadata", "scans", "precursors", "selected_ions", "products", "signature",
                "proprietary", "other");
        for (EntityType e : EntityType.values()) {
            assertThat(EntityType.parse(e.canonical())).isEqualTo(e);
        }
        for (DataKind k : DataKind.values()) {
            assertThat(DataKind.parse(k.canonical())).isEqualTo(k);
        }
    }

    @Test
    void findRejectsNullRolesButParsesNullStrings() {
        MzPeakManifest m = manifest(Fixtures.legacy("small.unpacked.mzpeak"));

        assertThatThrownBy(() -> m.find(null, DataKind.METADATA)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> m.find(EntityType.SPECTRUM, null)).isInstanceOf(NullPointerException.class);
        assertThat(m.find((String) null, "metadata")).isEmpty();
        assertThat(m.find("spectrum", (String) null)).isEmpty();
        assertThat(m.find(" Spectrum", "METADATA ")).map(Entry::name).contains("spectra_metadata.parquet");
    }

    // ---- helpers ---------------------------------------------------------------------------

    /** Opens both datasets through the reader and compares spectra, chromatograms and wavelength spectra. */
    private static void assertReadsAlike(Path expectedDataset, Path actualDataset) {
        try (MzPeakReader expected = MzPeakReader.open(expectedDataset);
             MzPeakReader actual = MzPeakReader.open(actualDataset)) {
            assertThat(actual.size()).isEqualTo(expected.size()).isPositive();
            long points = 0;
            for (int i = 0; i < expected.size(); i++) {
                Spectrum e = expected.getSpectrumAt(i);
                Spectrum a = actual.getSpectrumAt(i);
                assertThat(a.description()).isEqualTo(e.description());
                assertThat(a.mz()).as("mz of %d", e.index()).isEqualTo(e.mz());
                assertThat(a.intensity()).as("intensity of %d", e.index()).isEqualTo(e.intensity());
                assertThat(a.peaks()).as("peaks of %d", e.index()).isEqualTo(e.peaks());
                points += e.mz().length;
            }
            assertThat(points).isPositive();

            List<Chromatogram> ec = expected.chromatograms();
            List<Chromatogram> ac = actual.chromatograms();
            assertThat(ec).isNotEmpty();
            assertThat(ac).hasSameSizeAs(ec);
            for (int i = 0; i < ec.size(); i++) {
                assertThat(ac.get(i).id()).isEqualTo(ec.get(i).id());
                assertThat(ac.get(i).typeCurie()).isEqualTo(ec.get(i).typeCurie());
                assertThat(ac.get(i).time()).isEqualTo(ec.get(i).time());
                assertThat(ac.get(i).intensity()).isEqualTo(ec.get(i).intensity());
            }

            List<WavelengthSpectrum> ew = expected.wavelengthSpectra();
            List<WavelengthSpectrum> aw = actual.wavelengthSpectra();
            assertThat(aw).hasSameSizeAs(ew);
            for (int i = 0; i < ew.size(); i++) {
                assertThat(aw.get(i).id()).isEqualTo(ew.get(i).id());
                assertThat(aw.get(i).time()).isEqualTo(ew.get(i).time());
                assertThat(aw.get(i).lambdaMax()).isEqualTo(ew.get(i).lambdaMax());
                assertThat(aw.get(i).wavelength()).isEqualTo(ew.get(i).wavelength());
                assertThat(aw.get(i).intensity()).isEqualTo(ew.get(i).intensity());
            }
        }
    }

    private static MzPeakManifest manifest(Path dataset) {
        try (MzPeakSource src = MzPeakSource.open(dataset)) {
            return MzPeakManifest.fromSource(src);
        }
    }

    private static Optional<String> name(MzPeakManifest manifest, EntityType entity, DataKind kind) {
        return manifest.find(entity, kind).map(Entry::name);
    }

    private static Entry entry(MzPeakManifest manifest, String fileName) {
        return manifest.files().stream().filter(e -> fileName.equals(e.name())).findFirst()
                .orElseThrow(() -> new AssertionError("no manifest entry " + fileName));
    }

    private static void removeEntries(ObjectNode index, String... names) {
        List<String> remove = List.of(names);
        ArrayNode files = (ArrayNode) index.path("files");
        for (int i = files.size() - 1; i >= 0; i--) {
            if (remove.contains(files.get(i).path("name").asText())) {
                files.remove(i);
            }
        }
    }

    /** Copies an unpacked dataset, or extracts a single-file one, into a directory under {@code tmp}. */
    private static Path unpack(Path dataset, Path tmp) throws IOException {
        Path copy = tmp.resolve(dataset.getFileName().toString());
        Files.createDirectories(copy);
        if (Files.isDirectory(dataset)) {
            try (Stream<Path> files = Files.list(dataset)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    Files.copy(f, copy.resolve(f.getFileName().toString()));
                }
            }
            return copy;
        }
        try (ZipFile zip = new ZipFile(dataset.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                Path target = copy.resolve(e.getName()).normalize();
                if (e.isDirectory() || !target.startsWith(copy)) {
                    throw new AssertionError("unexpected ZIP entry " + e.getName() + " in " + dataset);
                }
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, target);
                }
            }
        }
        return copy;
    }

    private static void editIndex(Path unpacked, Consumer<ObjectNode> edit) throws IOException {
        ObjectNode index = (ObjectNode) MAPPER.readTree(Files.readAllBytes(unpacked.resolve(INDEX)));
        edit.accept(index);
        Files.write(unpacked.resolve(INDEX), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(index));
    }
}
