package org.mzpeak.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Product;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R6 (D13-21, X-26): the precursor {@code ms_level} (spectra.md §Spectrum precursor metadata) is read into
 * {@link Precursor#msLevel()}, and the products facets (spectra.md §Spectrum product selection metadata,
 * chromatograms.md §products) into {@link SpectrumDescription#products()} and {@link Chromatogram#products()}, each
 * with its {@code precursor_index}, in {@code product_index} order. Neither is written (WriteLossAuditTest).
 *
 * <p>Fixture ({@link #craft}): a copy of current small.unpacked whose {@code spectra_metadata_precursors} has an
 * {@code ms_level} column (uint8, as an Arrow writer stores it) holding 2 for the precursor of spectrum
 * {@link #SPECTRUM} and null for every other, and two new members the manifest lists by role, built from the
 * fixture's own precursor column types: {@code spectra_metadata_products} with product_index 1 (precursor_index 3,
 * isolation window target 600.0, offsets 1.0/1.0) and product_index 0 (precursor_index 4, target 500.0, offsets
 * 1.0/1.0, a dwell time Param) of spectrum 5, in that file order, and a product_index 2 of spectrum 7 without
 * precursor_index and isolation window; {@code chromatograms_metadata_products} with the same two products of
 * chromatogram 0. Every other cell is the fixture's.
 */
class PrecursorMsLevelAndProductsTest {

    static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    static final long SPECTRUM = 5;
    static final String SPECTRUM_PRODUCTS = "spectra_metadata_products.parquet";
    static final String CHROMATOGRAM_PRODUCTS = "chromatograms_metadata_products.parquet";
    private static final String PRECURSORS = "spectra_metadata_precursors.parquet";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Param DWELL = new Param("dwell time", "MS:1000502", 0.05, "UO:0000010");

    /** The two products of spectrum 5 and chromatogram 0, in product_index order. */
    static final List<Product> PRODUCTS = List.of(
            new Product(4L, 0, new IsolationWindow(500.0, 1.0, 1.0), List.of(DWELL)),
            new Product(3L, 1, new IsolationWindow(600.0, 1.0, 1.0), List.of()));

    @TempDir
    Path tmp;

    /** The fixture has neither: every precursor reads no MS level, every spectrum and chromatogram no products. */
    @Test
    void theFixtureReadsNoMsLevelAndNoProducts() {
        try (MzPeakReader reader = MzPeakReader.open(SMALL)) {
            List<SpectrumDescription> metadata = reader.metadata();
            assertThat(metadata).hasSize(48).allMatch(d -> d.products().isEmpty());
            assertThat(metadata.stream().flatMap(d -> d.precursors().stream()).toList()).isNotEmpty()
                    .allMatch(p -> p.msLevel() == null);
            assertThat(reader.chromatograms()).isNotEmpty().allMatch(c -> c.products().isEmpty());
        }
    }

    /** The precursor row of spectrum 5 with ms_level 2 reads msLevel() 2; nothing else changes. */
    @Test
    void thePrecursorMsLevelIsRead() throws IOException {
        Path crafted = craft(tmp, true);
        try (MzPeakReader reader = MzPeakReader.open(crafted); MzPeakReader fixture = MzPeakReader.open(SMALL)) {
            List<SpectrumDescription> metadata = reader.metadata();
            List<SpectrumDescription> expected = fixture.metadata();
            for (int i = 0; i < metadata.size(); i++) {
                List<Precursor> precursors = metadata.get(i).precursors();
                List<Precursor> before = expected.get(i).precursors();
                assertThat(precursors).as("spectrum %d", i).hasSameSizeAs(before);
                for (int k = 0; k < precursors.size(); k++) {
                    Precursor p = precursors.get(k);
                    Precursor b = before.get(k);
                    assertThat(p.msLevel()).as("spectrum %d", i).isEqualTo(i == SPECTRUM ? 2 : null);
                    assertThat(new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(),
                            p.selectedIons(), p.activation())).as("spectrum %d", i).isEqualTo(b);
                }
            }
            assertThat(metadata.get((int) SPECTRUM).primaryPrecursor().msLevel()).isEqualTo(2);
        }
    }

    /**
     * Spectrum 5 reads its two products in product_index order, though the file has them the other way round, each
     * with its precursor_index, window and Params; spectrum 7 reads its product without precursor_index and window;
     * every other spectrum reads none, and the rest of each description is the fixture's.
     */
    @Test
    void theSpectrumProductsAreReadInProductIndexOrder() throws IOException {
        Path crafted = craft(tmp, true);
        try (MzPeakReader reader = MzPeakReader.open(crafted); MzPeakReader fixture = MzPeakReader.open(SMALL)) {
            List<SpectrumDescription> metadata = reader.metadata();
            List<SpectrumDescription> expected = fixture.metadata();
            assertThat(metadata).hasSize(48);
            SpectrumDescription five = metadata.get((int) SPECTRUM);
            assertThat(five.products()).containsExactlyElementsOf(PRODUCTS);
            assertThat(five.products()).extracting(Product::precursorIndex).containsExactly(4L, 3L);
            assertThat(five.products()).extracting(Product::productIndex).containsExactly(0L, 1L);
            assertThat(five.products().get(0).isolationWindow().targetMz()).isEqualTo(500.0);
            assertThat(five.products().get(1).isolationWindow().targetMz()).isEqualTo(600.0);
            assertThat(metadata.get(7).products()).containsExactly(new Product(null, 2, null, List.of()));
            for (int i = 0; i < metadata.size(); i++) {
                SpectrumDescription d = metadata.get(i);
                if (i != SPECTRUM && i != 7) {
                    assertThat(d.products()).as("spectrum %d", i).isEmpty();
                }
                assertThat(withoutReadOnly(d)).as("spectrum %d", i).isEqualTo(expected.get(i));
            }
        }
    }

    /** Chromatogram 0 reads the same two products in product_index order; its other components are the fixture's. */
    @Test
    void theChromatogramProductsAreReadInProductIndexOrder() throws IOException {
        Path crafted = craft(tmp, true);
        try (MzPeakReader reader = MzPeakReader.open(crafted); MzPeakReader fixture = MzPeakReader.open(SMALL)) {
            List<Chromatogram> chromatograms = reader.chromatograms();
            List<Chromatogram> expected = fixture.chromatograms();
            assertThat(chromatograms).hasSameSizeAs(expected);
            Chromatogram tic = chromatograms.get(0);
            assertThat(tic.products()).containsExactlyElementsOf(PRODUCTS);
            assertThat(tic.products()).extracting(Product::precursorIndex).containsExactly(4L, 3L);
            for (int i = 1; i < chromatograms.size(); i++) {
                assertThat(chromatograms.get(i).products()).as("chromatogram %d", i).isEmpty();
            }
            Chromatogram before = expected.get(0);
            assertThat(tic.time()).isEqualTo(before.time());
            assertThat(tic.intensity()).isEqualTo(before.intensity());
            assertThat(tic.parameters()).isEqualTo(before.parameters());
            assertThat(tic.precursors()).isEqualTo(before.precursors());
            assertThat(tic.timeUnit()).isEqualTo(before.timeUnit());
        }
    }

    /** A product row without product_index is rejected, the message naming the member, the row and the column. */
    @Test
    void aProductWithoutProductIndexIsRejected() throws IOException {
        Path crafted = craft(tmp, false);
        assertThatThrownBy(() -> {
            try (MzPeakReader reader = MzPeakReader.open(crafted)) {
                reader.metadata();
            }
        }).isInstanceOf(MzPeakException.class)
                .hasMessage(SPECTRUM_PRODUCTS + ": the product row of source_index 7 has no product_index");
    }

    /** The products survive Chromatogram.withTimeUnit, and products and MS level the writer's precursor copies. */
    @Test
    void theValuesSurviveTheCopies() throws IOException {
        Path crafted = craft(tmp, true);
        Source source = ForwardBackwardCorpusTest.readSource(crafted);
        Chromatogram tic = source.chromatograms.get(0);
        assertThat(tic.products()).containsExactlyElementsOf(PRODUCTS);

        Chromatogram seconds = tic.withTimeUnit("UO:0000010");
        assertThat(seconds).isNotSameAs(tic);
        assertThat(seconds.products()).containsExactlyElementsOf(PRODUCTS);
        assertThat(seconds.withTimeUnit("UO:0000031").products()).containsExactlyElementsOf(PRODUCTS);

        // a chromatogram precursor renumbered by the writer keeps its MS level, the chromatogram its products
        Precursor precursor = new Precursor(7L, null, new IsolationWindow(445.3, 0.5, 0.5), List.of(),
                Activation.EMPTY, 2);
        Chromatogram withPrecursor = new Chromatogram(tic.index(), tic.id(), tic.typeCurie(), tic.time(),
                tic.intensity(), tic.intensityUnit(), tic.arrays(), tic.polarity(), tic.dataProcessingRef(),
                tic.parameters(), List.of(precursor), tic.timeUnit(), tic.products());
        Chromatogram written = WritePlanner.withWrittenPrecursors(withPrecursor, 0, Map.of(7L, 1L),
                MetadataLayout.SPLIT);
        assertThat(written).isNotSameAs(withPrecursor);
        assertThat(written.precursors()).extracting(Precursor::precursorIndex).containsExactly(1L);
        assertThat(written.precursors()).extracting(Precursor::msLevel).containsExactly(2);
        assertThat(written.products()).containsExactlyElementsOf(PRODUCTS);

        // spectrum 5, renumbered 3 without spectra 0 and 1, keeps its precursor's MS level, renumbered with it
        List<Spectrum> later = source.spectra.subList(2, source.spectra.size());
        WritePlan plan = WritePlanner.plan(later, List.of(), List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withMetadataLayout(MetadataLayout.SPLIT));
        WritePlan.SpectrumEntry five = plan.spectra().get((int) SPECTRUM - 2);
        assertThat(five.description().products()).containsExactlyElementsOf(PRODUCTS);
        assertThat(five.precursors()).hasSize(1);
        assertThat(five.precursors().get(0).precursorIndex()).isNull();
        assertThat(five.precursors().get(0).msLevel()).isEqualTo(2);
    }

    /** The old constructors give no MS level and no products; the new ones keep them, and they take part in equality. */
    @Test
    void theOldConstructorsGiveNoMsLevelAndNoProducts() {
        Precursor old = new Precursor(1L, "p", null, null, null);
        assertThat(old.msLevel()).isNull();
        assertThat(old).isEqualTo(new Precursor(1L, "p", null, null, null, null))
                .isNotEqualTo(new Precursor(1L, "p", null, null, null, 2));

        SpectrumDescription plain = new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null,
                "dp");
        assertThat(plain.products()).isEmpty();
        assertThat(plain).isEqualTo(new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null, "dp",
                null)).isNotEqualTo(new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null, "dp",
                PRODUCTS));
        assertThat(new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null).products()).isEmpty();

        double[] none = new double[0];
        Chromatogram c = new Chromatogram(0, "c", null, none, none, null, null, null, null, null, null, null);
        assertThat(c.products()).isEmpty();
        assertThat(new Chromatogram(0, "c", null, none, none, null, null, null, null, null, null, null, null)
                .products()).isEmpty();
        Chromatogram withProducts = new Chromatogram(0, "c", null, none, none, null, null, null, null, null, null,
                null, PRODUCTS);
        assertThat(withProducts.products()).isEqualTo(PRODUCTS);
        assertThatThrownBy(() -> withProducts.products().clear()).isInstanceOf(UnsupportedOperationException.class);
        List<Product> withNull = new ArrayList<>(PRODUCTS);
        withNull.add(null);
        assertThatThrownBy(() -> new Chromatogram(0, "c", null, none, none, null, null, null, null, null, null, null,
                withNull)).isInstanceOf(NullPointerException.class);

        Product product = new Product(null, 3, null, null);
        assertThat(product.parameters()).isEmpty();
        List<Param> params = new ArrayList<>(List.of(DWELL));
        Product copied = new Product(1L, 0, null, params);
        params.clear();
        assertThat(copied.parameters()).containsExactly(DWELL);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** {@code d} without what this unit reads: no products and precursors without MS level. */
    private static SpectrumDescription withoutReadOnly(SpectrumDescription d) {
        List<Precursor> precursors = new ArrayList<>();
        for (Precursor p : d.precursors()) {
            precursors.add(new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(), p.selectedIons(),
                    p.activation()));
        }
        return new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters(), d.dataProcessingRef());
    }

    /**
     * The crafted copy of small.unpacked described in the class comment, in a new directory under {@code tmp}; with
     * {@code productIndex} false, spectrum 7's product row has no product_index.
     */
    static Path craft(Path tmp, boolean productIndex) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp.resolve("crafted-" + productIndex));
        MemberRewrite.Member precursors = MemberRewrite.read(copy.resolve(PRECURSORS));
        addMsLevel(precursors);
        MemberRewrite.write(precursors, copy.resolve(PRECURSORS));

        MessageType schema = productSchema(precursors.schema);
        MemberRewrite.Member spectrumProducts = new MemberRewrite.Member();
        spectrumProducts.schema = schema;
        addProducts(spectrumProducts, SPECTRUM);
        Group seven = new SimpleGroupFactory(schema).newGroup().append("source_index", 7L);
        if (productIndex) {
            seven.append("product_index", 2L);
        }
        spectrumProducts.rows.add(seven);
        MemberRewrite.write(spectrumProducts, copy.resolve(SPECTRUM_PRODUCTS));

        MemberRewrite.Member chromatogramProducts = new MemberRewrite.Member();
        chromatogramProducts.schema = schema;
        addProducts(chromatogramProducts, 0);
        MemberRewrite.write(chromatogramProducts, copy.resolve(CHROMATOGRAM_PRODUCTS));

        Path indexFile = copy.resolve("mzpeak_index.json");
        ObjectNode index = (ObjectNode) MAPPER.readTree(Files.readAllBytes(indexFile));
        ArrayNode files = (ArrayNode) index.get("files");
        files.addObject().put("name", SPECTRUM_PRODUCTS).put("entity_type", "spectrum").put("data_kind", "products");
        files.addObject().put("name", CHROMATOGRAM_PRODUCTS).put("entity_type", "chromatogram")
                .put("data_kind", "products");
        Files.write(indexFile, MAPPER.writeValueAsBytes(index));
        return copy;
    }

    /** Adds the uint8 column {@code ms_level}, 2 in the precursor row of spectrum {@link #SPECTRUM}. */
    private static void addMsLevel(MemberRewrite.Member m) {
        List<Type> fields = new ArrayList<>(m.schema.getFields());
        fields.add(Types.optional(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, false))
                .named("ms_level"));
        MessageType schema = new MessageType(m.schema.getName(), fields);
        assertThat(m.rows.stream().filter(r -> ParquetGroups.optLong(r, "source_index") == SPECTRUM)).hasSize(1);
        List<Group> rows = new ArrayList<>();
        for (Group row : m.rows) {
            rows.add(MemberRewrite.copyRow(row, schema, null, g -> {
                if (ParquetGroups.optLong(row, "source_index") == SPECTRUM) {
                    g.add("ms_level", 2);
                }
                return g;
            }));
        }
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);
    }

    /**
     * The products schema from the precursor member's column types: {@code source_index}, {@code product_index} and
     * {@code precursor_index} as its uint64 keys, {@code isolation_window} as its struct, and {@code parameters} as
     * the struct's parameter list.
     */
    private static MessageType productSchema(MessageType precursors) {
        PrimitiveType key = precursors.getType("source_index").asPrimitiveType();
        GroupType window = precursors.getType("isolation_window").asGroupType();
        return new MessageType("products",
                key,
                Types.primitive(key.getPrimitiveTypeName(), key.getRepetition()).as(key.getLogicalTypeAnnotation())
                        .named("product_index"),
                precursors.getType("precursor_index"),
                window,
                window.getType("parameters"));
    }

    /** The two products of {@link #PRODUCTS} for {@code source}, product_index 1 first. */
    private static void addProducts(MemberRewrite.Member m, long source) {
        SimpleGroupFactory rows = new SimpleGroupFactory(m.schema);
        Group one = rows.newGroup().append("source_index", source).append("product_index", 1L)
                .append("precursor_index", 3L);
        one.addGroup("isolation_window").append("isolation_window_target", 600.0f)
                .append("isolation_window_lower_offset", 1.0f).append("isolation_window_upper_offset", 1.0f);
        Group zero = rows.newGroup().append("source_index", source).append("product_index", 0L)
                .append("precursor_index", 4L);
        zero.addGroup("isolation_window").append("isolation_window_target", 500.0f)
                .append("isolation_window_lower_offset", 1.0f).append("isolation_window_upper_offset", 1.0f);
        addParam(zero, DWELL);
        m.rows.add(one);
        m.rows.add(zero);
    }

    /** Appends {@code p}, with a float value, to the {@code parameters} list of {@code row}. */
    private static void addParam(Group row, Param p) {
        GroupType list = row.getType().getType("parameters").asGroupType();
        GroupType repeated = list.getType(0).asGroupType();
        Group element = row.addGroup("parameters").addGroup(repeated.getName())
                .addGroup(repeated.getType(0).getName());
        element.addGroup("value").append("float", (Double) p.value());
        element.append("accession", p.accession()).append("name", p.name()).append("unit", p.unit());
    }
}
