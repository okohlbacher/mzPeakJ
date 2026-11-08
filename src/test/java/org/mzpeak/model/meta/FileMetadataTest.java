package org.mzpeak.model.meta;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Param;
import org.mzpeak.model.meta.FileMetadata.ComponentType;
import org.mzpeak.model.meta.FileMetadata.Software;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The helpers of {@link FileMetadata}: {@link ComponentType#parse(String)}, {@link Software#displayName()} and
 * {@link FileMetadata#param(List, String)}, on the file metadata of small and on constructed values. (The reader's
 * file metadata is tested in {@code org.mzpeak.io.FileMetadataTest}.)
 */
class FileMetadataTest {

    @Test
    void parseMapsEverySpellingCaseInsensitively() {
        assertThat(ComponentType.parse("ionsource")).isEqualTo(ComponentType.ION_SOURCE);
        assertThat(ComponentType.parse("ion_source")).isEqualTo(ComponentType.ION_SOURCE);
        assertThat(ComponentType.parse("source")).isEqualTo(ComponentType.ION_SOURCE);
        assertThat(ComponentType.parse("analyzer")).isEqualTo(ComponentType.ANALYZER);
        assertThat(ComponentType.parse("mass_analyzer")).isEqualTo(ComponentType.ANALYZER);
        assertThat(ComponentType.parse("detector")).isEqualTo(ComponentType.DETECTOR);
        assertThat(ComponentType.parse("Analyzer")).isEqualTo(ComponentType.ANALYZER);
        assertThat(ComponentType.parse("DETECTOR")).isEqualTo(ComponentType.DETECTOR);
    }

    @Test
    void parseOfAnUnknownOrNullTypeIsUnknown() {
        assertThat(ComponentType.parse("inlet")).isEqualTo(ComponentType.UNKNOWN);
        assertThat(ComponentType.parse(" detector")).isEqualTo(ComponentType.UNKNOWN);
        assertThat(ComponentType.parse("")).isEqualTo(ComponentType.UNKNOWN);
        assertThat(ComponentType.parse(null)).isEqualTo(ComponentType.UNKNOWN);
    }

    @Test
    void theFixtureSoftwareIsNamedByItsFirstParam() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            List<Software> software = reader.fileMetadata().software();
            assertThat(software).extracting(Software::id)
                    .containsExactly("Xcalibur", "pwiz", "mzpeak_prototyping_convert1");
            assertThat(software).extracting(Software::displayName)
                    .containsExactly("Xcalibur", "ProteoWizard software", "custom unreleased software tool");
        }
    }

    @Test
    void displayNameSkipsUnnamedParamsAndFallsBackToTheId() {
        Param unnamed = new Param(null, "MS:1000615", null, null);
        Param blank = new Param("  ", "MS:1000532", null, null);
        Param named = new Param("ProteoWizard software", "MS:1000615", null, null);
        assertThat(new Software("pwiz", "3.0", List.of(unnamed, blank, named)).displayName())
                .isEqualTo("ProteoWizard software");
        assertThat(new Software("pwiz", "3.0", List.of(unnamed, blank)).displayName()).isEqualTo("pwiz");
        assertThat(new Software("pwiz", "3.0", List.of()).displayName()).isEqualTo("pwiz");
    }

    @Test
    void paramFindsTheFirstMatchInAFixtureComponent() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            List<Param> source = reader.fileMetadata().instrumentConfigurations().get(0).components().get(0)
                    .parameters();
            assertThat(FileMetadata.param(source, "MS:1000057"))
                    .contains(new Param("electrospray inlet", "MS:1000057", null, null));
            assertThat(FileMetadata.param(source, "MS:1000073"))
                    .contains(new Param("electrospray ionization", "MS:1000073", null, null));
            assertThat(FileMetadata.param(source, "MS:1000011")).isEmpty();
        }
    }

    @Test
    void paramOfANullListIsEmpty() {
        Param first = new Param("resolution", "MS:1000011", 30000L, null);
        Param second = new Param("resolution", "MS:1000011", 60000L, null);
        assertThat(FileMetadata.param(List.of(first, second), "MS:1000011")).containsSame(first);
        assertThat(FileMetadata.param(null, "MS:1000011")).isEmpty();
    }
}
