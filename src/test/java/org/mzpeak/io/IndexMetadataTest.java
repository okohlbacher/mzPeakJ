package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Sample;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;
import org.mzpeak.model.meta.ScanSettings;

import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * File-level metadata: {@link MzPeakReader#fileMetadata()}, {@link MzPeakReader#scanSettings()} and
 * {@link MzPeakReader#isImaging()} on every fixture of both sets.
 *
 * <p>The characterization pins what the reader returns before RD-10 makes the index the first source: per fixture
 * the run as one canonical line, the list sizes, the SHA-256 of the canonical lines of the other five documents,
 * the canonical scan settings and the imaging flag. A unit that changes these on purpose updates the values and
 * says why.
 */
class IndexMetadataTest {

    /** What the reader returns for one fixture; {@code sourceFiles} is -1 without a file description. */
    record Snapshot(String run, int instruments, int software, int dataProcessing, int samples, int sourceFiles,
                    String documentsSha256, String scanSettings, boolean imaging) {
    }

    private static final String SMALL_RUN =
            "(small|null|pwiz_Reader_Thermo_conversion|RAW1|2005-07-20T19:44:22Z|[])";
    private static final String HAS_UV_RUN =
            "(TOFsulfasMS4GHzDualMode_x002b_DADSpectra_x002b_UVSignal272-NoProfile"
                    + "|null|pwiz_Reader_conversion|MSScan.bin|2007-10-20T20:12:18Z|[])";
    private static final String DIA_PASEF_RUN =
            "(test/data/diaPASEF.d|null|null|analysis.tdf|2019-08-24T18:27:02.345+02:00|[])";
    private static final String LEGACY_SMALL_RUN =
            "(small|0|pwiz_Reader_Thermo_conversion|RAW1|2005-07-20T19:44:22Z|[])";
    private static final String LEGACY_HAS_UV_RUN =
            "(TOFsulfasMS4GHzDualMode_x002b_DADSpectra_x002b_UVSignal272-NoProfile"
                    + "|0|pwiz_Reader_conversion|MSScan.bin|2007-10-20T20:12:18Z|[])";

    private static final String SMALL_DOCUMENTS = "ecdc41da453ebe9fd2e871c8cbe0d9d498d056c7a3388c93944e8c3a9513d148";
    private static final String HAS_UV_DOCUMENTS = "1daa1c8b086b6fa13abb1bc4908d97e246f781a72cd4ed34cf009e6f6b9c9216";

    /**
     * The current set (2ee2589), read from the footer. Its run spells {@code default_instrument_configuration_id},
     * so the default instrument reads {@code null}.
     */
    private static final Map<String, Snapshot> CURRENT = Map.of(
            "small.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            // small.chunked and small.numpress record their own conversion options
            "small.chunked.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1,
                    "f99c36d02951633d2bc73b0ebddbce5e3aae6d49b711f75f389a0281e70f0395", "[]", false),
            "small.numpress.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1,
                    "e93490bc836303e99d77d90aa3479de01d651143af470f6bb7dab47c6b7f2efa", "[]", false),
            "small.unpacked.mzpeak", new Snapshot(SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            "has_uv.mzpeak", new Snapshot(HAS_UV_RUN, 1, 4, 3, 0, 18, HAS_UV_DOCUMENTS, "[]", false),
            "diaPASEF.ref.mzpeak", new Snapshot(DIA_PASEF_RUN, 1, 3, 1, 1, 2,
                    "fd75bb7073bd5fc6130ccc79251bd9a1bcb458cf22d4eb69370ab802e768e584", "[]", false),
            "diaPASEF.grid.mzpeak", new Snapshot(DIA_PASEF_RUN, 1, 3, 1, 1, 2,
                    "aa491a8539970776c89ad9b12258dc32b596ac344209dbb2621848c097be3b0a", "[]", false),
            // its index metadata is {}; its scan settings exist only in the footer
            "Example_Processed.img.mzpeak", new Snapshot("(Experiment01|null|XcaliburProcessing|sf1|null|[])",
                    1, 3, 3, 1, 1, "93152bd50d139e4b142f559424a361d9e44dfeb006b508cddce07485b88e3ce9", "[]", false));

    /** The legacy set (29e59b2): footer and index hold the same documents; the run spells default_instrument_id. */
    private static final Map<String, Snapshot> LEGACY = Map.of(
            "small.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            "small.chunked.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1,
                    "856171ba4615e5c54b56640659f81db1743820303696388a929b7f98bcb47f0f", "[]", false),
            "small.numpress.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1,
                    "380fe37163ae811e44f32a99e3e2a38175e4aa799b9d38134bbac4a434c2aaf7", "[]", false),
            "small.unpacked.mzpeak", new Snapshot(LEGACY_SMALL_RUN, 2, 3, 2, 1, 1, SMALL_DOCUMENTS, "[]", false),
            "has_uv.mzpeak", new Snapshot(LEGACY_HAS_UV_RUN, 1, 4, 3, 0, 18, HAS_UV_DOCUMENTS, "[]", false));

    static Stream<Arguments> allFixtures() {
        return Stream.concat(
                Fixtures.CURRENT_NAMES.stream().map(n -> Arguments.of("current", n, Fixtures.current(n))),
                Fixtures.LEGACY_NAMES.stream().map(n -> Arguments.of("legacy", n, Fixtures.legacy(n))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("allFixtures")
    void fileMetadataMatchesSnapshot(String set, String fixture, Path dataset) {
        Snapshot expected = ("current".equals(set) ? CURRENT : LEGACY).get(fixture);
        assertThat(expected).as("snapshot for %s %s", set, fixture).isNotNull();
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(snapshot(reader)).as("file metadata of %s %s", set, fixture).isEqualTo(expected);
        }
    }

    @Test
    void currentSmallUnpackedAnchorLines() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.unpacked.mzpeak"))) {
            List<String> documents = documents(reader.fileMetadata());
            assertThat(documents.get(3)).isEqualTo("sample_list=[(_x0031_||[(sample name|MS:1000002|Long:1|null)])]");
            assertThat(documents.get(4)).isEqualTo("software_list=["
                    + "(Xcalibur|1.1 Beta 7|[(Xcalibur|MS:1000532|null|null)]);"
                    + "(pwiz|3.0.23307|[(ProteoWizard software|MS:1000615|null|null)]);"
                    + "(mzpeak_prototyping_convert1|0.1.0|[(custom unreleased software tool|MS:1000799"
                    + "|String:mzpeak_prototyping_convert|null)])]");
        }
    }

    /** Canonical writes components by name; this fails when a model record gains one it does not write. */
    @Test
    void canonicalFormCoversEveryRecordComponent() {
        assertThat(components(FileMetadata.class)).containsExactly("fileDescription", "instrumentConfigurations",
                "dataProcessingMethods", "samples", "software", "run");
        assertThat(components(FileDescription.class)).containsExactly("contents", "sourceFiles");
        assertThat(components(SourceFile.class)).containsExactly("id", "name", "location", "parameters");
        assertThat(components(InstrumentConfiguration.class)).containsExactly("id", "components",
                "softwareReference", "parameters");
        assertThat(components(Component.class)).containsExactly("componentType", "order", "parameters");
        assertThat(components(DataProcessing.class)).containsExactly("id", "methods");
        assertThat(components(ProcessingMethod.class)).containsExactly("order", "softwareReference", "parameters");
        assertThat(components(Sample.class)).containsExactly("id", "name", "parameters");
        assertThat(components(Software.class)).containsExactly("id", "version", "parameters");
        assertThat(components(MsRun.class)).containsExactly("id", "defaultInstrumentId", "defaultDataProcessingId",
                "defaultSourceFileId", "startTime", "parameters");
        assertThat(components(ScanSettings.class)).containsExactly("id", "parameters");
    }

    // ---- canonical form ---------------------------------------------------------------------------

    static Snapshot snapshot(MzPeakReader reader) {
        FileMetadata m = reader.fileMetadata();
        return new Snapshot(
                run(m.run()),
                m.instrumentConfigurations().size(),
                m.software().size(),
                m.dataProcessingMethods().size(),
                m.samples().size(),
                m.fileDescription() == null ? -1 : m.fileDescription().sourceFiles().size(),
                Canonical.sha256(documents(m)),
                list(reader.scanSettings(), IndexMetadataTest::scanSettings),
                reader.isImaging());
    }

    /** {@code (id|defaultInstrumentId|defaultDataProcessingId|defaultSourceFileId|startTime|[params])}. */
    static String run(MsRun r) {
        if (r == null) {
            return "null";
        }
        return "(" + r.id() + "|" + r.defaultInstrumentId() + "|" + r.defaultDataProcessingId()
                + "|" + r.defaultSourceFileId() + "|" + r.startTime() + "|" + params(r.parameters()) + ")";
    }

    /** One line per document other than the run, in {@link FileMetadata} component order. */
    static List<String> documents(FileMetadata m) {
        return List.of(
                "file_description=" + fileDescription(m.fileDescription()),
                "instrument_configuration_list=" + list(m.instrumentConfigurations(), IndexMetadataTest::instrument),
                "data_processing_method_list=" + list(m.dataProcessingMethods(), IndexMetadataTest::dataProcessing),
                "sample_list=" + list(m.samples(), s -> "(" + s.id() + "|" + s.name() + "|" + params(s.parameters())
                        + ")"),
                "software_list=" + list(m.software(), s -> "(" + s.id() + "|" + s.version()
                        + "|" + params(s.parameters()) + ")"));
    }

    static String fileDescription(FileDescription d) {
        if (d == null) {
            return "null";
        }
        return "(" + params(d.contents()) + "|" + list(d.sourceFiles(), f -> "(" + f.id() + "|" + f.name()
                + "|" + f.location() + "|" + params(f.parameters()) + ")") + ")";
    }

    static String instrument(InstrumentConfiguration ic) {
        return "(" + ic.id() + "|" + list(ic.components(), c -> "(" + c.componentType() + "|" + c.order()
                + "|" + params(c.parameters()) + ")") + "|" + ic.softwareReference() + "|" + params(ic.parameters())
                + ")";
    }

    static String dataProcessing(DataProcessing dp) {
        return "(" + dp.id() + "|" + list(dp.methods(), pm -> "(" + pm.order() + "|" + pm.softwareReference()
                + "|" + params(pm.parameters()) + ")") + ")";
    }

    static String scanSettings(ScanSettings s) {
        return "(" + s.id() + "|" + params(s.parameters()) + ")";
    }

    static String params(List<Param> params) {
        return list(params, Canonical::param);
    }

    static <T> String list(List<T> items, Function<T, String> describe) {
        return items.stream().map(describe).collect(Collectors.joining(";", "[", "]"));
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
