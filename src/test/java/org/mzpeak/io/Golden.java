package org.mzpeak.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The golden output of the HEAD Python reader for one current fixture ({@link Fixtures#CURRENT}), written by
 * {@code src/test/resources/mzpeak/golden/generate_golden.py}; its README describes the format.
 *
 * <ul>
 *   <li>{@code <name>.tsv}: one {@link Line} per spectrum (S), peak list (P), extra array (X), chromatogram
 *       (C), wavelength spectrum (W) and raw stored spectrum (R), with counts, exact sums, three pairing
 *       sums and the first and last 3 (x, y) pairs.
 *   <li>{@code <name>.arrays.tsv}: every point ({@link FullArray}) of spectrum 0, the first centroid
 *       spectrum, the last spectrum and every chromatogram; an entity above 20,000 points is a {@link Digest}
 *       instead, and then the smallest spectrum is stored in full as well.
 * </ul>
 *
 * {@link Comparator} checks arrays that mzPeakJ decoded against these files with the tolerances of
 * {@link Tolerance}. The returned objects share their arrays; callers must not modify them.
 */
final class Golden {

    /** Directory of the golden files, next to the current fixtures. */
    static final Path DIR = Fixtures.CURRENT.resolve("golden");

    /** Line kinds of the summary file; see the class comment. */
    enum Kind { S, P, X, C, W, R }

    /**
     * One summary line. {@code key} is the entity index (S, P, W, R), the chromatogram id (C) or
     * {@code <owner kind>:<owner key>} (X). {@code arrayType} is null except on X lines, where it is the
     * array_type CURIE, or the array_name for MS:1000786. On X lines x is the owner's main axis and y the extra
     * array. On R lines {@code n} counts the non-null stored m/z cells and {@code nFilled} the null ones; on
     * the other kinds {@code nFilled} counts the points whose main-axis value the null fill produced.
     * {@code head} and {@code tail} hold up to 3 pairs as {@code x0, y0, x1, y1, ...}.
     */
    record Line(Kind kind, String key, String arrayType, long n, long nFilled, double sumX, double sumY,
                double sumXY, double sumIX, double sumIY, double[] head, double[] tail) {
    }

    /** Every point of one entity; {@code filled[i]} is true where the null fill produced {@code x[i]}. */
    record FullArray(Kind kind, String key, double[] x, double[] y, boolean[] filled) {
    }

    /**
     * SHA-256 digests over the little-endian float64 bits (NaN canonicalised) of an entity's arrays, for
     * entities too large to store in full. {@code sha256Extras} is keyed like the X lines' array types.
     */
    record Digest(Kind kind, String key, long n, long nFilled, String sha256X, String sha256Y,
                  Map<String, String> sha256Extras) {
    }

    /**
     * Comparison tolerances (design §2.4): {@code filledAbsDa} for main-axis values the null fill produced,
     * {@code relExact} for sums over stored values, {@code relFilled} for sums once filled points are
     * involved, and {@code relNumpress}, when not NaN, for y values and y sums of lossy Numpress data.
     */
    record Tolerance(double filledAbsDa, double relExact, double relFilled, double relNumpress) {
        static final Tolerance DEFAULT = new Tolerance(5e-5, 1e-12, 1e-9, Double.NaN);
    }

    private static final Pattern HEADER = Pattern.compile("# ([a-z0-9-]+): (.*)");

    private final String name;
    private final Map<String, String> header;
    private final List<Line> lines;
    private final List<FullArray> fullArrays;
    private final List<Digest> digests;

    private Golden(String name, Map<String, String> header, List<Line> lines, List<FullArray> fullArrays,
                   List<Digest> digests) {
        this.name = name;
        this.header = Collections.unmodifiableMap(header);
        this.lines = List.copyOf(lines);
        this.fullArrays = List.copyOf(fullArrays);
        this.digests = List.copyOf(digests);
    }

    /** Load the golden files of {@code fixture}, given as {@code small.mzpeak} or {@code small}. */
    static Golden load(String fixture) {
        String name = fixture.endsWith(".mzpeak") ? fixture.substring(0, fixture.length() - ".mzpeak".length())
                : fixture;
        Map<String, String> header = new LinkedHashMap<>();
        List<Line> lines = new ArrayList<>();
        for (String row : rows(DIR.resolve(name + ".tsv"), header)) {
            lines.add(parseLine(row));
        }
        List<FullArray> full = new ArrayList<>();
        List<Digest> digests = new ArrayList<>();
        PointCollector points = null;
        for (String row : rows(DIR.resolve(name + ".arrays.tsv"), new LinkedHashMap<>())) {
            String[] f = row.split("\t", -1);
            if (f[0].equals("D")) {
                digests.add(parseDigest(f, row));
                continue;
            }
            check(f[0].equals("A") && f.length == 7, row);
            Kind kind = Kind.valueOf(f[1]);
            String key = unescape(f[2]);
            if (points == null || points.kind != kind || !points.key.equals(key)) {
                if (points != null) {
                    full.add(points.toFullArray());
                }
                points = new PointCollector(kind, key);
            }
            check(Integer.parseInt(f[3]) == points.size(), row);
            check(f[6].equals("0") || f[6].equals("1"), row);
            points.add(number(f[4]), number(f[5]), f[6].equals("1"));
        }
        if (points != null) {
            full.add(points.toFullArray());
        }
        return new Golden(name, header, lines, full, digests);
    }

    /** The dataset name without {@code .mzpeak}. */
    String name() {
        return name;
    }

    /** The {@code # key: value} header lines of the summary file, such as {@code reader} and {@code command}. */
    Map<String, String> header() {
        return header;
    }

    /** All summary lines of kind {@code k}, in file order. */
    List<Line> lines(Kind k) {
        return lines.stream().filter(l -> l.kind() == k).toList();
    }

    /** The line of kind {@code k} with {@code key}; throws if several match (X lines: use the 3-argument form). */
    Optional<Line> line(Kind k, String key) {
        List<Line> found = lines.stream().filter(l -> l.kind() == k && l.key().equals(key)).toList();
        if (found.size() > 1) {
            throw new IllegalStateException(found.size() + " " + k + " lines for key " + key + " in " + name);
        }
        return found.stream().findFirst();
    }

    /** The line of kind {@code k} with {@code key} and {@code arrayType} (null for kinds other than X). */
    Optional<Line> line(Kind k, String key, String arrayType) {
        return lines.stream()
                .filter(l -> l.kind() == k && l.key().equals(key) && Objects.equals(l.arrayType(), arrayType))
                .findFirst();
    }

    /** The stored arrays of an entity, if it is stored in full. */
    Optional<FullArray> full(Kind k, String key) {
        return fullArrays.stream().filter(a -> a.kind() == k && a.key().equals(key)).findFirst();
    }

    /** The digest line of an entity, if it is stored as a digest. */
    Optional<Digest> digest(Kind k, String key) {
        return digests.stream().filter(d -> d.kind() == k && d.key().equals(key)).findFirst();
    }

    /** Every entity stored in full, in file order. */
    List<FullArray> fullArrays() {
        return fullArrays;
    }

    /** Every digest line, in file order. */
    List<Digest> digests() {
        return digests;
    }

    /**
     * SHA-256 over the little-endian IEEE-754 bits of {@code values}, NaN canonicalised (the generator's
     * {@code numpy.astype('<f8').tobytes()} after replacing NaN by {@code numpy.nan}).
     */
    static String sha256(double[] values) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        ByteBuffer buf = ByteBuffer.allocate(Long.BYTES * 1024).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            if (!buf.hasRemaining()) {
                md.update(buf.array(), 0, buf.position());
                buf.clear();
            }
            buf.putLong(Double.doubleToLongBits(v));
        }
        md.update(buf.array(), 0, buf.position());
        return HexFormat.of().formatHex(md.digest());
    }

    /** Checks decoded arrays against golden lines, full arrays and digests; returns one message per mismatch. */
    static final class Comparator {

        private static final int MAX_POINT_MESSAGES = 10;

        private Comparator() {
        }

        /**
         * Compare {@code x} and {@code y} with a summary line: {@code n} exactly, the sums and pairing sums
         * relatively, the head and tail pairs per value. For an R line, pass the stored arrays with null cells
         * as NaN. When the line has filled points, the sums use {@code relFilled} and head and tail x values
         * {@code filledAbsDa}. A non-finite sum or value matches only an identical one.
         */
        static List<String> compare(Line e, double[] x, double[] y, Tolerance t) {
            String what = label(e.kind(), e.key(), e.arrayType());
            List<String> out = new ArrayList<>();
            if (x.length != y.length) {
                out.add(what + ": x has " + x.length + " values but y has " + y.length);
                return out;
            }
            boolean stored = e.kind() == Kind.R;
            Stats a = Stats.of(x, y, stored);
            if (a.n != e.n()) {
                out.add(what + ": n expected " + e.n() + " but was " + a.n);
                return out;
            }
            if (stored && a.nNull != e.nFilled()) {
                out.add(what + ": null m/z cells expected " + e.nFilled() + " but was " + a.nNull);
            }
            boolean filled = !stored && e.nFilled() > 0;
            double rel = filled ? t.relFilled() : t.relExact();
            double relY = Double.isNaN(t.relNumpress()) ? rel : Math.max(rel, t.relNumpress());
            checkSum(out, what, "sum_x", e.sumX(), a.sumX, rel);
            checkSum(out, what, "sum_y", e.sumY(), a.sumY, relY);
            checkSum(out, what, "sum_xy", e.sumXY(), a.sumXY, relY);
            checkSum(out, what, "sum_ix", e.sumIX(), a.sumIX, rel);
            checkSum(out, what, "sum_iy", e.sumIY(), a.sumIY, relY);
            double absX = filled ? t.filledAbsDa() : 0.0;
            int k = Math.min(3, x.length);
            checkPairs(out, what, "head", e.head(), x, y, 0, k, absX, t.relNumpress());
            checkPairs(out, what, "tail", e.tail(), x, y, x.length - k, k, absX, t.relNumpress());
            return out;
        }

        /**
         * Compare {@code x} and {@code y} point by point with a full array: filled points within
         * {@code filledAbsDa}, every other x exactly, y exactly or within {@code relNumpress} when set.
         */
        static List<String> compareFull(FullArray e, double[] x, double[] y, Tolerance t) {
            String what = label(e.kind(), e.key(), null);
            List<String> out = new ArrayList<>();
            if (x.length != e.x().length || y.length != e.y().length) {
                out.add(what + ": n expected " + e.x().length + " but x has " + x.length + " and y " + y.length
                        + " values");
                return out;
            }
            int mismatches = 0;
            for (int i = 0; i < x.length; i++) {
                boolean xOk = same(e.x()[i], x[i])
                        || (e.filled()[i] && Math.abs(x[i] - e.x()[i]) <= t.filledAbsDa());
                boolean yOk = yMatches(e.y()[i], y[i], t.relNumpress());
                if (xOk && yOk) {
                    continue;
                }
                if (++mismatches <= MAX_POINT_MESSAGES) {
                    out.add(String.format(Locale.ROOT, "%s: point %d expected (%s, %s)%s but was (%s, %s)", what, i,
                            e.x()[i], e.y()[i], e.filled()[i] ? " filled" : "", x[i], y[i]));
                }
            }
            if (mismatches > MAX_POINT_MESSAGES) {
                out.add(what + ": " + (mismatches - MAX_POINT_MESSAGES) + " more point mismatches");
            }
            return out;
        }

        /**
         * Compare arrays with a digest line: {@code n} exactly and every SHA-256 bit-exactly, including each
         * expected extra array and no unexpected one. Digests are compared only when the line has no filled
         * points; otherwise only {@code n} is checked.
         */
        static List<String> compareDigest(Digest e, double[] x, double[] y, Map<String, double[]> extras) {
            String what = label(e.kind(), e.key(), null);
            List<String> out = new ArrayList<>();
            if (x.length != e.n() || y.length != e.n()) {
                out.add(what + ": n expected " + e.n() + " but x has " + x.length + " and y " + y.length + " values");
                return out;
            }
            if (e.nFilled() > 0) {
                return out;
            }
            checkDigest(out, what, "x", e.sha256X(), x);
            checkDigest(out, what, "y", e.sha256Y(), y);
            Map<String, double[]> actual = extras == null ? Map.of() : extras;
            e.sha256Extras().forEach((key, sha) -> {
                double[] values = actual.get(key);
                if (values == null) {
                    out.add(what + ": extra array " + key + " missing");
                } else if (values.length != e.n()) {
                    out.add(what + ": extra array " + key + " has " + values.length + " values, expected " + e.n());
                } else {
                    checkDigest(out, what, "extra array " + key, sha, values);
                }
            });
            actual.keySet().stream().filter(key -> !e.sha256Extras().containsKey(key)).sorted()
                    .forEach(key -> out.add(what + ": unexpected extra array " + key));
            return out;
        }

        private static void checkDigest(List<String> out, String what, String array, String expected,
                                        double[] values) {
            String actual = sha256(values);
            if (!actual.equals(expected)) {
                out.add(what + ": SHA-256 of " + array + " expected " + expected + " but was " + actual);
            }
        }

        private static void checkSum(List<String> out, String what, String stat, double expected, double actual,
                                     double rel) {
            if (same(expected, actual)) {
                return;
            }
            if (!Double.isFinite(expected) || !Double.isFinite(actual)) {
                out.add(String.format(Locale.ROOT, "%s: %s expected %s but was %s", what, stat, expected, actual));
                return;
            }
            double bound = rel * Math.abs(expected);
            double diff = Math.abs(expected - actual);
            if (!(diff <= bound)) {
                out.add(String.format(Locale.ROOT, "%s: %s expected %s but was %s (difference %s, allowed %s)", what,
                        stat, expected, actual, diff, bound));
            }
        }

        private static void checkPairs(List<String> out, String what, String part, double[] expected, double[] x,
                                       double[] y, int from, int count, double absX, double relY) {
            if (expected.length != 2 * count) {
                out.add(what + ": " + part + " expected " + expected.length / 2 + " pairs but has " + count);
                return;
            }
            for (int p = 0; p < count; p++) {
                double ex = expected[2 * p];
                double ey = expected[2 * p + 1];
                double ax = x[from + p];
                double ay = y[from + p];
                boolean xOk = same(ex, ax) || Math.abs(ax - ex) <= absX;
                if (!xOk || !yMatches(ey, ay, relY)) {
                    out.add(String.format(Locale.ROOT, "%s: %s pair %d (point %d) expected (%s, %s) but was (%s, %s)",
                            what, part, p, from + p, ex, ey, ax, ay));
                }
            }
        }

        private static boolean yMatches(double expected, double actual, double relNumpress) {
            if (same(expected, actual)) {
                return true;
            }
            if (Double.isNaN(relNumpress) || !Double.isFinite(expected) || !Double.isFinite(actual)) {
                return false;
            }
            return Math.abs(expected - actual) <= relNumpress * Math.abs(expected);
        }

        /** Equal values, with NaN equal to NaN and 0.0 equal to -0.0. */
        private static boolean same(double expected, double actual) {
            return expected == actual || (Double.isNaN(expected) && Double.isNaN(actual));
        }
    }

    /** Counts and sums as the generator computes them; R lines skip NaN (null) cells. */
    private static final class Stats {
        final long n;
        final long nNull;
        final double sumX;
        final double sumY;
        final double sumXY;
        final double sumIX;
        final double sumIY;

        private Stats(long n, long nNull, double sumX, double sumY, double sumXY, double sumIX, double sumIY) {
            this.n = n;
            this.nNull = nNull;
            this.sumX = sumX;
            this.sumY = sumY;
            this.sumXY = sumXY;
            this.sumIX = sumIX;
            this.sumIY = sumIY;
        }

        static Stats of(double[] x, double[] y, boolean skipNull) {
            Sum sx = new Sum();
            Sum sy = new Sum();
            Sum sxy = new Sum();
            Sum six = new Sum();
            Sum siy = new Sum();
            long nNull = 0;
            for (int i = 0; i < x.length; i++) {
                boolean hasX = !skipNull || !Double.isNaN(x[i]);
                boolean hasY = !skipNull || !Double.isNaN(y[i]);
                if (hasX) {
                    sx.add(x[i]);
                    six.add((double) i * x[i]);
                } else {
                    nNull++;
                }
                if (hasY) {
                    sy.add(y[i]);
                    siy.add((double) i * y[i]);
                }
                if (hasX && hasY) {
                    sxy.add(x[i] * y[i]);
                }
            }
            return new Stats(x.length - nNull, nNull, sx.value(), sy.value(), sxy.value(), six.value(),
                    siy.value());
        }
    }

    /**
     * Neumaier-compensated sum, within a few ulp of the generator's exactly rounded {@code math.fsum}. With a
     * non-finite term it returns the plain sum, as the generator does.
     */
    private static final class Sum {
        private double sum;
        private double compensation;
        private double plain;

        void add(double v) {
            plain += v;
            double t = sum + v;
            compensation += Math.abs(sum) >= Math.abs(v) ? (sum - t) + v : (v - t) + sum;
            sum = t;
        }

        double value() {
            return Double.isFinite(plain) ? sum + compensation : plain;
        }
    }

    /** Accumulates the A lines of one entity. */
    private static final class PointCollector {
        final Kind kind;
        final String key;
        private final DoubleArrayBuilder x = new DoubleArrayBuilder();
        private final DoubleArrayBuilder y = new DoubleArrayBuilder();
        private boolean[] filled = new boolean[16];

        PointCollector(Kind kind, String key) {
            this.kind = kind;
            this.key = key;
        }

        int size() {
            return x.size();
        }

        void add(double xv, double yv, boolean f) {
            if (x.size() == filled.length) {
                filled = Arrays.copyOf(filled, filled.length * 2);
            }
            filled[x.size()] = f;
            x.add(xv);
            y.add(yv);
        }

        FullArray toFullArray() {
            return new FullArray(kind, key, x.toArray(), y.toArray(), Arrays.copyOf(filled, x.size()));
        }
    }

    private static List<String> rows(Path file, Map<String, String> header) {
        List<String> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String row = in.readLine(); row != null; row = in.readLine()) {
                if (row.startsWith("#")) {
                    Matcher m = HEADER.matcher(row);
                    if (m.matches()) {
                        header.put(m.group(1), m.group(2));
                    }
                } else if (!row.isEmpty()) {
                    rows.add(row);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden file " + file, e);
        }
        return rows;
    }

    private static Line parseLine(String row) {
        String[] f = row.split("\t", -1);
        check(f.length == 12, row);
        String arrayType = f[2].equals("-") ? null : unescape(f[2]);
        return new Line(Kind.valueOf(f[0]), unescape(f[1]), arrayType, Long.parseLong(f[3]), Long.parseLong(f[4]),
                number(f[5]), number(f[6]), number(f[7]), number(f[8]), number(f[9]), pairs(f[10]), pairs(f[11]));
    }

    private static Digest parseDigest(String[] f, String row) {
        check(f.length == 8, row);
        Map<String, String> extras = new LinkedHashMap<>();
        if (!f[7].equals("-")) {
            for (String pair : unescape(f[7]).split(";")) {
                int eq = pair.lastIndexOf('=');
                check(eq > 0, row);
                extras.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return new Digest(Kind.valueOf(f[1]), unescape(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]), f[5], f[6],
                Collections.unmodifiableMap(extras));
    }

    private static double[] pairs(String field) {
        if (field.equals("-")) {
            return new double[0];
        }
        String[] parts = field.split(",");
        double[] out = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = number(parts[i]);
        }
        return out;
    }

    /** Parse Python's {@code %.17g}, including {@code nan}, {@code inf} and {@code -inf}. */
    static double number(String s) {
        return switch (s) {
            case "nan" -> Double.NaN;
            case "inf" -> Double.POSITIVE_INFINITY;
            case "-inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(s);
        };
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(++i);
            out.append(switch (next) {
                case 't' -> '\t';
                case 'n' -> '\n';
                case 'r' -> '\r';
                default -> next;
            });
        }
        return out.toString();
    }

    private static String label(Kind kind, String key, String arrayType) {
        return kind + " " + key + (arrayType == null ? "" : " [" + arrayType + "]");
    }

    private static void check(boolean ok, String row) {
        if (!ok) {
            throw new IllegalStateException("malformed golden line: " + row);
        }
    }
}
