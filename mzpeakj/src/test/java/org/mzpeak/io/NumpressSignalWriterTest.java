package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The SLOF chunks {@link NumpressSignalWriter} writes for a spectrum whose intensities are all zero, for which no
 * fixed point can be derived from the largest intensity: each chunk is encoded with fixed point 1, and the spectrum
 * reads back with its zeros and its m/z within Numpress linear precision. A chunk is bounded by the first and last
 * m/z its linear payload decodes to, not by the input m/z Numpress rounds (W9).
 *
 * <p>Oracle: spectrum 0 of legacy small.unpacked, a profile spectrum of 13589 points, with its intensities set to zero,
 * or with its arrays replaced by two points.
 */
class NumpressSignalWriterTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");

    @Test
    void chunksWhoseIntensitiesAreAllZeroAreEncodedWithFixedPointOne(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(Fixtures.withoutReferences(small.spectra));
        Spectrum original = spectra.get(0);
        double[] zeros = new double[original.mz().length];
        spectra.set(0, new Spectrum(original.description(), original.mz(), zeros, original.peaks()));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, spectra,
                Fixtures.chromatogramsWithoutReferences(small.chromatograms));

        List<Double> fixedPoints = new ArrayList<>();
        List<Double> otherFixedPoints = new ArrayList<>();
        ParquetGroups.forEach(out.resolve("spectra_data.parquet"), row -> {
            Group chunk = row.getGroup("chunk", 0);
            double fixedPoint = MSNumpress.decodeFixedPoint(
                    ParquetGroups.byteList(chunk, "intensity_numpress_slof_bytes"));
            (chunk.getLong("spectrum_index", 0) == 0 ? fixedPoints : otherFixedPoints).add(fixedPoint);
        });
        assertThat(fixedPoints).hasSize(28).containsOnly(1.0);
        assertThat(otherFixedPoints).isNotEmpty().noneMatch(fp -> fp == 1.0);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            Spectrum back = reader.getSpectrumAt(0);
            assertThat(back.intensity()).hasSize(zeros.length).containsOnly(0.0);
            assertThat(back.mz()).hasSize(original.mz().length);
            for (int i = 0; i < original.mz().length; i++) {
                assertThat(back.mz()[i]).as("m/z %d", i).isCloseTo(original.mz()[i], within(1e-4));
            }
        }
    }

    /**
     * m/z 100.123454 and 100.123456 are stored at fixed point 100000 as 10012345 and 10012346, so the chunk's linear
     * payload decodes to 100.12345 and 100.12346, and these, not the input m/z, are its {@code mz_chunk_start} and
     * {@code mz_chunk_end}: the inclusive bounds of the values the chunk holds.
     */
    @ParameterizedTest
    @EnumSource(NumpressIntensityEncoding.class)
    void chunkBoundsAreTheValuesDecodedFromTheLinearPayload(NumpressIntensityEncoding encoding, @TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(Fixtures.withoutReferences(small.spectra));
        Spectrum original = spectra.get(0);
        double[] mz = {100.123454, 100.123456};
        spectra.set(0, new Spectrum(original.description(), mz, new double[] {3.0, 4.0}, List.of()));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, Fixtures.chromatogramsWithoutReferences(small.chromatograms),
                List.of(), List.of(), FileMetadata.EMPTY,
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(encoding));

        List<Group> chunks = NumpressConformanceTest.chunksBySpectrum(out.resolve("spectra_data.parquet")).get(0L);
        assertThat(chunks).hasSize(1);
        Group chunk = chunks.get(0);
        byte[] bytes = ParquetGroups.byteList(chunk, "mz_numpress_linear_bytes");
        double[] decoded = MSNumpress.decode(MSNumpress.ACC_NUMPRESS_LINEAR, bytes, bytes.length);
        assertThat(decoded).containsExactly(10012345 / 100_000.0, 10012346 / 100_000.0);
        double start = ParquetGroups.optDouble(chunk, "mz_chunk_start");
        double end = ParquetGroups.optDouble(chunk, "mz_chunk_end");
        assertThat(start).isEqualTo(decoded[0]).isNotEqualTo(mz[0]);
        assertThat(end).isEqualTo(decoded[1]).isNotEqualTo(mz[1]);
    }
}
