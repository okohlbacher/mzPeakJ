package org.mzpeak.io;

import org.mzpeak.io.MzPeakWriter.SignalEncoding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mzpeak.io.ManifestWriter.writeManifest;
import static org.mzpeak.io.NumpressSignalWriter.writeNumpressChunks;
import static org.mzpeak.io.PackedMetadataWriter.writeChromatogramMetadata;
import static org.mzpeak.io.PackedMetadataWriter.writeSpectrumMetadata;
import static org.mzpeak.io.PackedMetadataWriter.writeWavelengthMetadata;
import static org.mzpeak.io.PointSignalWriter.writeChromatogramData;
import static org.mzpeak.io.PointSignalWriter.writeSpectrumPoints;
import static org.mzpeak.io.PointSignalWriter.writeWavelengthData;

/**
 * Writes a {@link WritePlan} into an unpacked dataset directory, one pipeline for both signal encodings: the spectrum
 * metadata, spectra_data and spectra_peaks in the plan's signal encoding, chromatograms and wavelength spectra in
 * point layout, and the manifest last. Only the members the plan names are written.
 */
final class DatasetWriter {

    private DatasetWriter() {
    }

    static void write(Path directory, WritePlan plan) {
        boolean numpress = plan.options().signalEncoding() == SignalEncoding.NUMPRESS;
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(directory.resolve("spectra_metadata.parquet"), plan);
            if (plan.writesSpectraData()) {
                writeSpectra(directory.resolve("spectra_data.parquet"), plan, false, numpress);
            }
            if (plan.writesSpectraPeaks()) {
                writeSpectra(directory.resolve("spectra_peaks.parquet"), plan, true, numpress);
            }
            if (plan.writesChromatograms()) {
                writeChromatogramMetadata(directory.resolve("chromatograms_metadata.parquet"), plan);
                writeChromatogramData(directory.resolve("chromatograms_data.parquet"), plan);
            }
            if (plan.writesWavelengthSpectra()) {
                writeWavelengthMetadata(directory.resolve("wavelength_spectra_metadata.parquet"), plan);
                writeWavelengthData(directory.resolve("wavelength_spectra_data.parquet"), plan);
            }
            writeManifest(directory.resolve("mzpeak_index.json"), plan);
        } catch (IOException e) {
            throw new MzPeakException((numpress ? "Failed to write Numpress mzPeak dataset to "
                    : "Failed to write mzPeak dataset to ") + directory, e);
        }
    }

    private static void writeSpectra(Path file, WritePlan plan, boolean centroidFile, boolean numpress)
            throws IOException {
        if (numpress) {
            writeNumpressChunks(file, plan, centroidFile);
        } else {
            writeSpectrumPoints(file, plan, centroidFile);
        }
    }
}
