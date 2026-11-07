package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;

/**
 * What {@link MzPeakWriter} derives from a spectrum's payload: the summary columns of its spectra_metadata row
 * (observed m/z range, base peak, total ion current).
 */
final class SpectrumSummary {

    private SpectrumSummary() {
    }

    /** Append the derived spectrum columns (observed m/z range, base peak, TIC) when the spectrum has payload. */
    static void writeDerivedColumns(Group spectrum, Spectrum s) {
        boolean centroid = isCentroid(s) && !s.peaks().isEmpty();
        int n = centroid ? s.peaks().size() : s.mz().length;
        if (n == 0) {
            return; // empty spectrum: omit (null), do not fabricate zeros
        }
        double loMz = Double.POSITIVE_INFINITY, hiMz = Double.NEGATIVE_INFINITY;
        double basePeakMz = Double.NaN, basePeakIntensity = Double.NEGATIVE_INFINITY;
        double tic = 0.0;
        if (centroid) {
            for (CentroidPeak p : s.peaks()) {
                double mz = p.mz(), in = p.intensity();
                if (mz < loMz) loMz = mz;
                if (mz > hiMz) hiMz = mz;
                tic += in;
                if (in > basePeakIntensity) { basePeakIntensity = in; basePeakMz = mz; }
            }
        } else {
            double[] mz = s.mz();
            double[] in = s.intensity();
            for (int i = 0; i < mz.length; i++) {
                if (mz[i] < loMz) loMz = mz[i];
                if (mz[i] > hiMz) hiMz = mz[i];
                tic += in[i];
                if (in[i] > basePeakIntensity) { basePeakIntensity = in[i]; basePeakMz = mz[i]; }
            }
        }
        if (Double.isFinite(loMz)) spectrum.add("MS_1000528_lowest_observed_mz_unit_MS_1000040", loMz);
        if (Double.isFinite(hiMz)) spectrum.add("MS_1000527_highest_observed_mz_unit_MS_1000040", hiMz);
        if (Double.isFinite(basePeakMz)) spectrum.add("MS_1000504_base_peak_mz_unit_MS_1000040", basePeakMz);
        if (Double.isFinite(basePeakIntensity)) {
            spectrum.add("MS_1000505_base_peak_intensity_unit_MS_1000131", basePeakIntensity);
        }
        spectrum.add("MS_1000285_total_ion_current_unit_MS_1000131", tic);
    }

    private static boolean isCentroid(Spectrum s) {
        return s.description().signalContinuity() == SignalContinuity.CENTROID;
    }
}
