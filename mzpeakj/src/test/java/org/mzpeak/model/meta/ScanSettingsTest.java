package org.mzpeak.model.meta;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Param;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScanSettings}: the typed accessors on the scan settings of Example_Processed.img, and on parameters whose
 * value is text, unparseable, null or absent.
 */
class ScanSettingsTest {

    private static Param param(String accession, Object value) {
        return new Param("p", accession, value, null);
    }

    @Test
    void theFixtureSettingsHaveA3By3GridOf100UmPixels() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("Example_Processed.img.mzpeak"))) {
            ScanSettings settings = reader.scanSettings().get(0);
            assertThat(settings.maxPixelX()).contains(3);
            assertThat(settings.maxPixelY()).contains(3);
            assertThat(settings.pixelSizeX()).contains(100.0);
            assertThat(settings.pixelSizeY()).contains(100.0);
        }
    }

    @Test
    void aNullParameterListIsEmpty() {
        ScanSettings settings = new ScanSettings("s1", null);
        assertThat(settings.parameters()).isEmpty();
        assertThat(settings.maxPixelX()).isEmpty();
        assertThat(settings.maxPixelY()).isEmpty();
        assertThat(settings.pixelSizeX()).isEmpty();
        assertThat(settings.pixelSizeY()).isEmpty();
    }

    @Test
    void absentAccessionsAreEmpty() {
        ScanSettings settings = new ScanSettings("s1", List.of(param("IMS:1000044", 300L), param("IMS:1000045", 300L)));
        assertThat(settings.maxPixelX()).isEmpty();
        assertThat(settings.maxPixelY()).isEmpty();
        assertThat(settings.pixelSizeX()).isEmpty();
        assertThat(settings.pixelSizeY()).isEmpty();
    }

    @Test
    void textValuesAreParsed() {
        ScanSettings settings = new ScanSettings("s1", List.of(
                param("IMS:1000042", "120"), param("IMS:1000043", "80"),
                param("IMS:1000046", "12.5"), param("IMS:1000047", "25")));
        assertThat(settings.maxPixelX()).contains(120);
        assertThat(settings.maxPixelY()).contains(80);
        assertThat(settings.pixelSizeX()).contains(12.5);
        assertThat(settings.pixelSizeY()).contains(25.0);
    }

    @Test
    void numericValuesAreConverted() {
        ScanSettings settings = new ScanSettings("s1", List.of(
                param("IMS:1000042", 120.9), param("IMS:1000046", 12L)));
        assertThat(settings.maxPixelX()).contains(120);
        assertThat(settings.pixelSizeX()).contains(12.0);
    }

    @Test
    void nullAndUnparseableValuesAreSkippedForALaterOne() {
        ScanSettings settings = new ScanSettings("s1", Arrays.asList(
                param("IMS:1000042", null), param("IMS:1000042", "many"), param("IMS:1000042", "1.5"),
                param("IMS:1000042", 7L),
                param("IMS:1000046", null), param("IMS:1000046", "wide"), param("IMS:1000046", 2.5)));
        assertThat(settings.maxPixelX()).contains(7);
        assertThat(settings.pixelSizeX()).contains(2.5);
    }

    @Test
    void onlyNullAndUnparseableValuesAreEmpty() {
        ScanSettings settings = new ScanSettings("s1", List.of(
                param("IMS:1000043", "many"), param("IMS:1000047", "wide")));
        assertThat(settings.maxPixelY()).isEmpty();
        assertThat(settings.pixelSizeY()).isEmpty();
        ScanSettings nulls = new ScanSettings("s1", Arrays.asList(
                param("IMS:1000043", null), param("IMS:1000047", null)));
        assertThat(nulls.maxPixelY()).isEmpty();
        assertThat(nulls.pixelSizeY()).isEmpty();
    }
}
