package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.SourceFile;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileMetadataTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.mzpeak");

    @Test
    void readsFooterMetadata() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            FileMetadata m = reader.fileMetadata();

            assertThat(m.run().id()).isEqualTo("small");
            assertThat(m.run().defaultInstrumentId()).isEqualTo(0);
            assertThat(m.run().startTime()).isEqualTo("2005-07-20T19:44:22Z");

            assertThat(m.software()).extracting(FileMetadata.Software::displayName)
                    .contains("Xcalibur", "ProteoWizard software");

            assertThat(m.instrumentConfigurations()).hasSize(2);
            var analyzer = m.instrumentConfigurations().get(0).components().stream()
                    .filter(c -> c.componentType() == ComponentType.ANALYZER)
                    .findFirst().orElseThrow();
            assertThat(analyzer.parameters()).anyMatch(p -> p.name() != null && p.name().contains("cyclotron"));

            assertThat(m.fileDescription().sourceFiles()).extracting(SourceFile::name).contains("small.RAW");
        }
    }

    @Test
    void lenientOnEmptyFooter() {
        FileMetadata m = FooterMetadataReader.parse(Map.of());
        assertThat(m.run()).isNull();
        assertThat(m.software()).isEmpty();
        assertThat(m.instrumentConfigurations()).isEmpty();
    }
}
