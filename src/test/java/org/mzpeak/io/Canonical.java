package org.mzpeak.io;

import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

/**
 * Canonical one-line text forms of decoded metadata, for characterization digests
 * ({@link LegacyMetadataSnapshotTest}).
 *
 * <ul>
 *   <li>A {@link SpectrumDescription} line holds every record component in declaration order, recursively:
 *       components are separated by {@code |}, a nested record is wrapped in {@code ( )}, a list in
 *       {@code [ ]} with its elements in list order, separated by {@code ;}. An absent string component (an id, a
 *       filter string, a spectrum reference, a CURIE or a name) reads {@link #ABSENT} (U+2400), so that it differs
 *       from the string {@code "null"}; any other absent value reads {@code null}.
 *   <li>A {@link Param} reads {@code (name|accession|Class:value|unit)}, where {@code Class} is the simple name
 *       of the value's runtime class, so a value that changes type (Long to Integer, say) changes the line.
 *   <li>A {@link Chromatogram} header reads {@code index|id|typeCurie|time.length}, a {@link WavelengthSpectrum}
 *       header {@code index|id|time|lambdaMax|length}.
 *   <li>Doubles are written by {@link #num(double)}, not {@link Double#toString(double)}: the latter's output
 *       changed in JDK 19 (JDK-4511638), and CI builds on JDK 17 and 21.
 * </ul>
 */
final class Canonical {

    /** How an absent string component reads: U+2400 SYMBOL FOR NULL, which no id, CURIE or name contains. */
    static final String ABSENT = "\u2400";

    private Canonical() {
    }

    static String describe(SpectrumDescription d) {
        return d.index() + "|" + str(d.id()) + "|" + d.msLevel() + "|" + num(d.retentionTime())
                + "|" + d.polarity() + "|" + d.signalContinuity()
                + "|" + d.numberOfDataPoints() + "|" + d.numberOfPeaks()
                + "|" + list(d.scans(), Canonical::scan)
                + "|" + list(d.precursors(), Canonical::precursor)
                + "|" + list(d.parameters(), Canonical::param);
    }

    static String describe(Chromatogram c) {
        return c.index() + "|" + str(c.id()) + "|" + str(c.typeCurie()) + "|" + c.time().length;
    }

    static String describe(WavelengthSpectrum w) {
        return w.index() + "|" + str(w.id()) + "|" + num(w.time()) + "|" + num(w.lambdaMax())
                + "|" + w.wavelength().length;
    }

    static String scan(ScanEvent s) {
        return "(" + num(s.startTime()) + "|" + num(s.injectionTime()) + "|" + str(s.filterString())
                + "|" + s.presetScanConfiguration() + "|" + s.instrumentConfigurationRef()
                + "|" + str(s.spectrumReference())
                + "|" + list(s.scanWindows(), Canonical::scanWindow)
                + "|" + list(s.parameters(), Canonical::param) + ")";
    }

    static String scanWindow(ScanWindow w) {
        return "(" + num(w.lowerLimit()) + "|" + num(w.upperLimit()) + ")";
    }

    static String precursor(Precursor p) {
        return "(" + p.precursorIndex() + "|" + str(p.precursorId())
                + "|" + isolationWindow(p.isolationWindow())
                + "|" + list(p.selectedIons(), Canonical::selectedIon)
                + "|" + activation(p.activation()) + ")";
    }

    static String isolationWindow(IsolationWindow w) {
        if (w == null) {
            return "null";
        }
        return "(" + num(w.targetMz()) + "|" + num(w.lowerOffset()) + "|" + num(w.upperOffset())
                + "|" + list(w.parameters(), Canonical::param) + ")";
    }

    static String selectedIon(SelectedIon i) {
        return "(" + num(i.mz()) + "|" + i.charge() + "|" + num(i.intensity())
                + "|" + list(i.parameters(), Canonical::param) + ")";
    }

    static String activation(Activation a) {
        if (a == null) {
            return "null";
        }
        return "(" + list(a.parameters(), Canonical::param) + ")";
    }

    static String param(Param p) {
        return "(" + str(p.name()) + "|" + str(p.accession()) + "|" + value(p.value()) + "|" + str(p.unit()) + ")";
    }

    /** {@code s}, or {@link #ABSENT} when it is {@code null}. */
    static String str(String s) {
        return s == null ? ABSENT : s;
    }

    /** {@code Class:value} with the simple name of the runtime class, or {@code null}. */
    static String value(Object v) {
        if (v == null) {
            return "null";
        }
        String text = v instanceof Double || v instanceof Float ? num(((Number) v).doubleValue()) : String.valueOf(v);
        return v.getClass().getSimpleName() + ":" + text;
    }

    /**
     * The decimal with the fewest significant digits that parses back to {@code d} (the nearest one at that
     * length), laid out like {@link Double#toString(double)}: plain with at least one fraction digit for
     * {@code 1e-3 <= |d| < 1e7}, otherwise {@code d.dddEn}. Exact and the same on every JDK. Zero, NaN and the
     * infinities use {@link Double#toString(double)}, whose output for them never changed.
     */
    static String num(double d) {
        if (d == 0 || !Double.isFinite(d)) {
            return Double.toString(d);
        }
        BigDecimal exact = new BigDecimal(d);
        BigDecimal decimal;
        for (int digits = 1; ; digits++) {
            decimal = exact.round(new MathContext(digits, RoundingMode.HALF_EVEN));
            if (decimal.doubleValue() == d) {
                break;
            }
        }
        decimal = decimal.stripTrailingZeros();
        double abs = Math.abs(d);
        if (abs >= 1e-3 && abs < 1e7) {
            String plain = decimal.toPlainString();
            return plain.indexOf('.') < 0 ? plain + ".0" : plain;
        }
        String unscaled = decimal.unscaledValue().abs().toString();
        String fraction = unscaled.length() > 1 ? unscaled.substring(1) : "0";
        int exponent = decimal.precision() - decimal.scale() - 1;
        return (d < 0 ? "-" : "") + unscaled.charAt(0) + "." + fraction + "E" + exponent;
    }

    static String num(Double d) {
        return d == null ? "null" : num(d.doubleValue());
    }

    static <T> List<String> lines(List<T> items, Function<T, String> describe) {
        List<String> out = new ArrayList<>(items.size());
        for (T item : items) {
            out.add(describe.apply(item));
        }
        return out;
    }

    /** SHA-256 (lowercase hex) of {@code lines} joined by {@code \n}, encoded as UTF-8. */
    static String sha256(List<String> lines) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return HexFormat.of().formatHex(md.digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> String list(List<T> items, Function<T, String> describe) {
        return "[" + String.join(";", lines(items, describe)) + "]";
    }
}
