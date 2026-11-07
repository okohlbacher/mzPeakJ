package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Layout guard (RD-3): a metadata member is decoded only when its own schema has the packed shape (a top-level
 * struct named after the entity) or the split shape (a top-level primitive key column). A member that has rows but
 * neither shape throws instead of reading as zero entities; a member with zero rows stays legal and reads as empty.
 * Runs on the schemas of both fixture sets.
 */
class LayoutGuardTest {

    private static final Set<String> DATA_ROLES = Set.of("data arrays", "data_arrays");

    /** The three metadata decoders, each reduced to the number of entities it decodes from a metadata member. */
    enum Decoder {
        SPECTRUM("spectrum metadata", "spectrum") {
            @Override
            int count(InputFile metadata, InputFile data) {
                return SpectrumMetadataDecoder.decode(metadata).descriptions().size();
            }
        },
        CHROMATOGRAM("chromatogram metadata", "chromatogram") {
            @Override
            int count(InputFile metadata, InputFile data) {
                return ChromatogramStore.load(metadata, data).all().size();
            }
        },
        WAVELENGTH_SPECTRUM("wavelength spectrum metadata", "spectrum") {
            @Override
            int count(InputFile metadata, InputFile data) {
                return WavelengthSpectrumStore.load(metadata, data).all().size();
            }
        };

        final String member;
        final String packedStruct;

        Decoder(String member, String packedStruct) {
            this.member = member;
            this.packedStruct = packedStruct;
        }

        abstract int count(InputFile metadata, InputFile data);
    }

    static Stream<Arguments> decodersGivenSpectrumData() {
        return Stream.of(Decoder.values()).flatMap(decoder -> Stream.of(
                Arguments.of(decoder, "small.mzpeak", "point"),
                Arguments.of(decoder, "small.chunked.mzpeak", "chunk")));
    }

    /** (b) A spectrum data member with rows is neither packed nor split metadata: each decoder throws. */
    @ParameterizedTest(name = "{0} given the spectrum data member of {1}")
    @MethodSource("decodersGivenSpectrumData")
    void memberWithRowsButNeitherShapeThrows(Decoder decoder, String fixture, String column) {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(fixture))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            InputFile spectrumData = source.inputFile(member(manifest, "spectrum", DATA_ROLES));
            // the stores' data argument; the guard rejects the metadata member before it is read
            InputFile chromatogramData = source.inputFile(member(manifest, "chromatogram", DATA_ROLES));
            assertThatThrownBy(() -> decoder.count(spectrumData, chromatogramData))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith(decoder.member + " member has ")
                    .hasMessageContaining("top-level struct '" + decoder.packedStruct + "'")
                    .hasMessageContaining("columns found: [" + column + "]");
        }
    }

    /** (b) The same shape with zero rows is a legal empty member: each decoder returns nothing. */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Decoder.class)
    void zeroRowMemberOfNeitherShapeDecodesToNothing(Decoder decoder) {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("has_uv.mzpeak"))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String spectrumData = member(manifest, "spectrum", DATA_ROLES);
            assertThat(rowCount(source, spectrumData)).isZero();
            InputFile chromatogramData = source.inputFile(member(manifest, "chromatogram", DATA_ROLES));
            assertThat(decoder.count(source.inputFile(spectrumData), chromatogramData)).isZero();
        }
    }

    /** (d) Legacy has_uv, whose spectrum data member has zero rows, still opens and reads its peaks. */
    @Test
    void hasUvWithZeroRowSpectrumDataStillLoads() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("has_uv.mzpeak"))) {
            try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy("has_uv.mzpeak"))) {
                assertThat(rowCount(source, member(reader.manifest(), "spectrum", DATA_ROLES))).isZero();
                assertThat(rowCount(source, member(reader.manifest(), "spectrum", Set.of("peaks"))))
                        .isEqualTo(24310);
            }
            assertThat(reader.size()).isEqualTo(212);
            assertThat(reader.chromatograms()).hasSize(2);
            assertThat(reader.wavelengthSpectra()).hasSize(520);
            long points = 0;
            for (Spectrum s : reader) {
                assertThat(s.intensity()).hasSameSizeAs(s.mz());
                points += s.mz().length;
            }
            assertThat(points).isEqualTo(24310);
        }
    }

    /** (a) Every metadata member of the legacy set is packed. */
    @Test
    void everyLegacyMetadataMemberIsPacked() {
        Map<String, MetadataShape> shapes = shapes(Fixtures.LEGACY, Fixtures.LEGACY_NAMES);
        // 4 x (spectrum, chromatogram) + has_uv (spectrum, chromatogram, wavelength spectrum)
        assertThat(shapes).hasSize(11).allSatisfy((member, shape) ->
                assertThat(shape).as(member).isEqualTo(MetadataShape.PACKED));
    }

    /** (a) Every metadata and facet member of the current set is split, including the zero-row facets. */
    @Test
    void everyCurrentMetadataAndFacetMemberIsSplit() {
        Map<String, MetadataShape> shapes = shapes(Fixtures.CURRENT, Fixtures.CURRENT_NAMES);
        // 6 x 7 (spectrum 4, chromatogram 3) + Example_Processed.img 4 + has_uv 9 (7 plus wavelength spectrum 2)
        assertThat(shapes).hasSize(55).allSatisfy((member, shape) ->
                assertThat(shape).as(member).isEqualTo(MetadataShape.SPLIT));
        assertThat(shapes).containsKeys("small.mzpeak/chromatograms_metadata_precursors.parquet",
                "has_uv.mzpeak/wavelength_spectra_metadata_scans.parquet");
    }

    @Test
    void detectNamesTheMemberTheExpectedStructAndTheColumnsFound() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.mzpeak"))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String name = member(manifest, "spectrum", DATA_ROLES);
            ParquetFooter footer = ParquetFooter.read(source.inputFile(name));
            assertThatThrownBy(() -> MetadataShape.detect(footer, "spectrum", "index", name))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage(name + " has 217710 rows but neither the packed shape (top-level struct 'spectrum')"
                            + " nor the split shape (top-level column 'index'); columns found: [point]");
        }
    }

    @Test
    void zeroRowMemberOfNeitherShapeIsEmpty() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("has_uv.mzpeak"))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            String name = member(manifest, "spectrum", DATA_ROLES);
            ParquetFooter footer = ParquetFooter.read(source.inputFile(name));
            assertThat(footer.topLevelFields()).containsExactly("point");
            assertThat(MetadataShape.detect(footer, "spectrum", "index", name)).isEqualTo(MetadataShape.EMPTY);
        }
    }

    @Test
    void footerHoldsSchemaRowCountAndKeyValues() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.legacy("small.unpacked.mzpeak"))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            InputFile metadata = source.inputFile(member(manifest, "spectrum", Set.of("metadata")));
            ParquetFooter footer = ParquetFooter.read(metadata);
            assertThat(footer.rowCount()).isEqualTo(48);
            assertThat(footer.topLevelFields()).containsExactly("spectrum", "scan", "precursor", "selected_ion");
            assertThat(footer.keyValue()).isNotEmpty().isEqualTo(ParquetGroups.footerKeyValue(metadata));
        }
    }

    /** The shape of every metadata and facet member of the fixtures {@code names} in {@code dir}, by fixture/member. */
    private static Map<String, MetadataShape> shapes(Path dir, List<String> names) {
        Map<String, MetadataShape> out = new LinkedHashMap<>();
        for (String fixture : names) {
            try (MzPeakSource source = MzPeakSource.open(dir.resolve(fixture))) {
                for (MzPeakManifest.Entry e : MzPeakManifest.fromSource(source).files()) {
                    String keyColumn = "metadata".equals(e.dataKind()) ? "index" : "source_index";
                    String packedStruct = switch (e.dataKind()) {
                        case "metadata" -> "chromatogram".equals(e.entityType()) ? "chromatogram" : "spectrum";
                        case "scans" -> "scan";
                        case "precursors" -> "precursor";
                        case "selected_ions" -> "selected_ion";
                        default -> null;
                    };
                    if (packedStruct != null) {
                        ParquetFooter footer = ParquetFooter.read(source.inputFile(e.name()));
                        out.put(fixture + "/" + e.name(),
                                MetadataShape.detect(footer, packedStruct, keyColumn, e.name()));
                    }
                }
            }
        }
        return out;
    }

    /** The name of the single member with entity type {@code entity} and one of {@code roles}. */
    private static String member(MzPeakManifest manifest, String entity, Set<String> roles) {
        return manifest.files().stream()
                .filter(e -> entity.equals(e.entityType()) && roles.contains(e.dataKind()))
                .map(MzPeakManifest.Entry::name)
                .reduce((a, b) -> {
                    throw new AssertionError("several " + entity + " members with roles " + roles);
                })
                .orElseThrow(() -> new AssertionError("no " + entity + " member with roles " + roles));
    }

    private static long rowCount(MzPeakSource source, String member) {
        return ParquetFooter.read(source.inputFile(member)).rowCount();
    }
}
