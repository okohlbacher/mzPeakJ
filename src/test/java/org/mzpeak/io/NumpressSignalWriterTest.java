package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Spectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The SLOF chunks {@link NumpressSignalWriter} writes for a spectrum whose intensities are all zero, for which no
 * fixed point can be derived from the largest intensity: each chunk is encoded with fixed point 1, and the spectrum
 * reads back with its zeros and its m/z within Numpress linear precision.
 *
 * <p>Oracle: spectrum 0 of legacy small.unpacked, a profile spectrum of 13589 points, with its intensities set to zero.
 */
class NumpressSignalWriterTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");

    @Test
    void chunksWhoseIntensitiesAreAllZeroAreEncodedWithFixedPointOne(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Spectrum original = spectra.get(0);
        double[] zeros = new double[original.mz().length];
        spectra.set(0, new Spectrum(original.description(), original.mz(), zeros, original.peaks()));

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectoryNumpress(out, spectra, small.chromatograms);

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
}
