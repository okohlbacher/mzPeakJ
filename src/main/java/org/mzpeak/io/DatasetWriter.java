package org.mzpeak.io;

import org.mzpeak.io.ManifestWriter.Dialect;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
 * point layout, and the manifest last, once every member is closed, so that it can list each member's checksum. Only
 * the members the plan names are written.
 */
final class DatasetWriter {

    private DatasetWriter() {
    }

    static void write(Path directory, WritePlan plan) {
        boolean numpress = plan.options().signalEncoding() == SignalEncoding.NUMPRESS;
        List<WrittenMember> members = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(member(directory, members, "spectra_metadata.parquet", EntityType.SPECTRUM,
                    DataKind.METADATA), plan);
            if (plan.writesSpectraData()) {
                writeSpectra(member(directory, members, "spectra_data.parquet", EntityType.SPECTRUM,
                        DataKind.DATA_ARRAYS), plan, false, numpress);
            }
            if (plan.writesSpectraPeaks()) {
                writeSpectra(member(directory, members, "spectra_peaks.parquet", EntityType.SPECTRUM,
                        DataKind.PEAKS), plan, true, numpress);
            }
            if (plan.writesChromatograms()) {
                writeChromatogramMetadata(member(directory, members, "chromatograms_metadata.parquet",
                        EntityType.CHROMATOGRAM, DataKind.METADATA), plan);
                writeChromatogramData(member(directory, members, "chromatograms_data.parquet",
                        EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS), plan);
            }
            if (plan.writesWavelengthSpectra()) {
                writeWavelengthMetadata(member(directory, members, "wavelength_spectra_metadata.parquet",
                        EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA), plan);
                writeWavelengthData(member(directory, members, "wavelength_spectra_data.parquet",
                        EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS), plan);
            }
            // The packed metadata layout is the only one written so far; it keeps the pin-era role spellings.
            writeManifest(directory, members, plan, Dialect.PIN_29E59B2);
        } catch (IOException e) {
            throw new MzPeakException((numpress ? "Failed to write Numpress mzPeak dataset to "
                    : "Failed to write mzPeak dataset to ") + directory, e);
        }
    }

    /**
     * Records {@code name} with its role as the next manifest entry and returns its path in {@code directory}. The
     * member's writer closes the file before the next member is started.
     */
    private static Path member(Path directory, List<WrittenMember> members, String name, EntityType entity,
                               DataKind kind) {
        members.add(new WrittenMember(name, entity, kind));
        return directory.resolve(name);
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
