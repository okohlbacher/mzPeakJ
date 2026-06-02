package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * End-to-end reader tests against the vendored HUPO-PSI {@code small.unpacked.mzpeak} fixture.
 * Golden values were extracted independently with pyarrow.
 */
class MzPeakReaderTest {

    private static final Path FIXTURE =
            Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void listsAllSpectraWithCorrectMsLevels() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            assertThat(reader.size()).isEqualTo(48);

            long ms1 = reader.metadata().stream().filter(d -> d.msLevel() == 1).count();
            long ms2 = reader.metadata().stream().filter(d -> d.msLevel() == 2).count();
            assertThat(ms1).isEqualTo(14);
            assertThat(ms2).isEqualTo(34);

            // index-sorted and contiguous 0..47
            List<SpectrumDescription> meta = reader.metadata();
            for (int i = 0; i < meta.size(); i++) {
                assertThat(meta.get(i).index()).isEqualTo((long) i);
            }
        }
    }

    @Test
    void readsMs1ProfileSpectrumArrays() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            SpectrumDescription d = reader.getMetadata(0).orElseThrow();
            assertThat(d.msLevel()).isEqualTo(1);
            assertThat(d.signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
            assertThat(d.retentionTime()).isCloseTo(0.004935, within(1e-5));
            assertThat(d.id()).contains("scan=1");
            assertThat(d.precursors()).isEmpty(); // MS1 must have no precursor (facet join correctness)

            // With reconstruction on (default), null-marked flank points are filled, so the materialized
            // count matches the declared number_of_data_points (matches the Rust reference: 13589).
            assertThat(d.numberOfDataPoints()).isEqualTo(13589L);

            Spectrum s = reader.getSpectrum(0).orElseThrow();
            assertThat(s.pointCount()).isEqualTo(13589);
            assertThat(s.mz()).hasSize(13589);
            assertThat(s.intensity()).hasSize(13589);
            assertThat(s.mz()[0]).isCloseTo(202.606575, within(1e-4));
            assertThat(s.intensity()[0]).isEqualTo(0.0);
            assertThat(s.intensity()[1]).isCloseTo(1938.1174, within(1e-2));
        }
    }

    @Test
    void readsMs2CentroidSpectrumWithPrecursor() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            SpectrumDescription d = reader.getMetadata(2).orElseThrow();
            assertThat(d.msLevel()).isEqualTo(2);
            assertThat(d.signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
            assertThat(d.precursors()).hasSize(1);

            // Facet-join correctness: spectrum 2's selected ion (source_index==2) is m/z 810.789, NOT the
            // row-aligned 725.36 that sits at metadata row 2 but belongs to spectrum 4.
            Precursor precursor = d.primaryPrecursor();
            assertThat(precursor.precursorIndex()).isEqualTo(1L);
            assertThat(precursor.primaryIon()).isNotNull();
            assertThat(precursor.primaryIon().mz()).isCloseTo(810.789428710938, within(1e-6));
            assertThat(precursor.isolationWindow().targetMz()).isCloseTo(810.789428710938, within(1e-4));

            Spectrum s = reader.getSpectrum(2).orElseThrow();
            assertThat(s.hasPeaks()).isTrue();
            assertThat(s.peaks()).hasSize(485);
            assertThat(s.peaks().get(0).mz()).isCloseTo(231.38884, within(1e-4));
            // Centroid-only spectrum exposes its peaks as the array payload too.
            assertThat(s.mz()).hasSize(485);
        }
    }

    @Test
    void iteratesEverySpectrum() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            int count = 0;
            long pointsAndPeaks = 0;
            for (Spectrum s : reader) {
                count++;
                pointsAndPeaks += s.pointCount();
            }
            assertThat(count).isEqualTo(48);
            assertThat(pointsAndPeaks).isGreaterThan(0);
        }
    }

    @Test
    void manifestResolvesFilesByRole() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            assertThat(reader.manifest().find("spectrum", "metadata")).isPresent();
            assertThat(reader.manifest().find("spectrum", "data arrays")).isPresent();
            assertThat(reader.manifest().find("spectrum", "peaks")).isPresent();
            assertThat(reader.getSpectrum(999)).isEmpty();
        }
    }
}
