package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Component;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.FileMetadata.SourceFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The file metadata documents {@link FooterMetadataWriter} serializes, for what the other writer tests leave out:
 * no file metadata at all; a run without default instrument, default source file or file description, and a dataset
 * rewritten with the sentinel source file its first write declared; Param values of every type; Param lists that are
 * null; and a component of unknown type. Each is written with {@link MzPeakWriter} and read back with
 * {@link MzPeakReader}.
 *
 * <p>Oracle: legacy small.unpacked, its file metadata changed where a test says so.
 */
class FooterMetadataWriterTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SourceFile SENTINEL_SOURCE = new SourceFile("mzpeakj_source", "unknown", "file:///unknown",
            List.of());

    /**
     * Without file metadata ({@code null}) nothing is serialized, and the dataset reads back with the same file
     * metadata as one written with {@link FileMetadata#EMPTY}: no run, software or file description.
     */
    @Test
    void withoutFileMetadataNothingIsWritten(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);

        assertThat(FooterMetadataWriter.serialize(null)).isEmpty();
        assertThat(FooterMetadataWriter.serialize(FileMetadata.EMPTY)).isEmpty();
        FileMetadata none = fileMetadata(write(tmp.resolve("none.mzpeak"), small, null));
        FileMetadata empty = fileMetadata(write(tmp.resolve("empty.mzpeak"), small, FileMetadata.EMPTY));
        assertThat(none).isEqualTo(empty);
        assertThat(none.run()).isNull();
        assertThat(none.software()).isEmpty();
        assertThat(none.fileDescription()).isNull();
    }

    /**
     * A run without default instrument and default source file, and a file description without source files: the
     * run names the sentinel source file, which the file description declares, and no default instrument. Written
     * again as read back, the dataset keeps that one sentinel source file instead of declaring a second.
     */
    @Test
    void theSentinelSourceFileIsDeclaredOnceAlsoWhenTheDatasetIsRewritten(@TempDir Path tmp) throws IOException {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        FileMetadata fixture = small.fileMetadata;
        MsRun run = fixture.run();
        FileMetadata meta = new FileMetadata(new FileDescription(fixture.fileDescription().contents(), List.of()),
                fixture.instrumentConfigurations(), fixture.dataProcessingMethods(), fixture.samples(),
                fixture.software(), new MsRun(run.id(), null, run.defaultDataProcessingId(), null, run.startTime(),
                run.parameters()));

        JsonNode runJson = json(FooterMetadataWriter.serialize(meta), "run");
        assertThat(runJson.has("default_instrument_id")).isFalse();
        assertThat(runJson.path("default_source_file_id").asText()).isEqualTo("mzpeakj_source");

        FileMetadata once = fileMetadata(write(tmp.resolve("once.mzpeak"), small, meta));
        assertThat(once.run()).isEqualTo(new MsRun(run.id(), null, run.defaultDataProcessingId(), "mzpeakj_source",
                run.startTime(), run.parameters()));
        assertThat(once.fileDescription().sourceFiles()).containsExactly(SENTINEL_SOURCE);

        Map<String, String> again = FooterMetadataWriter.serialize(once);
        assertThat(json(again, "file_description").path("source_files")).hasSize(1);
        FileMetadata twice = fileMetadata(write(tmp.resolve("twice.mzpeak"), small, once));
        assertThat(twice.fileDescription().sourceFiles()).containsExactly(SENTINEL_SOURCE);
        assertThat(twice).isEqualTo(once);
    }

    /**
     * A run without default source file and file metadata without a file description: the run names the sentinel
     * source file, and no file description is written to declare it.
     */
    @Test
    void withoutFileDescriptionTheSentinelSourceFileIsNamedButNotDeclared(@TempDir Path tmp) throws IOException {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        FileMetadata fixture = small.fileMetadata;
        MsRun run = fixture.run();
        FileMetadata meta = new FileMetadata(null, fixture.instrumentConfigurations(),
                fixture.dataProcessingMethods(), fixture.samples(), fixture.software(), new MsRun(run.id(),
                run.defaultInstrumentId(), run.defaultDataProcessingId(), null, run.startTime(), run.parameters()));

        Map<String, String> kv = FooterMetadataWriter.serialize(meta);
        assertThat(kv).doesNotContainKey("file_description");
        assertThat(json(kv, "run").path("default_source_file_id").asText()).isEqualTo("mzpeakj_source");
        assertThat(json(kv, "run").path("default_instrument_id").asInt()).isEqualTo(run.defaultInstrumentId());

        FileMetadata back = fileMetadata(write(tmp.resolve("out.mzpeak"), small, meta));
        assertThat(back.fileDescription()).isNull();
        assertThat(back.run().defaultSourceFileId()).isEqualTo("mzpeakj_source");
        assertThat(back.run().defaultInstrumentId()).isEqualTo(run.defaultInstrumentId());
    }

    /**
     * Param values of every type the writer knows (Boolean, Long, Integer, Double, String, none) are written as JSON
     * values of their type, an Integer as an integral number that reads back as a Long; a null Param list is
     * written as an empty one; and a component of unknown type as {@code unknown}, which reads back as
     * {@link ComponentType#UNKNOWN}.
     */
    @Test
    void paramValuesOfEveryTypeNullParamListsAndUnknownComponentsAreWritten(@TempDir Path tmp) throws IOException {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        FileMetadata fixture = small.fileMetadata;
        MsRun run = fixture.run();
        List<Param> values = List.of(
                new Param("acquisition complete", null, Boolean.TRUE, null),
                new Param("total scans", null, 48L, null),
                new Param("ms2 scans", null, 34, null),
                new Param("source temperature", null, 120.5, "UO:0000027"),
                new Param("operator", null, "jk", null),
                new Param("ms level", "MS:1000511", null, null));
        List<Software> software = new ArrayList<>(fixture.software());
        software.add(new Software("unlisted", "0.1", null));
        List<InstrumentConfiguration> configurations = new ArrayList<>(fixture.instrumentConfigurations());
        configurations.add(new InstrumentConfiguration(2, List.of(new Component(ComponentType.UNKNOWN, 1, null)),
                null, null));
        FileMetadata meta = new FileMetadata(new FileDescription(null, fixture.fileDescription().sourceFiles()),
                configurations, fixture.dataProcessingMethods(), fixture.samples(), software, new MsRun(run.id(),
                run.defaultInstrumentId(), run.defaultDataProcessingId(), run.defaultSourceFileId(), run.startTime(),
                values));

        Map<String, String> kv = FooterMetadataWriter.serialize(meta);
        List<String> written = new ArrayList<>();
        json(kv, "run").path("parameters").forEach(p -> written.add(p.path("value").toString()));
        assertThat(written).containsExactly("true", "48", "34", "120.5", "\"jk\"", "null");
        JsonNode unknown = json(kv, "instrument_configuration_list").get(2);
        assertThat(unknown.path("components").get(0).path("component_type").asText()).isEqualTo("unknown");
        assertThat(unknown.path("components").get(0).path("parameters")).isEmpty();
        assertThat(unknown.path("parameters")).isEmpty();
        assertThat(json(kv, "file_description").path("contents")).isEmpty();

        FileMetadata back = fileMetadata(write(tmp.resolve("out.mzpeak"), small, meta));
        assertThat(back.run().parameters()).containsExactly(
                new Param("acquisition complete", null, Boolean.TRUE, null),
                new Param("total scans", null, 48L, null),
                new Param("ms2 scans", null, 34L, null),
                new Param("source temperature", null, 120.5, "UO:0000027"),
                new Param("operator", null, "jk", null),
                new Param("ms level", "MS:1000511", null, null));
        assertThat(back.software().get(back.software().size() - 1))
                .isEqualTo(new Software("unlisted", "0.1", List.of()));
        InstrumentConfiguration unknownBack = back.instrumentConfigurations().get(2);
        assertThat(unknownBack.id()).isEqualTo(2);
        assertThat(unknownBack.components()).containsExactly(new Component(ComponentType.UNKNOWN, 1, List.of()));
        assertThat(unknownBack.parameters()).isEmpty();
        assertThat(back.fileDescription().contents()).isEmpty();
        assertThat(back.fileDescription().sourceFiles()).isEqualTo(fixture.fileDescription().sourceFiles());
    }

    private static Path write(Path out, Source source, FileMetadata fileMetadata) {
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, fileMetadata);
        return out;
    }

    private static FileMetadata fileMetadata(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.fileMetadata();
        }
    }

    private static JsonNode json(Map<String, String> kv, String key) throws IOException {
        assertThat(kv).containsKey(key);
        return MAPPER.readTree(kv.get(key));
    }
}
