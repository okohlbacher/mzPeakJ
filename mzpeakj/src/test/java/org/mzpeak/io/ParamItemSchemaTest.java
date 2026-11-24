package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Type.Repetition;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.AuxiliaryArray;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The repetition of the {@code parameters} item fields and the {@code auxiliary_arrays} item fields mzPeakJ writes
 * (W14, D13-27; docs/layouts/metadata-tables.md §The parameters list, docs/layouts/auxiliary-arrays.md), and the
 * params a reader gets back from the written files.
 *
 * <p>Fixtures: current small, has_uv and diaPASEF.ref as read ({@link ForwardBackwardCorpusTest#readSource}), spectrum
 * 0 given {@link AuxiliaryArrayWriteTest#PLAIN} and {@link #WITH_PARAMS}, an array with a unit and two params, so
 * every written member has auxiliary arrays to read back.
 */
class ParamItemSchemaTest {

    /** An auxiliary array with a unit, a named user param and an accession-only param. */
    private static final AuxiliaryArray WITH_PARAMS = new AuxiliaryArray(
            new Param("non-standard data array", "MS:1000786", "params array", null),
            AuxiliaryArrayWriteTest.PLAIN.data(), "MS:1000523", "MS:1000576", 3, "UO:0000186",
            List.of(new Param("comment", null, "note", null), new Param(null, "MS:1000517", null, null)), null);

    /**
     * SHA-256 of {@link #params} of each fixture's split output as read back, pinned at agent-team/v013d (e8cead6),
     * before the item fields became required.
     */
    private static final Map<String, String> READ_BACK = Map.of(
            "small.mzpeak", "3fdd2bb69c433be49a6f099c0c99e3c9029620c184ab752c9fa0d359ffe6f885",
            "has_uv.mzpeak", "9b1e0c2d5395812b2ac6f78230642143f448f58cadd7acbec8cf2cd249090d6c",
            "diaPASEF.ref.mzpeak", "2dbf7135b2bb3d1dcc5e575bdc2b9d542580dd8b9489c3a173858f0bf1ac9b65");

    @TempDir
    Path tmp;

    /**
     * Split output: the item group, its {@code value} group and its {@code name} of every {@code parameters} list of a
     * metadata table are REQUIRED (metadata-tables.md, W14); those of the {@code parameters} list inside an auxiliary
     * array stay OPTIONAL, as auxiliary-arrays.md gives them (D13-27). Until X-37 every one was OPTIONAL.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak"})
    void splitParameterItemFields(String fixture) throws IOException {
        Path out = write(fixture, MetadataLayout.SPLIT);
        List<String> lists = new ArrayList<>();
        for (Path member : members(out)) {
            for (Type field : ParquetFooter.read(new LocalInputFile(member)).schema().getFields()) {
                collectParameterLists(member.getFileName() + ":" + field.getName(), field, false, lists);
            }
        }
        assertThat(lists).isNotEmpty();
        assertThat(lists).anyMatch(l -> l.contains("auxiliary_arrays"));
        assertThat(lists).anyMatch(l -> !l.contains("auxiliary_arrays"));
        for (String list : lists) {
            assertThat(list).endsWith(list.contains("(auxiliary_arrays)")
                    ? "item=OPTIONAL value=OPTIONAL name=OPTIONAL"
                    : "item=REQUIRED value=REQUIRED name=REQUIRED");
        }
    }

    /**
     * Split output: the auxiliary-array item's {@code data} list (U5), {@code name} group, its {@code name},
     * {@code data_type}, {@code compression} and {@code items} (UINT64) are REQUIRED in every member with auxiliary
     * arrays (auxiliary-arrays.md, W14), the name's {@code value} group stays OPTIONAL. Until X-37 they were OPTIONAL,
     * {@code data} until U5.
     */
    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak"})
    void splitAuxiliaryArrayItemFields(String fixture) throws IOException {
        Path out = write(fixture, MetadataLayout.SPLIT);
        int found = 0;
        for (Path member : members(out)) {
            GroupType schema = ParquetFooter.read(new LocalInputFile(member)).schema();
            if (!schema.containsField("auxiliary_arrays")) {
                continue;
            }
            found++;
            GroupType item = schema.getType("auxiliary_arrays").asGroupType().getType(0).asGroupType()
                    .getType(0).asGroupType();
            String where = member.getFileName().toString();
            assertThat(item.getType("data").getRepetition()).as(where).isEqualTo(Repetition.REQUIRED);
            assertThat(item.getType("data").getLogicalTypeAnnotation()).as(where)
                    .isEqualTo(LogicalTypeAnnotation.listType());
            assertThat(item.getType("name").getRepetition()).as(where).isEqualTo(Repetition.REQUIRED);
            assertThat(item.getType("name").asGroupType().getType("name").getRepetition()).as(where)
                    .isEqualTo(Repetition.REQUIRED);
            assertThat(item.getType("name").asGroupType().getType("value").getRepetition()).as(where)
                    .isEqualTo(Repetition.OPTIONAL);
            assertThat(item.getType("data_type").getRepetition()).as(where).isEqualTo(Repetition.REQUIRED);
            assertThat(item.getType("compression").getRepetition()).as(where).isEqualTo(Repetition.REQUIRED);
            assertThat(item.getType("items").getRepetition()).as(where).isEqualTo(Repetition.REQUIRED);
            assertThat(item.getType("items").getLogicalTypeAnnotation()).as(where)
                    .isEqualTo(LogicalTypeAnnotation.intType(64, false));
        }
        assertThat(found).isPositive();
    }

    /** PACKED output: every {@code parameters} list keeps the optional item fields (frozen dialect, D13-11). */
    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak"})
    void packedParameterItemFieldsStayOptional(String fixture) throws IOException {
        Path out = write(fixture, MetadataLayout.PACKED);
        List<String> lists = new ArrayList<>();
        for (Path member : members(out)) {
            for (Type field : ParquetFooter.read(new LocalInputFile(member)).schema().getFields()) {
                collectParameterLists(member.getFileName() + ":" + field.getName(), field, false, lists);
            }
        }
        assertThat(lists).isNotEmpty();
        for (String list : lists) {
            assertThat(list).endsWith("item=OPTIONAL value=OPTIONAL name=OPTIONAL");
        }
    }

    /** The params of the split output as read back are those pinned before the item fields became required. */
    @ParameterizedTest
    @ValueSource(strings = {"small.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak"})
    void splitReadBackParams(String fixture) throws IOException {
        Path out = write(fixture, MetadataLayout.SPLIT);
        String text;
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            text = params(reader);
        }
        assertThat(text).contains("params array", "note", "signal to noise array");
        assertThat(sha256(text)).as(fixture).isEqualTo(READ_BACK.get(fixture));
    }

    /**
     * The split writer's guard of the required item {@code name}: a Param with neither name nor accession, which the
     * planner refuses before anything is written, is refused with a message naming the entity by its metadata row;
     * a Param with only an accession gets a name, one with a name is kept.
     */
    @Test
    void theSplitWriterRefusesAParameterItemWithoutAName() {
        Group row = new SimpleGroupFactory(Types.buildMessage()
                .required(PrimitiveTypeName.INT64).as(ParquetOutput.UINT64).named("source_index")
                .named("spectra_metadata_precursors")).newGroup();
        row.add("source_index", 7L);
        Param nameless = new Param(null, null, 1.5, null);
        assertThatThrownBy(() -> SplitMetadataWriter.named(nameless, row)).isInstanceOf(MzPeakException.class)
                .hasMessage("spectra_metadata_precursors source_index 7: a parameter has neither a name nor an"
                        + " accession, but the name of a parameters item is a required field: " + nameless);
        assertThat(SplitMetadataWriter.named(new Param(null, "MS:1000786", "x", null), row))
                .isEqualTo(new Param("MS:1000786", "MS:1000786", "x", null));
        Param named = new Param("comment", null, "note", null);
        assertThat(SplitMetadataWriter.named(named, row)).isSameAs(named);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** {@code fixture} as read, spectrum 0 given two auxiliary arrays, written with {@code layout}. */
    private Path write(String fixture, MetadataLayout layout) {
        Source source = AuxiliaryArrayWriteTest.withSpectrumArrays(Fixtures.current(fixture),
                List.of(AuxiliaryArrayWriteTest.PLAIN, WITH_PARAMS));
        Path out = tmp.resolve(layout + "-" + fixture);
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, Options.defaults().withMetadataLayout(layout));
        return out;
    }

    private static List<Path> members(Path out) throws IOException {
        try (Stream<Path> files = Files.list(out)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".parquet")).sorted().toList();
        }
    }

    /**
     * Adds to {@code out}, for every {@code parameters} list at or below {@code t}, its path and the repetition of its
     * item group, the item's {@code value} group and its {@code name}.
     */
    private static void collectParameterLists(String path, Type t, boolean inAuxiliary, List<String> out) {
        if (t.isPrimitive()) {
            return;
        }
        GroupType g = t.asGroupType();
        if (g.getName().equals("parameters")
                && g.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
            GroupType item = g.getType(0).asGroupType().getType(0).asGroupType();
            out.add(path + (inAuxiliary ? " (auxiliary_arrays)" : "") + " item=" + item.getRepetition()
                    + " value=" + item.getType("value").getRepetition()
                    + " name=" + item.getType("name").getRepetition());
            return;
        }
        for (Type child : g.getFields()) {
            collectParameterLists(path + "." + child.getName(), child,
                    inAuxiliary || g.getName().equals("auxiliary_arrays"), out);
        }
    }

    /**
     * Every param the reader gives for the archive, one line per list: those of each spectrum, its scans, its
     * precursors' isolation windows, activations and selected ions and its auxiliary arrays' names and params, then
     * the same of each chromatogram and wavelength spectrum.
     */
    private static String params(MzPeakReader reader) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < reader.size(); i++) {
            Spectrum s = reader.getSpectrumAt(i);
            out.append("spectrum ").append(i).append(' ').append(s.description().parameters()).append('\n');
            for (ScanEvent scan : s.description().scans()) {
                out.append(" scan ").append(scan.parameters()).append('\n');
            }
            precursors(s.description().precursors(), out);
            auxiliary(s.auxiliaryArrays(), out);
        }
        for (Chromatogram c : reader.chromatograms()) {
            out.append("chromatogram ").append(c.index()).append(' ').append(c.parameters()).append('\n');
            precursors(c.precursors(), out);
            auxiliary(c.auxiliaryArrays(), out);
        }
        for (WavelengthSpectrum w : reader.wavelengthSpectra()) {
            out.append("wavelength ").append(w.index()).append(' ').append(w.parameters()).append('\n');
            for (ScanEvent scan : w.scans()) {
                out.append(" scan ").append(scan.parameters()).append('\n');
            }
            auxiliary(w.auxiliaryArrays(), out);
        }
        return out.toString();
    }

    private static void precursors(List<Precursor> precursors, StringBuilder out) {
        for (Precursor p : precursors) {
            if (p.isolationWindow() != null) {
                out.append(" isolation ").append(p.isolationWindow().parameters()).append('\n');
            }
            out.append(" activation ").append(p.activation().parameters()).append('\n');
            for (SelectedIon ion : p.selectedIons()) {
                out.append(" ion ").append(ion.parameters()).append('\n');
            }
        }
    }

    private static void auxiliary(List<AuxiliaryArray> arrays, StringBuilder out) {
        for (AuxiliaryArray a : arrays) {
            out.append(" auxiliary ").append(a.name()).append(' ').append(a.unit()).append(' ')
                    .append(a.parameters()).append('\n');
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
