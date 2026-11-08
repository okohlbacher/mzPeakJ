package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What {@link MzPeakWriter} derives from a spectrum: the summary columns of its spectra_metadata row (observed m/z
 * range, base peak, total ion current) from its payload, and its spectrum type from its MS level.
 */
final class SpectrumSummary {

    /** MS1 spectrum, the type derived for MS level 1 and below. */
    static final String MS1_SPECTRUM = "MS:1000579";
    /** MSn spectrum, the type derived above MS level 1. */
    static final String MSN_SPECTRUM = "MS:1000580";

    // Generated once from psi-ms.obo data-version 4.2.11, as bundled with mzdata v0.68.0 (cv/psi-ms.obo.gz), with the
    // rule of the CvTerms tables: only is_a edges are followed, obsolete terms are skipped, the table is in accession
    // order. Recorded rule: the descendants of MS:1000559 'spectrum type', without MS:1000559 itself.

    /**
     * Spectrum types: the descendants of MS:1000559 'spectrum type' (16 terms), the CURIEs a {@code spectrum_type}
     * term-marker cell holds. Unmodifiable, in accession order; {@code contains(null)} is false.
     */
    static final Set<String> SPECTRUM_TYPES = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "MS:1000294", // mass spectrum
            "MS:1000322", // charge inversion mass spectrum
            "MS:1000325", // constant neutral gain spectrum
            "MS:1000326", // constant neutral loss spectrum
            "MS:1000328", // e/2 mass spectrum
            "MS:1000341", // precursor ion spectrum
            "MS:1000579", // MS1 spectrum
            "MS:1000580", // MSn spectrum
            "MS:1000581", // CRM spectrum
            "MS:1000582", // SIM spectrum
            "MS:1000583", // SRM spectrum
            "MS:1000789", // enhanced multiply charged spectrum
            "MS:1000790", // time-delayed fragmentation spectrum
            "MS:1000804", // electromagnetic radiation spectrum
            "MS:1000805", // emission spectrum
            "MS:1000806"  // absorption spectrum
    )));

    /**
     * The summary values derived from a spectrum's payload; each is {@code null} when it cannot be derived. The total
     * ion current is the plain sum, present (possibly not finite) whenever the payload has a point.
     */
    record Values(Double lowestMz, Double highestMz, Double basePeakMz, Double basePeakIntensity, Double tic) {
    }

    private static final Values NONE = new Values(null, null, null, null, null);

    private SpectrumSummary() {
    }

    /** The spectrum type derived from {@code msLevel}: MSn spectrum above level 1, else MS1 spectrum. */
    static String spectrumType(int msLevel) {
        return msLevel > 1 ? MSN_SPECTRUM : MS1_SPECTRUM;
    }

    /**
     * Append the derived spectrum columns (observed m/z range, base peak, TIC), each of {@code v}, the values
     * {@link #derive} gives for the spectrum, that is present.
     */
    static void writeDerivedColumns(Group spectrum, Values v) {
        addIfPresent(spectrum, "MS_1000528_lowest_observed_mz_unit_MS_1000040", v.lowestMz());
        addIfPresent(spectrum, "MS_1000527_highest_observed_mz_unit_MS_1000040", v.highestMz());
        addIfPresent(spectrum, "MS_1000504_base_peak_mz_unit_MS_1000040", v.basePeakMz());
        addIfPresent(spectrum, "MS_1000505_base_peak_intensity_unit_MS_1000131", v.basePeakIntensity());
        addIfPresent(spectrum, "MS_1000285_total_ion_current_unit_MS_1000131", v.tic());
    }

    /**
     * The summary values of {@code s}'s payload: its peaks for a centroid spectrum with peaks, else its arrays. An
     * empty spectrum has none (no fabricated zeros).
     */
    static Values derive(Spectrum s) {
        boolean centroid = isCentroid(s) && !s.peaks().isEmpty();
        int n = centroid ? s.peaks().size() : s.mz().length;
        if (n == 0) {
            return NONE;
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
        return new Values(finite(loMz), finite(hiMz), finite(basePeakMz), finite(basePeakIntensity), tic);
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static void addIfPresent(Group group, String field, Double value) {
        if (value != null) {
            group.add(field, value);
        }
    }

    private static boolean isCentroid(Spectrum s) {
        return s.description().signalContinuity() == SignalContinuity.CENTROID;
    }
}
