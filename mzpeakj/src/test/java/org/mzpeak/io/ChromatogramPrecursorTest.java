package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-6: chromatogram precursors and selected ions, read and written. An SRM chromatogram keeps its Q1: the precursor
 * isolation window, the activation and the selected ion. The split writer used to write the members
 * chromatograms_metadata_precursors and chromatograms_metadata_selected_ions without rows, with one warning, and the
 * reader did not decode them. Now the split writer fills them with the code that writes the spectrum facets, and the
 * reader joins their rows by the key {@code (source_index, precursor_index)} as it does those of spectra. A
 * chromatogram precursor_index names a chromatogram (spec HEAD chromatograms.md:75-80; Rust visitor.rs:3108-3122), so
 * it is mapped through the chromatogram numbering, null when it names no chromatogram written, and the attribution
 * check of WR-8 applies to chromatograms. The packed layout writes no chromatogram precursor and warns once.
 * Chromatogram products are not modelled (design §5.8, Q10, B-27): no products member is written. The cv-list prefix
 * scan covers the precursors' isolation-window, activation and selected-ion params (design §7).
 *
 * <p>Oracles: the vendored HUPO-PSI fixtures (has_uv's TIC signal as the SRM chromatogram, small's first spectrum
 * for the small writes), constructed precursors, the raw cells of the written members and constructed packed
 * tables in the pin-era column names of legacy small.unpacked.
 */
class ChromatogramPrecursorTest {

    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String PRECURSORS = "chromatograms_metadata_precursors.parquet";
    private static final String SELECTED_IONS = "chromatograms_metadata_selected_ions.parquet";
    private static final String SPECTRUM_PRECURSORS = "spectra_metadata_precursors.parquet";
    private static final String METADATA = "chromatograms_metadata.parquet";
    /** MS:1000627 selected ion current chromatogram. */
    private static final String SIC_TYPE = "MS:1000627";
    private static final Param CID = new Param("collision-induced dissociation", "MS:1000133", null, null);
    private static final SelectedIon ION = new SelectedIon(445.34, 2, 120053.0, List.of());
    /** (a)'s first precursor: no precursor_index, a precursor_id, a target without offsets, CID and the ion. */
    private static final Precursor Q1 = new Precursor(null, "scan=19", new IsolationWindow(456.7, null, null),
            List.of(ION), new Activation(List.of(CID)));
    /** (a)'s second precursor: no precursor_index, no precursor_id, a target, no activation and no selected ion. */
    private static final Precursor Q1_SECOND = new Precursor(null, null, new IsolationWindow(445.3, null, null),
            List.of(), Activation.EMPTY);
    /** Part of every warning about the chromatogram precursors. */
    private static final String PRECURSOR_WARNING = "precursor";
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tmp;

    // ---- (a) and (g): the SRM chromatogram ----------------------------------------------------------------------

    /**
     * Has_uv's spectra, wavelength spectra, scan settings and file metadata with (a)'s SRM chromatogram as their only
     * chromatogram, written to {@code archive} in the split layout; also the archive {@link UpstreamInteropTest}
     * gives the HEAD Python reader.
     *
     * @return the chromatogram written
     */
    static Chromatogram writeSrmArchive(Path archive) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Chromatogram sic = sic(source.chromatograms.get(0), List.of(Q1, Q1_SECOND));
        MzPeakWriter.writeArchive(archive, source.spectra, List.of(sic), source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);
        return sic;
    }

    /**
     * (a): the SRM chromatogram 'sic' with has_uv's TIC signal and two precursors, both without precursor_index, the
     * first with a precursor_id, CID and the selected ion: a split archive reads back with precursors equal by record
     * equality, isolation windows and selected ions with their params included, and the write gives no warning about
     * them. The precursor member holds one row per precursor, the selected-ion member one row, keyed by the
     * chromatogram's index; no products member is written.
     */
    @Test
    void aSplitWriteKeepsTheSrmPrecursors() {
        Path archive = tmp.resolve("srm.mzpeak");
        List<String> warnings = new ArrayList<>();
        Chromatogram[] sic = new Chromatogram[1];
        ArrayIndexTest.captureLog(MzPeakWriter.class, () -> sic[0] = writeSrmArchive(archive)).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .filter(m -> m.contains(PRECURSOR_WARNING)).forEach(warnings::add);
        assertThat(warnings).isEmpty();

        List<Chromatogram> back = chromatograms(archive);
        assertThat(back).hasSize(1);
        assertThat(back.get(0).precursors()).isEqualTo(List.of(Q1, Q1_SECOND));
        assertThat(back.get(0).precursors()).isEqualTo(sic[0].precursors());
        assertThat(back.get(0).id()).isEqualTo("sic");
        assertThat(back.get(0).typeCurie()).isEqualTo(SIC_TYPE);
        assertThat(back.get(0).time()).isEqualTo(sic[0].time());
        assertThat(back.get(0).intensity()).isEqualTo(sic[0].intensity());

        try (MzPeakSource source = MzPeakSource.open(archive)) {
            List<Group> precursors = rows(source, PRECURSORS);
            assertThat(precursors).hasSize(2);
            assertThat(precursors).extracting(r -> ParquetGroups.optLong(r, "source_index")).containsExactly(0L, 0L);
            assertThat(precursors).extracting(r -> ParquetGroups.optLong(r, "precursor_index"))
                    .containsExactly(null, null);
            assertThat(precursors).extracting(r -> ParquetGroups.optString(r, "precursor_id"))
                    .containsExactly("scan=19", null);
            assertThat(precursors).extracting(r -> ParquetGroups.optDouble(
                    ParquetGroups.optGroup(r, "isolation_window"), "isolation_window_target"))
                    .containsExactly(456.7, 445.3);
            assertThat(precursors).extracting(r -> ParquetGroups.optString(
                    ParquetGroups.optGroup(r, "activation"), "dissociation_method"))
                    .containsExactly("MS:1000133", null);
            List<Group> ions = rows(source, SELECTED_IONS);
            assertThat(ions).hasSize(1);
            Group ion = ions.get(0);
            assertThat(ParquetGroups.optLong(ion, "source_index")).isZero();
            assertThat(ParquetGroups.optLong(ion, "precursor_index")).isNull();
            assertThat(ParquetGroups.optLong(ion, "selected_ion_index")).isZero();
            assertThat(ParquetGroups.optDouble(ion, "selected_ion_mz")).isEqualTo(445.34);
            assertThat(ParquetGroups.optInt(ion, "charge_state")).isEqualTo(2);
            assertThat(ParquetGroups.optDouble(ion, "peak_intensity")).isEqualTo(120053.0);
            assertThat(dataKinds(source)).doesNotContain("products");
        }
    }

    /**
     * Params of every scope round-trip on a chromatogram precursor: an isolation-window user param, a collision
     * energy promoted to its column, an activation user param and a selected-ion cv param stay where they were.
     */
    @Test
    void theParamsOfEveryScopeRoundTrip() {
        Param isolationParam = new Param("ms level", null, 1L, null);
        Param energy = new Param("collision energy", "MS:1000045", 25.0, "UO:0000266");
        Param activationParam = new Param("note", null, "q2", null);
        Param ionParam = new Param("peak intensity rank", "MS:1003391", 1L, null);
        Precursor precursor = new Precursor(null, "scan=3", new IsolationWindow(500.0, 0.5, 0.75,
                List.of(isolationParam)), List.of(new SelectedIon(500.1, null, null, List.of(ionParam))),
                new Activation(List.of(CID, energy, activationParam)));
        Path out = tmp.resolve("params.mzpeak");
        writeSmall(out, List.of(chromatogram(0, "srm", List.of(precursor))), SPLIT);

        assertThat(chromatograms(out).get(0).precursors()).containsExactly(precursor);
        Group row = rows(out.resolve(PRECURSORS)).get(0);
        assertThat(ParquetGroups.optDouble(ParquetGroups.optGroup(row, "activation"), "collision_energy"))
                .isEqualTo(25.0);
    }

    // ---- (b) the attribution check -----------------------------------------------------------------------------

    /**
     * (b): (a)'s chromatogram with the selected ion moved to its second precursor, which shares the key
     * {@code (source_index 0, precursor_index null)} with the first: the split write is refused, naming the
     * chromatogram, before any file or directory exists. The packed layout writes no chromatogram precursor and is
     * not checked.
     */
    @Test
    void aSelectedIonThatReadersWouldAttachToAnotherPrecursorIsRefused() throws IOException {
        Precursor first = new Precursor(Q1.precursorIndex(), Q1.precursorId(), Q1.isolationWindow(), List.of(),
                Q1.activation());
        Precursor second = new Precursor(Q1_SECOND.precursorIndex(), Q1_SECOND.precursorId(),
                Q1_SECOND.isolationWindow(), List.of(ION), Q1_SECOND.activation());
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV));
        Chromatogram moved = sic(source.chromatograms.get(0), List.of(first, second));
        Path out = tmp.resolve("refused.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, source.spectra, List.of(moved),
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram 0: precursors 0 (precursor_id 'scan=19') and 1 (no precursor_id) share the"
                        + " written key (source_index 0, precursor_index null), so readers would attach the selected"
                        + " ions of precursor 1 to precursor 0; give each precursor of the chromatogram a"
                        + " precursor_index of its own");
        assertThat(out).doesNotExist();
        try (Stream<Path> written = Files.list(tmp)) {
            assertThat(written).isEmpty();
        }

        Path packed = tmp.resolve("packed.mzpeak");
        writeSmall(packed, List.of(chromatogram(0, "sic", List.of(first, second))), PACKED);
        assertThat(chromatograms(packed).get(0).precursors()).isEmpty();
    }

    /**
     * Two precursors whose parents, chromatograms 7 and 8, are not written both get precursor_index null and carry
     * selected ions: the remedy is to write the parent chromatograms. Chromatogram 9 is written as 0, which the
     * message gives as the source_index.
     */
    @Test
    void precursorsThatLoseTheirParentsNameTheParentChromatograms() {
        Precursor seven = new Precursor(7L, "x", null, List.of(ION), null);
        Precursor eight = new Precursor(8L, "y", null, List.of(ION), null);
        assertThatThrownBy(() -> WritePlanner.plan(List.of(), List.of(chromatogram(9, "b", List.of(seven, eight))),
                List.of(), List.of(), FileMetadata.EMPTY, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram 9: precursors 0 (precursor_id 'x') and 1 (precursor_id 'y') share the"
                        + " written key (source_index 0, precursor_index null), so readers would attach the selected"
                        + " ions of precursor 1 to precursor 0; write their parent chromatograms (index 7 and 8) as"
                        + " well, so that each precursor keeps a precursor_index of its own");
    }

    /**
     * A shared key whose later precursor carries no selected ion loses nothing and is written: (a) itself. A negative
     * chromatogram precursor_index is refused in the split layout, as a spectrum's is (uint64); the packed layout
     * writes no chromatogram precursor and does not check it.
     */
    @Test
    void aNegativeChromatogramPrecursorIndexIsRefusedInTheSplitLayout() {
        Chromatogram negative = chromatogram(0, "a", List.of(new Precursor(-1L, null, null, List.of(), null)));
        assertThatThrownBy(() -> WritePlanner.plan(List.of(), List.of(negative), List.of(), List.of(),
                FileMetadata.EMPTY, SPLIT))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("precursor_index must be non-negative (mzPeak indices are uint64): -1");
        assertThat(WritePlanner.plan(List.of(), List.of(negative), List.of(), List.of(), FileMetadata.EMPTY, PACKED)
                .chromatograms()).hasSize(1);
        assertThat(WritePlanner.plan(List.of(), List.of(chromatogram(0, "sic", List.of(Q1, Q1_SECOND))), List.of(),
                List.of(), FileMetadata.EMPTY, SPLIT).chromatograms()).hasSize(1);
    }

    // ---- (c) the chromatogram foreign key ----------------------------------------------------------------------

    /**
     * (c): chromatograms 5 ('a') and 9 ('b'), written as 0 and 1. A precursor of 'b' with precursor_index 5 names
     * 'a' and is written and read back as 0; one with precursor_index 7 names no chromatogram and is written and read
     * back as null. Each keeps its selected ion, which is written in key order, null first. The caller's chromatogram
     * is not changed.
     */
    @Test
    void aPrecursorIndexIsMappedThroughTheChromatogramNumbering() {
        SelectedIon fromA = new SelectedIon(300.1, 1, null, List.of());
        SelectedIon fromNone = new SelectedIon(400.2, null, 5.0, List.of());
        Precursor namesA = new Precursor(5L, "a", new IsolationWindow(300.0, 1.0, 1.0), List.of(fromA), null);
        Precursor namesNone = new Precursor(7L, null, new IsolationWindow(400.0, null, null), List.of(fromNone),
                null);
        Chromatogram a = chromatogram(5, "a", List.of());
        Chromatogram b = chromatogram(9, "b", List.of(namesA, namesNone));
        Path out = tmp.resolve("foreign-key.mzpeak");
        writeSmall(out, List.of(a, b), SPLIT);

        List<Chromatogram> back = chromatograms(out);
        assertThat(back).extracting(Chromatogram::index).containsExactly(0L, 1L);
        assertThat(back.get(0).precursors()).isEmpty();
        assertThat(back.get(1).precursors()).containsExactly(
                new Precursor(0L, "a", namesA.isolationWindow(), List.of(fromA), null),
                new Precursor(null, null, namesNone.isolationWindow(), List.of(fromNone), null));
        assertThat(b.precursors()).extracting(Precursor::precursorIndex).containsExactly(5L, 7L);

        List<Group> precursors = rows(out.resolve(PRECURSORS));
        assertThat(precursors).extracting(r -> ParquetGroups.optLong(r, "source_index")).containsExactly(1L, 1L);
        assertThat(precursors).extracting(r -> ParquetGroups.optLong(r, "precursor_index")).containsExactly(0L, null);
        List<Group> ions = rows(out.resolve(SELECTED_IONS));
        assertThat(ions).extracting(r -> ParquetGroups.optLong(r, "precursor_index")).containsExactly(null, 0L);
        assertThat(ions).extracting(r -> ParquetGroups.optLong(r, "selected_ion_index")).containsExactly(0L, 1L);
        assertThat(ions).extracting(r -> ParquetGroups.optDouble(r, "selected_ion_mz")).containsExactly(400.2, 300.1);
        // the spectrum facets are not touched: small's first spectrum is an MS1 spectrum
        assertThat(rows(out.resolve(SPECTRUM_PRECURSORS))).isEmpty();
    }

    // ---- (d) the packed layout ---------------------------------------------------------------------------------

    /** (d): a packed write of (a)'s chromatogram warns once, with the count, and reads back no precursors. */
    @Test
    void aPackedWriteWarnsOnceAndReadsBackNoPrecursors() {
        Path out = tmp.resolve("packed.mzpeak");
        List<String> warnings = ArrayIndexTest.captureLog(MzPeakWriter.class,
                        () -> writeSmall(out, List.of(chromatogram(0, "sic", List.of(Q1, Q1_SECOND))), PACKED))
                .stream().filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .filter(m -> m.contains(PRECURSOR_WARNING)).toList();
        assertThat(warnings).containsExactly("the packed metadata layout writes no polarity, data processing"
                + " reference, parameters or precursors of a chromatogram: 0 polarities, 0 data processing"
                + " references, 0 parameters and 2 precursors are not written");
        assertThat(chromatograms(out)).singleElement().satisfies(c -> assertThat(c.precursors()).isEmpty());
        assertThat(out.resolve(PRECURSORS)).doesNotExist();
    }

    // ---- (e) the cv-list prefix scan ---------------------------------------------------------------------------

    /**
     * (e): BTO, which the small write uses nowhere else, as an activation param of a chromatogram precursor in a split
     * write is declared in metadata.cv_list; so are CVs used only by an isolation-window or a selected-ion param of
     * one. A write without them declares none of them.
     */
    @Test
    void aCvUsedOnlyInAChromatogramPrecursorIsDeclared() throws IOException {
        Path plain = tmp.resolve("plain.mzpeak");
        writeSmall(plain, List.of(chromatogram(0, "sic", List.of(Q1))), SPLIT);
        assertThat(cvIds(plain)).doesNotContain("BTO", "NCIT", "PRIDE");

        Param brain = new Param("brain", "BTO:0000142", null, null);
        Param window = new Param("window", "NCIT:C25677", 1L, null);
        Param ion = new Param("ion", "PRIDE:0000001", null, null);
        Precursor precursor = new Precursor(null, "scan=19", new IsolationWindow(456.7, null, null, List.of(window)),
                List.of(new SelectedIon(445.34, 2, null, List.of(ion))), new Activation(List.of(CID, brain)));
        Path out = tmp.resolve("bto.mzpeak");
        writeSmall(out, List.of(chromatogram(0, "sic", List.of(precursor))), SPLIT);

        assertThat(cvIds(out)).contains("BTO", "NCIT", "PRIDE");
        assertThat(WriterCvListTest.assertEveryWrittenPrefixIsDeclared(out)).containsEntry("BTO", "BTO:0000142");
        assertThat(chromatograms(out).get(0).precursors()).containsExactly(precursor);
    }

    // ---- (f) the fixtures --------------------------------------------------------------------------------------

    /**
     * (f): the chromatograms of the current fixtures have no precursors, so a split write of each leaves the
     * chromatogram precursor and selected-ion members without rows, as {@link WriterSnapshotTest} pins them, and
     * reads back without precursors. No layout writes a products member.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"small.unpacked.mzpeak", HAS_UV, "diaPASEF.ref.mzpeak"})
    void theFixturesWriteNoChromatogramPrecursorAndNoProducts(String fixture) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(fixture));
        assertThat(source.chromatograms).isNotEmpty().allSatisfy(c -> assertThat(c.precursors()).isEmpty());
        for (Options options : List.of(SPLIT, PACKED)) {
            Path out = tmp.resolve(fixture + "-" + options.metadataLayout());
            MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                    source.scanSettings, source.fileMetadata, options);
            try (MzPeakSource dataset = MzPeakSource.open(out)) {
                assertThat(dataKinds(dataset)).as("%s", out).doesNotContain("products");
            }
            assertThat(chromatograms(out)).allSatisfy(c -> assertThat(c.precursors()).isEmpty());
        }
        Path split = tmp.resolve(fixture + "-" + MetadataLayout.SPLIT);
        assertThat(rows(split.resolve(PRECURSORS))).isEmpty();
        assertThat(rows(split.resolve(SELECTED_IONS))).isEmpty();
    }

    // ---- the reader --------------------------------------------------------------------------------------------

    /**
     * A split dataset whose manifest lists the precursor member but not the selected-ion member reads the precursors
     * without their ion: a facet member the manifest does not list contributes no rows.
     */
    @Test
    void aFacetMemberTheManifestDoesNotListContributesNothing() throws IOException {
        Path out = tmp.resolve("unlisted.mzpeak");
        writeSmall(out, List.of(chromatogram(0, "sic", List.of(Q1, Q1_SECOND))), SPLIT);
        Path index = out.resolve(ManifestWriter.INDEX);
        JsonNode manifest = MAPPER.readTree(Files.readAllBytes(index));
        ArrayNode files = (ArrayNode) manifest.path("files");
        for (int i = files.size() - 1; i >= 0; i--) {
            if (SELECTED_IONS.equals(files.get(i).path("name").asText())) {
                files.remove(i);
            }
        }
        Files.write(index, MAPPER.writeValueAsBytes(manifest));
        try (MzPeakSource source = MzPeakSource.open(out)) {
            assertThat(MzPeakManifest.fromSource(source).files()).extracting(MzPeakManifest.Entry::name)
                    .contains(PRECURSORS).doesNotContain(SELECTED_IONS);
        }

        assertThat(chromatograms(out).get(0).precursors()).containsExactly(
                new Precursor(null, "scan=19", Q1.isolationWindow(), List.of(), Q1.activation()), Q1_SECOND);
    }

    /**
     * The pin-era packed layout keeps chromatogram precursors in the {@code precursor} and {@code selected_ion} structs
     * of the chromatogram member, each packed with its own {@code source_index}, in the column names of legacy
     * small.unpacked: they are read and joined as in the split layout; placeholder rows (a null struct) are skipped,
     * and the activation is its {@code parameters} list.
     */
    @Test
    void aPackedMemberGivesThePrecursorsOfItsStructs() {
        MessageType schema = Types.buildMessage()
                .addField(Types.optionalGroup()
                        .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("MS_1000626_chromatogram_type")
                        .named("chromatogram"))
                .addField(Types.optionalGroup()
                        .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                        .named("source_index")
                        .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                        .named("precursor_index")
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("precursor_id")
                        .addField(Types.optionalGroup()
                                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000827_isolation_window_target_mz")
                                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000828_isolation_window_lower_offset")
                                .optional(PrimitiveTypeName.DOUBLE).named("MS_1000829_isolation_window_upper_offset")
                                .addField(ParquetOutput.paramListField())
                                .named("isolation_window"))
                        .addField(Types.optionalGroup()
                                .addField(ParquetOutput.paramListField())
                                .named("activation"))
                        .named("precursor"))
                .addField(Types.optionalGroup()
                        .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                        .named("source_index")
                        .optional(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false))
                        .named("precursor_index")
                        .optional(PrimitiveTypeName.DOUBLE).named("MS_1000744_selected_ion_mz_unit_MS_1000040")
                        .optional(PrimitiveTypeName.INT32).named("MS_1000041_charge_state")
                        .optional(PrimitiveTypeName.DOUBLE).named("MS_1000042_intensity_unit_MS_1000131")
                        .addField(ParquetOutput.paramListField())
                        .named("selected_ion"))
                .named("arrow_schema");
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        Group first = factory.newGroup();
        first.addGroup("chromatogram").append("index", 0L).append("id", "sic")
                .append("MS_1000626_chromatogram_type", SIC_TYPE);
        Group precursor = first.addGroup("precursor").append("source_index", 0L).append("precursor_id", "scan=19");
        ParquetOutput.writeParams(precursor.addGroup("isolation_window")
                .append("MS_1000827_isolation_window_target_mz", 456.7), List.of());
        ParquetOutput.writeParams(precursor.addGroup("activation"), List.of(CID));
        ParquetOutput.writeParams(first.addGroup("selected_ion").append("source_index", 0L)
                .append("MS_1000744_selected_ion_mz_unit_MS_1000040", 445.34).append("MS_1000041_charge_state", 2)
                .append("MS_1000042_intensity_unit_MS_1000131", 120053.0), List.of());
        Group second = factory.newGroup();
        ParquetOutput.writeParams(second.addGroup("precursor").append("source_index", 0L)
                .addGroup("isolation_window").append("MS_1000827_isolation_window_target_mz", 445.3), List.of());
        Group placeholder = factory.newGroup();
        placeholder.addGroup("precursor");
        write(tmp.resolve(METADATA), schema, first, second, placeholder);

        List<Chromatogram> back = ChromatogramStore.load(new LocalInputFile(tmp.resolve(METADATA)),
                new LocalInputFile(Fixtures.legacy("small.unpacked.mzpeak").resolve("chromatograms_data.parquet")))
                .all();
        assertThat(back).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo("sic");
            assertThat(c.precursors()).containsExactly(Q1, Q1_SECOND);
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** {@code tic}'s signal as the SRM chromatogram 'sic' numbered 0, of type MS:1000627, with {@code precursors}. */
    private static Chromatogram sic(Chromatogram tic, List<Precursor> precursors) {
        return new Chromatogram(0, "sic", SIC_TYPE, tic.time(), tic.intensity(), tic.intensityUnit(), tic.arrays(),
                Polarity.UNKNOWN, null, List.of(), precursors);
    }

    /** A one-point selected ion current chromatogram numbered {@code index} with {@code precursors}. */
    private static Chromatogram chromatogram(long index, String id, List<Precursor> precursors) {
        return new Chromatogram(index, id, SIC_TYPE, new double[] {1.0}, new double[] {2.0}, null, Map.of(),
                Polarity.UNKNOWN, null, List.of(), precursors);
    }

    /** Writes {@code chromatograms} with the first spectrum of current small, an MS1 spectrum, to {@code out}. */
    private static void writeSmall(Path out, List<Chromatogram> chromatograms, Options options) {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.mzpeak"));
        MzPeakWriter.writeDirectory(out, source.spectra.subList(0, 1), chromatograms, List.of(), List.of(),
                FileMetadata.EMPTY, options);
    }

    private static List<Chromatogram> chromatograms(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.chromatograms();
        }
    }

    private static List<Group> rows(Path member) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(member, rows::add);
        return rows;
    }

    private static List<Group> rows(MzPeakSource source, String member) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(source.inputFile(member), rows::add);
        return rows;
    }

    /** The data kinds of the members the manifest of {@code source} lists. */
    private static List<String> dataKinds(MzPeakSource source) {
        List<String> kinds = new ArrayList<>();
        for (MzPeakManifest.Entry entry : MzPeakManifest.fromSource(source).files()) {
            kinds.add(entry.dataKind());
        }
        return kinds;
    }

    /** The ids of the written dataset's metadata.cv_list, in order. */
    private static List<String> cvIds(Path dataset) throws IOException {
        JsonNode index = MAPPER.readTree(Files.readAllBytes(dataset.resolve(ManifestWriter.INDEX)));
        List<String> ids = new ArrayList<>();
        for (JsonNode cv : index.path("metadata").path("cv_list")) {
            ids.add(cv.path("id").asText());
        }
        return ids;
    }

    /** Writes {@code rows} of {@code schema} to {@code file}. */
    private static void write(Path file, MessageType schema, Group... rows) {
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
