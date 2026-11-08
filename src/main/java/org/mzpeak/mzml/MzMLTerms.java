package org.mzpeak.mzml;

import org.mzpeak.model.CvTerms;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Term names, param value typing and formatting, and the id and XML rules of the mzML converter (mzML design 5.2,
 * 5.3, 12.2). The checks that stand in for XML Schema validation ({@link #ncName}, {@link #spectrumIdValid},
 * {@link #dateTimeValid}, {@link #anyUri}) judge as the JDK's schema validator judges ({@link #anyUri} slightly more
 * strictly, as stated there), so a value they accept can be written verbatim and the document still validates.
 */
final class MzMLTerms {

    private MzMLTerms() {
    }

    // --- Term tables ---
    // SPECTRUM_TYPES, CHROMATOGRAM_TYPES, SPECTRA_COMBINATION, DATA_TYPES and COMPRESSIONS are generated once from
    // psi-ms.obo data-version 4.2.11, as bundled with mzdata v0.68.0 (cv/psi-ms.obo.gz), by the one rule of mzML
    // design 12.2: the non-obsolete transitive is_a descendants of the root (only is_a edges followed, the root itself
    // not listed), in accession order. Obsolete descendants left out: MS:1000343 and MS:1000620 (spectrum types),
    // MS:1001474 (chromatogram types), MS:1000520 (data types); obsolete terms get no name. UNIT_NAMES and FIXED_TERMS
    // are chosen lists, named as psi-ms 4.2.11 names them (UO:0000032, which that file does not carry, as UO
    // releases/2026-01-16 does). All tables are unmodifiable and in accession order.

    /** Spectrum types: the descendants of MS:1000559 'spectrum type' (16 terms). */
    static final Map<String, String> SPECTRUM_TYPES = table(
            "MS:1000294", "mass spectrum",
            "MS:1000322", "charge inversion mass spectrum",
            "MS:1000325", "constant neutral gain spectrum",
            "MS:1000326", "constant neutral loss spectrum",
            "MS:1000328", "e/2 mass spectrum",
            "MS:1000341", "precursor ion spectrum",
            "MS:1000579", "MS1 spectrum",
            "MS:1000580", "MSn spectrum",
            "MS:1000581", "CRM spectrum",
            "MS:1000582", "SIM spectrum",
            "MS:1000583", "SRM spectrum",
            "MS:1000789", "enhanced multiply charged spectrum",
            "MS:1000790", "time-delayed fragmentation spectrum",
            "MS:1000804", "electromagnetic radiation spectrum",
            "MS:1000805", "emission spectrum",
            "MS:1000806", "absorption spectrum");

    /** Chromatogram types: the descendants of MS:1000626 'chromatogram type' (14 terms; MS:4000104 via MS:1000235). */
    static final Map<String, String> CHROMATOGRAM_TYPES = table(
            "MS:1000235", "total ion current chromatogram",
            "MS:1000627", "selected ion current chromatogram",
            "MS:1000628", "basepeak chromatogram",
            "MS:1000810", "ion current chromatogram",
            "MS:1000811", "electromagnetic radiation chromatogram",
            "MS:1000812", "absorption chromatogram",
            "MS:1000813", "emission chromatogram",
            "MS:1001472", "selected ion monitoring chromatogram",
            "MS:1001473", "selected reaction monitoring chromatogram",
            "MS:1002715", "temperature chromatogram",
            "MS:1003019", "pressure chromatogram",
            "MS:1003020", "flow rate chromatogram",
            "MS:4000025", "precursor ion current chromatogram",
            "MS:4000104", "total ion currents");

    /** Spectra combinations: the descendants of MS:1000570 'spectra combination' (4 terms). */
    static final Map<String, String> SPECTRA_COMBINATION = table(
            "MS:1000571", "sum of spectra",
            "MS:1000573", "median of spectra",
            "MS:1000575", "mean of spectra",
            "MS:1000795", "no combination");

    /** Binary data types: the descendants of MS:1000518 'binary data type' (5 terms). */
    static final Map<String, String> DATA_TYPES = table(
            "MS:1000519", "32-bit integer",
            "MS:1000521", "32-bit float",
            "MS:1000522", "64-bit integer",
            "MS:1000523", "64-bit float",
            "MS:1001479", "null-terminated ASCII string");

    /** Binary data compressions: the descendants of MS:1000572 'binary data compression type' (18 terms). */
    static final Map<String, String> COMPRESSIONS = table(
            "MS:1000574", "zlib compression",
            "MS:1000576", "no compression",
            "MS:1002312", "MS-Numpress linear prediction compression",
            "MS:1002313", "MS-Numpress positive integer compression",
            "MS:1002314", "MS-Numpress short logged float compression",
            "MS:1002746", "MS-Numpress linear prediction compression followed by zlib compression",
            "MS:1002747", "MS-Numpress positive integer compression followed by zlib compression",
            "MS:1002748", "MS-Numpress short logged float compression followed by zlib compression",
            "MS:1003088", "truncation and zlib compression",
            "MS:1003089", "truncation, delta prediction and zlib compression",
            "MS:1003090", "truncation, linear prediction and zlib compression",
            "MS:1003780", "zstd compression",
            "MS:1003781", "byte-shuffled zstd compression",
            "MS:1003782", "dictionary-encoded zstd compression",
            "MS:1003783", "MS-Numpress linear prediction compression followed by zstd compression",
            "MS:1003784", "MS-Numpress positive integer compression followed by zstd compression",
            "MS:1003785", "MS-Numpress short logged float compression followed by zstd compression",
            "MS:1003826", "coordinate grid encoding");

    /** Units: those of {@link CvTerms#DEFAULT_ARRAY_UNITS} and the time, energy, absorbance and length units read. */
    static final Map<String, String> UNIT_NAMES = table(
            "MS:1000040", "m/z",
            "MS:1000131", "number of detector counts",
            "MS:1002814", "volt-second per square centimeter",
            "UO:0000010", "second",
            "UO:0000017", "micrometer",
            "UO:0000018", "nanometer",
            "UO:0000028", "millisecond",
            "UO:0000031", "minute",
            "UO:0000032", "hour",
            "UO:0000186", "dimensionless unit",
            "UO:0000266", "electronvolt",
            "UO:0000269", "absorbance unit");

    /** The fixed terms the writer emits (promoted columns, contacts, synthesized software and data processing). */
    static final Map<String, String> FIXED_TERMS = table(
            "MS:1000016", "scan start time",
            "MS:1000041", "charge state",
            "MS:1000042", "peak intensity",
            "MS:1000045", "collision energy",
            "MS:1000127", "centroid spectrum",
            "MS:1000128", "profile spectrum",
            "MS:1000129", "negative scan",
            "MS:1000130", "positive scan",
            "MS:1000500", "scan window upper limit",
            "MS:1000501", "scan window lower limit",
            "MS:1000511", "ms level",
            "MS:1000512", "filter string",
            "MS:1000544", "Conversion to mzML",
            "MS:1000586", "contact name",
            "MS:1000590", "contact affiliation",
            "MS:1000616", "preset scan configuration",
            "MS:1000618", "highest observed wavelength",
            "MS:1000619", "lowest observed wavelength",
            "MS:1000744", "selected ion m/z",
            "MS:1000786", "non-standard data array",
            "MS:1000799", "custom unreleased software tool",
            "MS:1000827", "isolation window target m/z",
            "MS:1000828", "isolation window lower offset",
            "MS:1000829", "isolation window upper offset",
            "MS:1000927", "ion injection time",
            "MS:1003812", "lambda max");

    private static final Map<String, String> NAMES = names(SPECTRUM_TYPES, CHROMATOGRAM_TYPES, SPECTRA_COMBINATION,
            DATA_TYPES, COMPRESSIONS, UNIT_NAMES, FIXED_TERMS, CvTerms.ARRAY_TYPES, CvTerms.DISSOCIATION_METHODS);

    /**
     * The name of {@code curie} from the tables above, then {@link CvTerms#ARRAY_TYPES} and
     * {@link CvTerms#DISSOCIATION_METHODS}; null for any other accession (obsolete terms included) and for null.
     */
    static String name(String curie) {
        return NAMES.get(curie);
    }

    // --- Param values (design 5.3) ---

    /** A param value read from mzML; {@code typeMismatch} when the text did not fit its declared type. */
    record Typed(Object value, boolean typeMismatch) {
    }

    private static final Pattern INTEGER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?");
    private static final Map<String, Double> NON_FINITE =
            Map.of("INF", Double.POSITIVE_INFINITY, "-INF", Double.NEGATIVE_INFINITY, "NaN", Double.NaN);
    private static final Map<String, Boolean> BOOLEANS =
            Map.of("true", Boolean.TRUE, "false", Boolean.FALSE, "1", Boolean.TRUE, "0", Boolean.FALSE);
    private static final Set<String> INTEGER_TYPES = Set.of("int", "integer", "long", "short", "byte");
    private static final Set<String> DECIMAL_TYPES = Set.of("float", "double", "decimal");

    /**
     * Types {@code text}, a cvParam or userParam value, by {@code xsdType} (a userParam {@code type}, null if
     * absent). Absent or empty text is null. A declared type ({@code xsd:} or any other prefix, or none): a
     * {@code *string} type gives the text; {@code boolean} gives a Boolean for {@code true}, {@code false}, {@code 1},
     * {@code 0}; {@code int}, {@code integer}, {@code long}, {@code short}, {@code byte}, {@code *Integer} and
     * {@code unsigned*} give a Long when the text is an ASCII integer that fits one; {@code float}, {@code double} and
     * {@code decimal} give a Double for a decimal or exponent number, {@code INF}, {@code -INF} or {@code NaN}; text
     * that does not fit these types stays a String with {@code typeMismatch}; any other type gives the text. Without a
     * type the content decides: an integer that fits a long is a Long, a decimal or exponent number (with {@code .},
     * {@code e} or {@code E}) and {@code INF}, {@code -INF}, {@code NaN} are Doubles, {@code true} and {@code false}
     * Booleans, anything else (an integer too large for a long included) the text.
     */
    static Typed value(String text, String xsdType) {
        if (text == null || text.isEmpty()) {
            return new Typed(null, false);
        }
        if (xsdType == null) {
            Object value; // not a conditional expression: Long and Double operands would be unboxed
            if (INTEGER.matcher(text).matches()) {
                value = longValue(text);
            } else {
                value = doubleValue(text);
            }
            if (value == null) {
                value = BOOLEANS.get(text); // 1 and 0 matched INTEGER already
            }
            return new Typed(value == null ? text : value, false);
        }
        String type = xsdType.substring(xsdType.indexOf(':') + 1);
        Object value;
        if (type.toLowerCase(Locale.ROOT).endsWith("string")) {
            return new Typed(text, false);
        } else if (type.equals("boolean")) {
            value = BOOLEANS.get(text);
        } else if (INTEGER_TYPES.contains(type) || type.endsWith("Integer") || type.startsWith("unsigned")) {
            value = longValue(text);
        } else if (DECIMAL_TYPES.contains(type)) {
            value = doubleValue(text);
        } else {
            return new Typed(text, false);
        }
        return value == null ? new Typed(text, true) : new Typed(value, false);
    }

    /** {@code text} as a Long if it is an ASCII integer that fits one, else null. */
    private static Long longValue(String text) {
        if (!INTEGER.matcher(text).matches()) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    /** {@code text} as a Double if it is a decimal or exponent number, {@code INF}, {@code -INF} or {@code NaN}. */
    private static Double doubleValue(String text) {
        return DECIMAL.matcher(text).matches() ? Double.valueOf(text) : NON_FINITE.get(text);
    }

    /**
     * {@code value} as mzML text: null for null; Double and Float by {@link Double#toString(double)} with
     * {@code INF}, {@code -INF} and {@code NaN} for the non-finite values; any other value (Long, Integer, Short, Byte,
     * Boolean, String) by its {@code toString}. {@code value(format(x), null)} gives {@code x} back for every Long,
     * Double and Boolean.
     */
    static String format(Object value) {
        if (value instanceof Double || value instanceof Float) {
            return Double.toString(((Number) value).doubleValue()).replace("Infinity", "INF");
        }
        return value == null ? null : value.toString();
    }

    /** The {@code userParam@type} of {@code value}: xsd:integer, xsd:double, xsd:boolean, xsd:string; null for null. */
    static String userParamType(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return "xsd:integer";
        }
        if (value instanceof Double || value instanceof Float) {
            return "xsd:double";
        }
        return value instanceof Boolean ? "xsd:boolean" : "xsd:string";
    }

    // --- Ids (design 5.2) ---

    // The NCName characters of XML Schema 1.0, which the JDK's validator checks xs:ID values against (Namespaces in
    // XML 1.0 first edition, after XML 1.0 second edition Appendix B): NC_START holds the ranges of Letter and '_',
    // NC_PART the ranges a later character may also take (Digit, CombiningChar, Extender, '.', '-'). Each pair of
    // characters is one inclusive range, in ascending order; characters above U+FFFF are never NCName characters there.
    // Generated once from the JDK's tables; MzMLTermsTest compares every BMP character with the JDK's name check.
    private static final String NC_START = ""
            + "\u0041\u005A\u005F\u005F\u0061\u007A\u00C0\u00D6\u00D8\u00F6\u00F8\u0131\u0134\u013E\u0141\u0148"
            + "\u014A\u017E\u0180\u01C3\u01CD\u01F0\u01F4\u01F5\u01FA\u0217\u0250\u02A8\u02BB\u02C1\u0386\u0386"
            + "\u0388\u038A\u038C\u038C\u038E\u03A1\u03A3\u03CE\u03D0\u03D6\u03DA\u03DA\u03DC\u03DC\u03DE\u03DE"
            + "\u03E0\u03E0\u03E2\u03F3\u0401\u040C\u040E\u044F\u0451\u045C\u045E\u0481\u0490\u04C4\u04C7\u04C8"
            + "\u04CB\u04CC\u04D0\u04EB\u04EE\u04F5\u04F8\u04F9\u0531\u0556\u0559\u0559\u0561\u0586\u05D0\u05EA"
            + "\u05F0\u05F2\u0621\u063A\u0641\u064A\u0671\u06B7\u06BA\u06BE\u06C0\u06CE\u06D0\u06D3\u06D5\u06D5"
            + "\u06E5\u06E6\u0905\u0939\u093D\u093D\u0958\u0961\u0985\u098C\u098F\u0990\u0993\u09A8\u09AA\u09B0"
            + "\u09B2\u09B2\u09B6\u09B9\u09DC\u09DD\u09DF\u09E1\u09F0\u09F1\u0A05\u0A0A\u0A0F\u0A10\u0A13\u0A28"
            + "\u0A2A\u0A30\u0A32\u0A33\u0A35\u0A36\u0A38\u0A39\u0A59\u0A5C\u0A5E\u0A5E\u0A72\u0A74\u0A85\u0A8B"
            + "\u0A8D\u0A8D\u0A8F\u0A91\u0A93\u0AA8\u0AAA\u0AB0\u0AB2\u0AB3\u0AB5\u0AB9\u0ABD\u0ABD\u0AE0\u0AE0"
            + "\u0B05\u0B0C\u0B0F\u0B10\u0B13\u0B28\u0B2A\u0B30\u0B32\u0B33\u0B36\u0B39\u0B3D\u0B3D\u0B5C\u0B5D"
            + "\u0B5F\u0B61\u0B85\u0B8A\u0B8E\u0B90\u0B92\u0B95\u0B99\u0B9A\u0B9C\u0B9C\u0B9E\u0B9F\u0BA3\u0BA4"
            + "\u0BA8\u0BAA\u0BAE\u0BB5\u0BB7\u0BB9\u0C05\u0C0C\u0C0E\u0C10\u0C12\u0C28\u0C2A\u0C33\u0C35\u0C39"
            + "\u0C60\u0C61\u0C85\u0C8C\u0C8E\u0C90\u0C92\u0CA8\u0CAA\u0CB3\u0CB5\u0CB9\u0CDE\u0CDE\u0CE0\u0CE1"
            + "\u0D05\u0D0C\u0D0E\u0D10\u0D12\u0D28\u0D2A\u0D39\u0D60\u0D61\u0E01\u0E2E\u0E30\u0E30\u0E32\u0E33"
            + "\u0E40\u0E45\u0E81\u0E82\u0E84\u0E84\u0E87\u0E88\u0E8A\u0E8A\u0E8D\u0E8D\u0E94\u0E97\u0E99\u0E9F"
            + "\u0EA1\u0EA3\u0EA5\u0EA5\u0EA7\u0EA7\u0EAA\u0EAB\u0EAD\u0EAE\u0EB0\u0EB0\u0EB2\u0EB3\u0EBD\u0EBD"
            + "\u0EC0\u0EC4\u0F40\u0F47\u0F49\u0F69\u10A0\u10C5\u10D0\u10F6\u1100\u1100\u1102\u1103\u1105\u1107"
            + "\u1109\u1109\u110B\u110C\u110E\u1112\u113C\u113C\u113E\u113E\u1140\u1140\u114C\u114C\u114E\u114E"
            + "\u1150\u1150\u1154\u1155\u1159\u1159\u115F\u1161\u1163\u1163\u1165\u1165\u1167\u1167\u1169\u1169"
            + "\u116D\u116E\u1172\u1173\u1175\u1175\u119E\u119E\u11A8\u11A8\u11AB\u11AB\u11AE\u11AF\u11B7\u11B8"
            + "\u11BA\u11BA\u11BC\u11C2\u11EB\u11EB\u11F0\u11F0\u11F9\u11F9\u1E00\u1E9B\u1EA0\u1EF9\u1F00\u1F15"
            + "\u1F18\u1F1D\u1F20\u1F45\u1F48\u1F4D\u1F50\u1F57\u1F59\u1F59\u1F5B\u1F5B\u1F5D\u1F5D\u1F5F\u1F7D"
            + "\u1F80\u1FB4\u1FB6\u1FBC\u1FBE\u1FBE\u1FC2\u1FC4\u1FC6\u1FCC\u1FD0\u1FD3\u1FD6\u1FDB\u1FE0\u1FEC"
            + "\u1FF2\u1FF4\u1FF6\u1FFC\u2126\u2126\u212A\u212B\u212E\u212E\u2180\u2182\u3007\u3007\u3021\u3029"
            + "\u3041\u3094\u30A1\u30FA\u3105\u312C\u4E00\u9FA5\uAC00\uD7A3";
    private static final String NC_PART = ""
            + "\u002D\u002E\u0030\u0039\u00B7\u00B7\u02D0\u02D1\u0300\u0345\u0360\u0361\u0387\u0387\u0483\u0486"
            + "\u0591\u05A1\u05A3\u05B9\u05BB\u05BD\u05BF\u05BF\u05C1\u05C2\u05C4\u05C4\u0640\u0640\u064B\u0652"
            + "\u0660\u0669\u0670\u0670\u06D6\u06E4\u06E7\u06E8\u06EA\u06ED\u06F0\u06F9\u0901\u0903\u093C\u093C"
            + "\u093E\u094D\u0951\u0954\u0962\u0963\u0966\u096F\u0981\u0983\u09BC\u09BC\u09BE\u09C4\u09C7\u09C8"
            + "\u09CB\u09CD\u09D7\u09D7\u09E2\u09E3\u09E6\u09EF\u0A02\u0A02\u0A3C\u0A3C\u0A3E\u0A42\u0A47\u0A48"
            + "\u0A4B\u0A4D\u0A66\u0A71\u0A81\u0A83\u0ABC\u0ABC\u0ABE\u0AC5\u0AC7\u0AC9\u0ACB\u0ACD\u0AE6\u0AEF"
            + "\u0B01\u0B03\u0B3C\u0B3C\u0B3E\u0B43\u0B47\u0B48\u0B4B\u0B4D\u0B56\u0B57\u0B66\u0B6F\u0B82\u0B83"
            + "\u0BBE\u0BC2\u0BC6\u0BC8\u0BCA\u0BCD\u0BD7\u0BD7\u0BE7\u0BEF\u0C01\u0C03\u0C3E\u0C44\u0C46\u0C48"
            + "\u0C4A\u0C4D\u0C55\u0C56\u0C66\u0C6F\u0C82\u0C83\u0CBE\u0CC4\u0CC6\u0CC8\u0CCA\u0CCD\u0CD5\u0CD6"
            + "\u0CE6\u0CEF\u0D02\u0D03\u0D3E\u0D43\u0D46\u0D48\u0D4A\u0D4D\u0D57\u0D57\u0D66\u0D6F\u0E31\u0E31"
            + "\u0E34\u0E3A\u0E46\u0E4E\u0E50\u0E59\u0EB1\u0EB1\u0EB4\u0EB9\u0EBB\u0EBC\u0EC6\u0EC6\u0EC8\u0ECD"
            + "\u0ED0\u0ED9\u0F18\u0F19\u0F20\u0F29\u0F35\u0F35\u0F37\u0F37\u0F39\u0F39\u0F3E\u0F3F\u0F71\u0F84"
            + "\u0F86\u0F8B\u0F90\u0F95\u0F97\u0F97\u0F99\u0FAD\u0FB1\u0FB7\u0FB9\u0FB9\u20D0\u20DC\u20E1\u20E1"
            + "\u3005\u3005\u302A\u302F\u3031\u3035\u3099\u309A\u309D\u309E\u30FC\u30FE";

    /**
     * Rule NC: {@code id} with every character that is not allowed at its position in an NCName encoded
     * {@code _xHHHH_} (4 lowercase hex digits of the UTF-16 unit, pwiz style). The result of a non-empty id is an
     * NCName; the encoding is not injective, which {@link #uniqueId} resolves.
     */
    static String ncName(String id) {
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (inRanges(NC_START, c) || i > 0 && inRanges(NC_PART, c)) {
                out.append(c);
            } else {
                out.append("_x").append(Integer.toHexString(c | 0x10000).substring(1)).append('_');
            }
        }
        return out.toString();
    }

    private static boolean inRanges(String ranges, char c) {
        int low = 0;
        int high = ranges.length() / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (c < ranges.charAt(2 * mid)) {
                high = mid - 1;
            } else if (c > ranges.charAt(2 * mid + 1)) {
                low = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /** {@code candidate} if it is not taken, else {@code candidate_2}, {@code candidate_3}, ... (first free); taken. */
    static String uniqueId(String candidate, Set<String> taken) {
        String id = candidate;
        for (int k = 2; !taken.add(id); k++) {
            id = candidate + "_" + k;
        }
        return id;
    }

    /**
     * The id of a spectrum or chromatogram whose own id cannot be written: {@code index=<i>} if it is not taken, else
     * {@code index=<i> mzpeakj=<k>} with the smallest free k from 1; added to {@code taken}.
     */
    static String repairedId(int mzmlIndex, Set<String> taken) {
        String id = "index=" + mzmlIndex;
        for (int k = 1; !taken.add(id); k++) {
            id = "index=" + mzmlIndex + " mzpeakj=" + k;
        }
        return id;
    }

    /**
     * The model ids of the instrument configurations with the given mzML ids, in document order ({@code ids}), and
     * the id each mzML id refers to ({@code map}, document order, the first of repeated ids).
     */
    record IcIds(List<Integer> ids, Map<String, Integer> map, boolean exact) {
    }

    private static final Pattern IC_ID = Pattern.compile("IC([1-9][0-9]{0,9})|IC_(-[1-9][0-9]{0,9})");

    /**
     * Rule IC: if every id is {@code IC<v>} with 1 &lt;= v &lt;= 2^31 (model id v - 1) or {@code IC_<v>} with
     * -2^31 &lt;= v &lt;= -1 (model id v), without leading zeros, and the ids are distinct, these model ids
     * ({@code exact}); otherwise each configuration's position in document order. The ids must not be null.
     */
    static IcIds icIds(List<String> ids) {
        List<Integer> byRule = new ArrayList<>(ids.size());
        for (String id : ids) {
            byRule.add(icRuleId(id));
        }
        boolean exact = !byRule.contains(null) && new HashSet<>(byRule).size() == byRule.size();
        List<Integer> out = new ArrayList<>(ids.size());
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            out.add(exact ? byRule.get(i) : i);
            map.putIfAbsent(ids.get(i), out.get(i));
        }
        return new IcIds(Collections.unmodifiableList(out), Collections.unmodifiableMap(map), exact);
    }

    private static Integer icRuleId(String id) {
        Matcher m = IC_ID.matcher(id);
        if (!m.matches()) {
            return null;
        }
        // at most 10 digits: Long.parseLong cannot overflow, and the int range check is the rule's range
        long n = m.group(1) != null ? Long.parseLong(m.group(1)) - 1 : Long.parseLong(m.group(2));
        return n == (int) n ? (int) n : null;
    }

    /** The mzML id of model instrument configuration {@code n}: {@code IC<n+1>} for n &gt;= 0, else {@code IC_<n>}. */
    static String icId(int n) {
        return n >= 0 ? "IC" + (n + 1L) : "IC_" + n;
    }

    /**
     * Whether {@code id} matches the spectrum id pattern of mzML1.1.0.xsd L830, {@code \S+=\S+( \S+=\S+)*}, where
     * XML Schema's {@code \S} is any character but space, tab, LF and CR: one or more pairs separated by single
     * spaces, each with an {@code =} that has a character before and after it. Checked without a regex, which would
     * backtrack quadratically or recurse once per pair on long ids.
     */
    static boolean spectrumIdValid(String id) {
        if (id.indexOf('\t') >= 0 || id.indexOf('\n') >= 0 || id.indexOf('\r') >= 0) {
            return false;
        }
        for (String pair : id.split(" ", -1)) {
            int equals = pair.indexOf('=', 1);
            if (equals < 0 || equals > pair.length() - 2) {
                return false;
            }
        }
        return true;
    }

    private static final Pattern DATE_TIME = Pattern.compile(
            "(-?(?:[1-9][0-9]{3,9}|0[0-9]{3}))-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])"
                    + "T([01][0-9]|2[0-4]):([0-5][0-9]):([0-5][0-9](?:\\.[0-9]+)?)"
                    + "(?:Z|[+-](?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))?");
    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

    /**
     * Whether {@code text} is a valid xs:dateTime as the JDK's validator judges it: after whitespace collapsing, the
     * lexical form of XML Schema 1.0 with a year in int range other than 0, a day that exists in its month
     * (Gregorian leap years, applied to negative years too), and the seconds read as a double, as that validator
     * reads them: below 60 (so {@code 59.999999999999999} is rejected), and 0 when the hour is 24 (so
     * {@code 24:00:00.} followed by enough zeros and a 1 is accepted).
     */
    static boolean dateTimeValid(String text) {
        Matcher m = DATE_TIME.matcher(collapse(text));
        if (!m.matches()) {
            return false;
        }
        long year = Long.parseLong(m.group(1));
        int month = Integer.parseInt(m.group(2));
        boolean leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
        int days = month == 2 && leap ? 29 : DAYS_IN_MONTH[month - 1];
        double seconds = Double.parseDouble(m.group(6));
        return year != 0 && year == (int) year && Integer.parseInt(m.group(3)) <= days && seconds < 60
                && (!m.group(4).equals("24") || m.group(5).equals("00") && seconds == 0);
    }

    private static final Pattern XML_SPACE_ENDS = Pattern.compile("^[ \\t\\n\\r]+|[ \\t\\n\\r]+$");
    private static final Pattern XML_SPACE = Pattern.compile("[ \\t\\n\\r]+");

    /** XML Schema whitespace collapsing: no leading or trailing space, tab, LF or CR, each inner run one space. */
    private static String collapse(String text) {
        return XML_SPACE.matcher(XML_SPACE_ENDS.matcher(text).replaceAll("")).replaceAll(" ");
    }

    // --- XML repairs ---

    /** A string after a repair, and whether the repair changed it. */
    record Repaired(String value, boolean repaired) {
    }

    /**
     * {@code text} with every character XML 1.0 cannot carry (C0 controls other than tab, LF and CR; U+FFFE; U+FFFF;
     * unpaired surrogates) replaced by U+FFFD. Unchanged text is returned as the same instance.
     */
    static Repaired xmlSafe(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean repaired = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                out.append(c).append(text.charAt(++i));
            } else if (c >= 0x20 ? c < 0xD800 || c > 0xDFFF && c < 0xFFFE : c == '\t' || c == '\n' || c == '\r') {
                out.append(c);
            } else {
                out.append('\uFFFD');
                repaired = true;
            }
        }
        return new Repaired(repaired ? out.toString() : text, repaired);
    }

    // the ASCII characters the JDK's xs:anyURI check escapes before parsing, besides controls, space and DEL
    private static final String URI_ESCAPED = "<>\"{}|\\^`~";
    private static final String UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    // a byte of more than three digits in the IPv4 part of a bracketed host (the only place such a host has a '.')
    private static final Pattern LONG_IPV4_BYTE = Pattern.compile("[0-9]{4}\\.|\\.[0-9]{4}");

    /**
     * Rule URI (design 5.2) for an xs:anyURI value ({@code sourceFile@location}, {@code cv@URI}), to be applied to text
     * XML can carry ({@link #xmlSafe}): {@code text} unchanged when it is valid, judged as the JDK's validator judges
     * (whitespace collapsed; controls, space, DEL, {@code < > " { } | \ ^ `} and {@code ~} and every non-ASCII
     * character percent-encoded as UTF-8; the result must parse as {@link URI}, and a bracketed (IPv6) host must have
     * no zone id, no IPv4 byte of more than three digits and a port of at most 65535, which {@link URI} allows and the
     * validator does not). Stricter than the validator only for a bracketed host with a signed port ({@code :+80},
     * {@code :-0}) or with an IPv4 part ending in {@code .}: these are repaired although the validator accepts them.
     * Otherwise stage 1, if valid: every {@code %} not followed by two hex digits written {@code %25}, {@code [} and
     * {@code ]} written {@code %5B} and {@code %5D}, every {@code #} after the first written {@code %23}. Otherwise
     * stage 2, if valid: the UTF-8 percent-encoding of every character except {@code A-Z a-z 0-9 - . _ ~ /}. Stage 2
     * fails only for {@code //} (an empty authority); then {@code /} is encoded too, which always gives a valid
     * relative reference.
     */
    static Repaired anyUri(String text) {
        if (uriValid(text)) {
            return new Repaired(text, false);
        }
        String stage1 = uriStage1(text);
        if (uriValid(stage1)) {
            return new Repaired(stage1, true);
        }
        String stage2 = percentEncoded(text, true);
        return new Repaired(uriValid(stage2) ? stage2 : percentEncoded(text, false), true);
    }

    private static boolean uriValid(String text) {
        String collapsed = collapse(text);
        StringBuilder escaped = new StringBuilder(collapsed.length());
        for (int i = 0; i < collapsed.length(); ) {
            int c = collapsed.codePointAt(i);
            i += Character.charCount(c);
            if (c > 0x20 && c < 0x7F && URI_ESCAPED.indexOf(c) < 0) {
                escaped.append((char) c);
            } else {
                percentEncode(escaped, c);
            }
        }
        try {
            URI uri = new URI(escaped.toString());
            String host = uri.getHost();
            return host == null || !host.startsWith("[") || host.indexOf('%') < 0
                    && !LONG_IPV4_BYTE.matcher(host).find() && uri.getPort() <= 65535;
        } catch (URISyntaxException | NumberFormatException e) {
            // Java 17's URI throws NumberFormatException for an IPv4 byte over Integer.MAX_VALUE in a bracketed host
            return false;
        }
    }

    private static String uriStage1(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean fragment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && !(i + 2 < text.length() && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2)))) {
                out.append("%25");
            } else if (c == '[' || c == ']') {
                out.append(c == '[' ? "%5B" : "%5D");
            } else if (c == '#' && fragment) {
                out.append("%23");
            } else {
                fragment |= c == '#';
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return "0123456789ABCDEFabcdef".indexOf(c) >= 0;
    }

    private static String percentEncoded(String text, boolean keepSlash) {
        StringBuilder out = new StringBuilder(3 * text.length());
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (UNRESERVED.indexOf(c) >= 0 || keepSlash && c == '/') {
                out.append((char) c);
            } else {
                percentEncode(out, c);
            }
        }
        return out.toString();
    }

    private static void percentEncode(StringBuilder out, int codePoint) {
        for (byte b : new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8)) {
            out.append('%').append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
    }

    // --- Table construction ---

    private static Map<String, String> table(String... accessionNamePairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < accessionNamePairs.length; i += 2) {
            out.put(accessionNamePairs[i], accessionNamePairs[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }

    @SafeVarargs
    private static Map<String, String> names(Map<String, String>... tables) {
        Map<String, String> out = new HashMap<>();
        for (Map<String, String> table : tables) {
            table.forEach(out::putIfAbsent);
        }
        return out;
    }
}
