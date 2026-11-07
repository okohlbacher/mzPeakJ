package org.mzpeak.io;

import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mzpeak.io.ArchivePacker.packArchive;
import static org.mzpeak.io.ManifestWriter.writeManifest;
import static org.mzpeak.io.NumpressSignalWriter.writeNumpressChunks;
import static org.mzpeak.io.PackedMetadataWriter.writeChromatogramMetadata;
import static org.mzpeak.io.PackedMetadataWriter.writeSpectrumMetadata;
import static org.mzpeak.io.PackedMetadataWriter.writeWavelengthMetadata;
import static org.mzpeak.io.PointSignalWriter.writeChromatogramData;
import static org.mzpeak.io.PointSignalWriter.writeSpectrumPoints;
import static org.mzpeak.io.PointSignalWriter.writeWavelengthData;
import static org.mzpeak.io.SpectrumSummary.peakRows;
import static org.mzpeak.io.SpectrumSummary.profileRows;

/**
 * Writes mzPeak datasets in either {@code point} layout (the default) or MS-Numpress {@code chunk} layout.
 *
 * <p>Point layout: spectra (profile + centroid), chromatograms, wavelength (UV/DAD) spectra, and file/run
 * metadata; round-trips through {@link MzPeakReader}. Numpress layout additionally encodes m/z with Numpress
 * linear (MS:1002312) and intensity with Numpress SLOF (MS:1002314) per chunk.
 *
 * <p>Per-spectrum/scan/selected-ion CV/user {@code parameters} lists round-trip through both writer and reader.
 * The writer also emits the reference schema's richer metadata: derived spectrum columns (observed m/z range,
 * base peak, total ion current, spectrum type), scan windows, precursor activation, typed ion-mobility and
 * imaging-position columns, scan settings, and wavelength facets — so a read &rarr; write cycle preserves
 * everything {@link MzPeakReader} surfaces.
 */
public final class MzPeakWriter {

    private MzPeakWriter() {
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra) {
        writeDirectory(directory, spectra, List.of());
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeDirectory(directory, spectra, chromatograms, FileMetadata.EMPTY);
    }

    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      FileMetadata fileMetadata) {
        writeDirectory(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /**
     * Write an unpacked {@code *.mzpeak/} directory, including wavelength spectra, scan settings, and
     * file/run metadata. This is the full round-trip form consumed by the forward-backward test.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative. The primary
     * precursor's precursor_index, the only one written, must be non-negative; several spectra may share it.
     * Otherwise an {@link MzPeakException} is thrown before anything is written.
     */
    public static void writeDirectory(Path directory, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                      List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                      FileMetadata fileMetadata) {
        List<Spectrum> ordered = validateAndOrder(spectra, chromatograms, wavelengthSpectra);
        boolean hasProfile = ordered.stream().anyMatch(s -> profileRows(s) > 0);
        boolean hasPeaks = ordered.stream().anyMatch(s -> peakRows(s) > 0);
        boolean hasChromatograms = !chromatograms.isEmpty();
        boolean hasWavelength = !wavelengthSpectra.isEmpty();
        Map<String, String> footer = FooterMetadataWriter.serialize(fileMetadata);
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(directory.resolve("spectra_metadata.parquet"), ordered, footer);
            if (hasProfile) {
                writeSpectrumPoints(directory.resolve("spectra_data.parquet"), ordered, false);
            }
            if (hasPeaks) {
                writeSpectrumPoints(directory.resolve("spectra_peaks.parquet"), ordered, true);
            }
            if (hasChromatograms) {
                writeChromatogramMetadata(directory.resolve("chromatograms_metadata.parquet"), chromatograms);
                writeChromatogramData(directory.resolve("chromatograms_data.parquet"), chromatograms);
            }
            if (hasWavelength) {
                writeWavelengthMetadata(directory.resolve("wavelength_spectra_metadata.parquet"), wavelengthSpectra);
                writeWavelengthData(directory.resolve("wavelength_spectra_data.parquet"), wavelengthSpectra);
            }
            writeManifest(directory.resolve("mzpeak_index.json"), hasProfile, hasPeaks, hasChromatograms,
                    hasWavelength, scanSettings, fileMetadata);
        } catch (IOException e) {
            throw new MzPeakException("Failed to write mzPeak dataset to " + directory, e);
        }
    }

    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms) {
        writeArchive(archive, spectra, chromatograms, FileMetadata.EMPTY);
    }

    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    FileMetadata fileMetadata) {
        writeArchive(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP (atomically: build in temp, then move into place). */
    public static void writeArchive(Path archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                    List<WavelengthSpectrum> wavelengthSpectra, List<ScanSettings> scanSettings,
                                    FileMetadata fileMetadata) {
        packArchive(archive, dir ->
                writeDirectory(dir, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata));
    }

    // ---- Numpress-encoded chunk layout API --------------------------------------------------------

    /**
     * Write an unpacked {@code *.mzpeak/} directory using MS-Numpress chunk encoding.
     * m/z is encoded with Numpress linear ({@code MS:1002312}); intensity with Numpress SLOF
     * ({@code MS:1002314}). Chromatograms are written in standard point layout. Round-trips through
     * {@link MzPeakReader} on any container and layout variant.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms) {
        writeDirectoryNumpress(directory, spectra, chromatograms,
                FileMetadata.EMPTY, NumpressIntensityEncoding.SLOF);
    }

    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             FileMetadata fileMetadata) {
        writeDirectoryNumpress(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata,
                NumpressIntensityEncoding.SLOF);
    }

    /**
     * Write an unpacked directory with Numpress encoding and a caller-chosen intensity encoding.
     * Use {@link NumpressIntensityEncoding#SLOF} (default) for floating-point intensities and
     * {@link NumpressIntensityEncoding#PIC} for non-negative integer ion-count arrays.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        writeDirectoryNumpress(directory, spectra, chromatograms, List.of(), List.of(), fileMetadata, intensityEncoding);
    }

    /**
     * Full Numpress directory form including wavelength spectra and scan settings.
     *
     * <p>Spectra may come in any order; they are written in index order (the caller's list is not modified).
     * Spectrum, chromatogram and wavelength-spectrum indices must be unique and non-negative. The primary
     * precursor's precursor_index, the only one written, must be non-negative; several spectra may share it.
     * Otherwise an {@link MzPeakException} is thrown before anything is written.
     */
    public static void writeDirectoryNumpress(Path directory, List<Spectrum> spectra,
                                             List<Chromatogram> chromatograms,
                                             List<WavelengthSpectrum> wavelengthSpectra,
                                             List<ScanSettings> scanSettings,
                                             FileMetadata fileMetadata,
                                             NumpressIntensityEncoding intensityEncoding) {
        List<Spectrum> ordered = validateAndOrder(spectra, chromatograms, wavelengthSpectra);
        boolean hasPeaks = ordered.stream().anyMatch(s -> peakRows(s) > 0);
        boolean hasChromatograms = !chromatograms.isEmpty();
        boolean hasWavelength = !wavelengthSpectra.isEmpty();
        Map<String, String> footer = FooterMetadataWriter.serialize(fileMetadata);
        try {
            Files.createDirectories(directory);
            writeSpectrumMetadata(directory.resolve("spectra_metadata.parquet"), ordered, footer);
            writeNumpressChunks(directory.resolve("spectra_data.parquet"), ordered, false, intensityEncoding);
            if (hasPeaks) {
                writeNumpressChunks(directory.resolve("spectra_peaks.parquet"), ordered, true, intensityEncoding);
            }
            if (hasChromatograms) {
                writeChromatogramMetadata(directory.resolve("chromatograms_metadata.parquet"), chromatograms);
                writeChromatogramData(directory.resolve("chromatograms_data.parquet"), chromatograms);
            }
            if (hasWavelength) {
                writeWavelengthMetadata(directory.resolve("wavelength_spectra_metadata.parquet"), wavelengthSpectra);
                writeWavelengthData(directory.resolve("wavelength_spectra_data.parquet"), wavelengthSpectra);
            }
            writeManifest(directory.resolve("mzpeak_index.json"), true, hasPeaks, hasChromatograms,
                    hasWavelength, scanSettings, fileMetadata);
        } catch (IOException e) {
            throw new MzPeakException("Failed to write Numpress mzPeak dataset to " + directory, e);
        }
    }

    /** Write a single-file STORED {@code .mzpeak} ZIP with Numpress encoding. */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms) {
        writeArchiveNumpress(archive, spectra, chromatograms, FileMetadata.EMPTY);
    }

    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           FileMetadata fileMetadata) {
        writeArchiveNumpress(archive, spectra, chromatograms, List.of(), List.of(), fileMetadata);
    }

    /** Full Numpress archive form including wavelength spectra and scan settings. */
    public static void writeArchiveNumpress(Path archive, List<Spectrum> spectra,
                                           List<Chromatogram> chromatograms,
                                           List<WavelengthSpectrum> wavelengthSpectra,
                                           List<ScanSettings> scanSettings,
                                           FileMetadata fileMetadata) {
        packArchive(archive, dir ->
                writeDirectoryNumpress(dir, spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata,
                        NumpressIntensityEncoding.SLOF));
    }

    /**
     * Intensity encoding for Numpress chunks. {@link #SLOF} is the standard choice for floating-point
     * intensities; {@link #PIC} is appropriate for integer ion-count arrays.
     */
    public enum NumpressIntensityEncoding { SLOF, PIC }

    // ---- helpers ----------------------------------------------------------------------------------

    /**
     * Enforce the reader's index contract before anything is written. Spectrum, precursor_index, chromatogram
     * and wavelength-spectrum indices must be non-negative (they are stored as uint64), and spectrum, chromatogram
     * and wavelength-spectrum indices must be unique. The reader finds a spectrum's data rows through the index
     * range of each row group, so spectra are written in index order: this returns the caller's list when it is
     * already in index order, and otherwise a stable-sorted copy. The caller's list is never modified.
     */
    private static List<Spectrum> validateAndOrder(List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                                   List<WavelengthSpectrum> wavelengthSpectra) {
        boolean inIndexOrder = true;
        long previous = -1;
        for (Spectrum s : spectra) {
            requireNonNegative(s.index(), "spectrum index");
            Precursor p = s.description().primaryPrecursor();
            if (p != null && p.precursorIndex() != null) {
                requireNonNegative(p.precursorIndex(), "precursor_index");
            }
            inIndexOrder &= s.index() >= previous;
            previous = s.index();
        }
        Set<Long> seen = new HashSet<>();
        for (Chromatogram c : chromatograms) {
            requireNonNegative(c.index(), "chromatogram index");
            requireUnique(seen, c.index(), "chromatogram index");
        }
        seen.clear();
        for (WavelengthSpectrum w : wavelengthSpectra) {
            requireNonNegative(w.index(), "wavelength spectrum index");
            requireUnique(seen, w.index(), "wavelength spectrum index");
        }
        List<Spectrum> ordered = spectra;
        if (!inIndexOrder) {
            ordered = new ArrayList<>(spectra);
            ordered.sort(Comparator.comparingLong(Spectrum::index)); // List.sort is stable
        }
        previous = -1;
        for (Spectrum s : ordered) {
            if (s.index() == previous) {
                throw new MzPeakException("duplicate spectrum index: " + s.index() + " (mzPeak indices are unique)");
            }
            previous = s.index();
        }
        return ordered;
    }

    private static void requireNonNegative(long value, String what) {
        if (value < 0) {
            throw new MzPeakException(what + " must be non-negative (mzPeak indices are uint64): " + value);
        }
    }

    private static void requireUnique(Set<Long> seen, long value, String what) {
        if (!seen.add(value)) {
            throw new MzPeakException("duplicate " + what + ": " + value + " (mzPeak indices are unique)");
        }
    }
}
