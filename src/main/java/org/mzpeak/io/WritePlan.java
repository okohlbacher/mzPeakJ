package org.mzpeak.io;

import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.ScanSettings;

import java.util.List;
import java.util.Map;

/**
 * Everything {@link DatasetWriter} writes, decided by {@link WritePlanner} before any I/O: the entities in the order
 * they are written, the signal files each spectrum goes to, which members exist, the footer key-values of
 * spectra_metadata and the manifest, and the report that collects the write's warnings.
 *
 * @param options            the settings of the write
 * @param spectra            the spectra in index order
 * @param chromatograms      the chromatograms in the caller's order
 * @param wavelengthSpectra  the wavelength spectra in the caller's order
 * @param writesSpectraData  whether spectra_data is written
 * @param writesSpectraPeaks whether spectra_peaks is written
 * @param scanSettings       the caller's scan settings for the manifest, possibly {@code null}
 * @param footer             the file metadata serialized once, for the spectra_metadata footer and the manifest
 * @param report             the warnings of the write
 */
record WritePlan(Options options, List<SpectrumEntry> spectra, List<Entry<Chromatogram>> chromatograms,
                 List<Entry<WavelengthSpectrum>> wavelengthSpectra, boolean writesSpectraData,
                 boolean writesSpectraPeaks, List<ScanSettings> scanSettings, Map<String, String> footer,
                 WriteReport report) {

    /**
     * A spectrum to write: the caller's spectrum, its index, and whether its rows go to spectra_data
     * ({@code mz()}/{@code intensity()}) and to spectra_peaks ({@code peaks()}).
     */
    record SpectrumEntry(Spectrum source, long sourceIndex, boolean data, boolean peaks) {
    }

    /** A chromatogram or wavelength spectrum to write: the caller's object and its index. */
    record Entry<T>(T source, long sourceIndex) {
    }

    /** Whether chromatograms_metadata and chromatograms_data are written. */
    boolean writesChromatograms() {
        return !chromatograms.isEmpty();
    }

    /** Whether wavelength_spectra_metadata and wavelength_spectra_data are written. */
    boolean writesWavelengthSpectra() {
        return !wavelengthSpectra.isEmpty();
    }
}
