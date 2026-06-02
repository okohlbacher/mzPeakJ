package org.mzpeak.fragpipe;

import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import umich.ms.datatypes.scan.IScan;
import umich.ms.datatypes.scan.StorageStrategy;
import umich.ms.datatypes.scan.impl.ScanDefault;
import umich.ms.datatypes.scan.props.Polarity;
import umich.ms.datatypes.scan.props.PrecursorInfo;
import umich.ms.datatypes.spectrum.ISpectrum;
import umich.ms.datatypes.spectrum.impl.SpectrumDefault;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts mzPeakJ {@link Spectrum}s into FragPipe/MSFragger (MSFTBX) {@code umich.ms.datatypes} types.
 *
 * <p>This is the only class that depends on {@code msftbx}; the rest of mzPeakJ is FragPipe-free. Because
 * mzPeakJ already stores intensities as {@code double[]}, the m/z and intensity arrays are passed to
 * {@link SpectrumDefault} by reference with no widening copy.
 */
public final class MsftbxAdapter {

    private static final Pattern SCAN_NUMBER = Pattern.compile("scan=(\\d+)");

    private MsftbxAdapter() {
    }

    /** Build an MSFTBX {@link ISpectrum} (peak list) from a spectrum's array payload. */
    public static ISpectrum toSpectrum(Spectrum spectrum) {
        return new SpectrumDefault(spectrum.mz(), spectrum.intensity(), null);
    }

    /** Build an MSFTBX {@link IScan} (metadata + attached spectrum) from an mzPeakJ spectrum. */
    public static IScan toScan(Spectrum spectrum) {
        SpectrumDescription d = spectrum.description();
        ScanDefault scan = new ScanDefault(scanNumber(d));
        scan.setMsLevel(d.msLevel());
        if (!Double.isNaN(d.retentionTime())) {
            scan.setRt(d.retentionTime());
        }
        Polarity polarity = mapPolarity(d.polarity());
        if (polarity != null) {
            scan.setPolarity(polarity);
        }
        scan.setCentroided(d.signalContinuity() == SignalContinuity.CENTROID);
        scan.setScanMzWindowLower(null);
        scan.setStorageStrategy(StorageStrategy.STRONG);
        scan.setSpectrum(toSpectrum(spectrum), false);

        Precursor precursor = d.primaryPrecursor();
        if (precursor != null) {
            scan.setPrecursor(mapPrecursor(precursor));
        }
        return scan;
    }

    private static PrecursorInfo mapPrecursor(Precursor precursor) {
        PrecursorInfo info = new PrecursorInfo();
        SelectedIon ion = precursor.primaryIon();
        if (ion != null) {
            info.setMzTarget(ion.mz());
            info.setMzTargetMono(ion.mz());
            info.setCharge(ion.charge());
        }
        if (precursor.isolationWindow() != null) {
            info.setMzRangeStart(precursor.isolationWindow().lowerBound());
            info.setMzRangeEnd(precursor.isolationWindow().upperBound());
        }
        // mzPeak precursor_index is a spectrum index, not a vendor scan number; best-effort mapping.
        if (precursor.precursorIndex() != null && precursor.precursorIndex() >= 0
                && precursor.precursorIndex() <= Integer.MAX_VALUE) {
            info.setParentScanNum(precursor.precursorIndex().intValue());
        }
        return info;
    }

    private static Polarity mapPolarity(org.mzpeak.model.Polarity polarity) {
        return switch (polarity) {
            case POSITIVE -> Polarity.POSITIVE;
            case NEGATIVE -> Polarity.NEGATIVE;
            case UNKNOWN -> null;
        };
    }

    /** Prefer the vendor scan number parsed from the nativeID; fall back to the spectrum index. */
    private static int scanNumber(SpectrumDescription d) {
        if (d.id() != null) {
            Matcher m = SCAN_NUMBER.matcher(d.id());
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return Math.toIntExact(d.index());
    }
}
