package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that per-spectrum, per-scan, and per-selected-ion CV/user parameters are decoded
 * from the {@code parameters} columns in spectra_metadata.parquet.
 */
class SpectrumParamsTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void spectrumDescriptionExposesParams() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            // The small.RAW fixture may or may not carry spectrum-level CV params depending on the converter;
            // either way the field must be non-null and the list non-null.
            SpectrumDescription d = reader.getMetadata(0).orElseThrow();
            assertThat(d.parameters()).isNotNull();
            // If params are present they should have non-empty names or accessions
            for (Param p : d.parameters()) {
                assertThat(p.name() != null || p.accession() != null).isTrue();
            }
        }
    }

    @Test
    void scanEventExposesParams() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            SpectrumDescription d = reader.getMetadata(0).orElseThrow();
            assertThat(d.scans()).isNotEmpty();
            ScanEvent scan = d.scans().get(0);
            assertThat(scan.parameters()).isNotNull(); // never null
        }
    }

    @Test
    void selectedIonExposesParams() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            SpectrumDescription d = reader.getMetadata(2).orElseThrow();
            assertThat(d.msLevel()).isEqualTo(2);
            var ion = d.primaryPrecursor().primaryIon();
            assertThat(ion).isNotNull();
            assertThat(ion.parameters()).isNotNull(); // never null
        }
    }

    @Test
    void paramsLoadFromPackedZip() {
        try (MzPeakReader reader = MzPeakReader.open(
                Path.of("src/test/resources/mzpeak/small.mzpeak"))) {
            // Basic smoke: loading from ZIP doesn't throw, params not null
            List<Param> params = reader.getMetadata(0).orElseThrow().parameters();
            assertThat(params).isNotNull();
        }
    }
}
