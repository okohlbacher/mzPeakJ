package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.SignalPlan.Extra;
import org.mzpeak.io.parquet.ParquetGroups;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.stream.Collectors;

/**
 * Decodes a chromatogram data member into time and intensity arrays per chromatogram index, reading only the
 * columns its {@link SignalPlan} names: the prefix struct, the index field, the time axis, the intensity columns and
 * the extra arrays (design §8.4). The rows are read in the plan's layout:
 * <ul>
 *   <li><b>point</b>, one row per time point: the time value, and the first non-null value among the plan's
 *       value-bearing intensity columns in index order, such as has_uv's {@code intensity_f32_au} absorbance where
 *       {@code intensity} is null. A null time reads as NaN, an intensity without any value as 0.
 *   <li><b>chunk</b>, one row per chunk: the time axis is the chunk start followed by the running sum of the chunk
 *       values as deltas; a null delta adds 0. The intensities are the first value-bearing intensity list that
 *       carries a value (non-null and not empty), else the values of the first MS-Numpress SLOF column whose bytes
 *       decode to any (an empty payload, or a SLOF header without values, has none), else none; every list and SLOF
 *       column of the plan is considered, in index order. A null intensity reads as 0, missing ones are padded with
 *       0 and surplus ones dropped. A chunk without a start is skipped.
 * </ul>
 * Rows without the prefix struct or an index are skipped; the chunks and points of one index are appended in row
 * order. These are Q-U5's semantics; sharing the spectrum chunk decoder would change them (deferred).
 *
 * <p><b>Time unit</b> (MZ-3): the times are in minutes. The time values column and the chunk start column are each
 * converted from the unit of their plan entry: minutes or no unit as stored, seconds, milliseconds and hours
 * converted (a chunk's deltas before they are summed). Any other unit throws an {@link MzPeakException} naming the
 * member, the column and the unit, before any row is read.
 *
 * <p>What cannot be decoded throws an {@link MzPeakException} instead of reading as wrong or empty arrays: an index
 * beyond the supported range (a negative value in a signed column, or a uint64 with the high bit set; an unsigned
 * INT32 is read without sign extension, see {@link ParquetGroups#optIndexKey}); on the first decoded
 * row, a plan that names no time values column (point layout) or neither a value-bearing intensity column nor a SLOF
 * column; a chunk whose {@code chunk_encoding} is neither null nor MS:1003089 (delta); a chunk in which a chunk
 * transform column of the time axis (such as Numpress linear time) carries a value; and a chunk in which no intensity
 * list carries a value and no SLOF column decodes to values, but another intensity chunk transform column of the plan
 * carries one: any MS-Numpress PIC (MS:1002313) column, a column of another transform, or one that names none.
 *
 * <p><b>Extra arrays</b> (AP-9) are the plan's {@link SignalPlan#extras() extras}, under the plan's keys, parallel
 * to the time axis: one cell per point row, and per chunk row a list that aligns 1:1 with the time values the chunk
 * decodes to. A null cell, and every cell of a null list, reads as NaN. A key whose cells in a chromatogram are all
 * NaN gets no entry for that chromatogram. A drift-time array ({@link ArrayUnits#DRIFT_TIME_ARRAYS}) is in
 * milliseconds, as for spectra: each cell is converted from the unit of its plan entry, milliseconds or no unit as
 * stored, seconds and minutes converted. Extras never throw; with a WARNING,
 * <ul>
 *   <li>an extra stored in a chunk transform column is dropped from the member (transforms are not decoded for
 *       chromatograms);
 *   <li>a drift-time array in any other unit is dropped from the member;
 *   <li>an extra whose cells are not numbers is dropped from the member;
 *   <li>a chunk list of another length than its chunk's time values drops that key for that chromatogram.
 * </ul>
 *
 * <p><b>Intensity unit</b> (AP-9): the unit of the plan entry that supplied a chromatogram's values, the intensity
 * column or SLOF column chosen above; a chunk without intensities takes the first of them that is non-null (an empty
 * list or payload, or a SLOF header). It is null when no entry supplied a value, when the entries that did differ in
 * unit (with a WARNING), and for an {@link ArrayIndex.Source#INFERRED INFERRED} plan, whose entries carry no unit.
 */
final class ChromatogramDataDecoder {

    private static final System.Logger LOG = System.getLogger(ChromatogramDataDecoder.class.getName());

    /** MS:1003089, delta prediction: the only chunk encoding the time axis is decoded with. */
    private static final String DELTA_ENCODING = "MS:1003089";

    /**
     * The decoded arrays of one chromatogram.
     *
     * @param intensityUnit the unit of the entry that supplied the intensities, or null
     * @param arrays        the extra arrays by key, in plan order, each as long as {@code time}
     */
    record Decoded(double[] time, double[] intensity, String intensityUnit, Map<String, double[]> arrays) {
    }

    /** The member name in errors. */
    private final String member;
    private final String prefix;
    private final String indexField;
    private final boolean chunked;
    private final String timeField;
    private final String startField;
    /** The conversions of the time values column and of the chunk start column to minutes. */
    private final DoubleUnaryOperator timeToMinutes;
    private final DoubleUnaryOperator startToMinutes;
    private final String encodingField;
    /** The chunk transforms of the time axis, CURIE to column; none of them is decoded. */
    private final Map<String, String> axisTransforms;
    /** The value-bearing intensity columns, in index order. */
    private final List<Column> intensityColumns;
    /** The intensity columns stored as the MS-Numpress SLOF chunk transform, in index order. */
    private final List<Column> slofColumns;
    /**
     * The other intensity chunk transform columns, in index order: MS-Numpress PIC, any other transform, and one that
     * names none. None of them is decoded.
     */
    private final List<Column> undecodedIntensity;
    /** Why the plan cannot decode a row, thrown on the first decoded row; null when it can. */
    private final String unreadablePlan;
    private final boolean unitsDeclared;
    /** The decodable extras, in plan order. */
    private final List<Extra> extras;
    /** Per extra: its cells did not read as numbers, or its unit is not converted, so it is dropped from the member. */
    private final boolean[] unreadable;
    /** Per extra: the conversion of its stored cells, to milliseconds for a drift-time array, else as stored. */
    private final DoubleUnaryOperator[] conversions;

    private ChromatogramDataDecoder(SignalPlan plan, String member) {
        this.member = member;
        this.prefix = plan.prefix();
        this.indexField = plan.indexField();
        this.chunked = plan.layout() == SignalPlan.Layout.CHUNK;
        this.timeField = field(plan.axis().values());
        this.startField = field(plan.axis().start());
        this.timeToMinutes = toMinutes(plan.axis().values(), member);
        this.startToMinutes = toMinutes(plan.axis().start(), member);
        this.encodingField = field(plan.axis().encoding());
        this.axisTransforms = plan.axis().transformFields();
        this.intensityColumns = plan.intensities().stream()
                .filter(c -> c.format().valueBearing())
                .toList();
        this.slofColumns = plan.intensities().stream()
                .filter(ChromatogramDataDecoder::isSlof)
                .toList();
        this.undecodedIntensity = plan.intensities().stream()
                .filter(c -> c.format() == BufferFormat.CHUNK_TRANSFORM && !isSlof(c))
                .toList();
        if (!chunked && timeField == null) {
            unreadablePlan = "names no time values column";
        } else if (intensityColumns.isEmpty() && slofColumns.isEmpty()) {
            unreadablePlan = "names neither a value-bearing intensity column nor an MS-Numpress SLOF column";
        } else {
            unreadablePlan = null;
        }
        this.unitsDeclared = plan.source() == ArrayIndex.Source.FOOTER;
        List<Extra> decodable = new ArrayList<>();
        for (Extra extra : plan.extras().values()) {
            if (extra.values() != null && extra.transformFields().isEmpty()) {
                decodable.add(extra);
            } else {
                LOG.log(Level.WARNING, () -> "chromatogram extra array '" + extra.key() + "' is stored in chunk"
                        + " transform columns " + transformColumns(extra) + ", which are not decoded for"
                        + " chromatograms; the array is dropped");
            }
        }
        this.extras = List.copyOf(decodable);
        this.unreadable = new boolean[extras.size()];
        this.conversions = new DoubleUnaryOperator[extras.size()];
        for (int k = 0; k < extras.size(); k++) {
            Extra extra = extras.get(k);
            conversions[k] = DoubleUnaryOperator.identity();
            if (ArrayUnits.DRIFT_TIME_ARRAYS.contains(extra.arrayType())) {
                try {
                    conversions[k] = ArrayUnits.toMilliseconds(extra.unit(), extra.arrayType(), member,
                            extra.values().path());
                } catch (MzPeakException e) {
                    markUnreadable(k, e);
                }
            }
        }
    }

    /**
     * Groups the rows of {@code dataFile} by chromatogram index, in the order the indices first occur.
     *
     * @param member the name of the member in errors
     * @throws MzPeakException when a time column is in a unit that is not converted (the message names the member);
     *                         when SLOF bytes do not decode, or a row cannot be decoded (see the class comment); the
     *                         message names the chromatogram index
     */
    static Map<Long, Decoded> decode(InputFile dataFile, String member, SignalPlan plan) {
        ChromatogramDataDecoder decoder = new ChromatogramDataDecoder(plan, member);
        Map<Long, Accumulator> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> decoder.accept(row, acc));

        Map<Long, Decoded> out = new LinkedHashMap<>();
        acc.forEach((idx, a) -> out.put(idx, decoder.decoded(idx, a)));
        return out;
    }

    private static String field(Column column) {
        return column == null ? null : column.field();
    }

    /** The conversion of the time values in {@code column} to minutes; none when there is no column. */
    private static DoubleUnaryOperator toMinutes(Column column, String member) {
        return column == null ? DoubleUnaryOperator.identity()
                : ArrayUnits.toMinutes(column.unit(), member, column.path());
    }

    /** True if {@code column} is stored as the MS-Numpress SLOF chunk transform. */
    private static boolean isSlof(Column column) {
        return column.format() == BufferFormat.CHUNK_TRANSFORM
                && MSNumpress.ACC_NUMPRESS_SLOF.equals(column.transform());
    }

    private String transformColumns(Extra extra) {
        return extra.transformFields().entrySet().stream()
                .map(e -> prefix + "." + e.getValue() + " (" + e.getKey() + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private void accept(Group row, Map<Long, Accumulator> acc) {
        Group group = ParquetGroups.optGroup(row, prefix);
        if (group == null) {
            return;
        }
        Long idx = ParquetGroups.optIndexKey(group, indexField, member);
        if (idx == null) {
            return;
        }
        if (unreadablePlan != null) {
            throw new MzPeakException("the chromatogram signal plan " + unreadablePlan + ", so the rows of "
                    + indexField + " " + idx + " cannot be read");
        }
        if (chunked) {
            acceptChunk(group, idx, acc);
        } else {
            acceptPoint(group, idx, acc);
        }
    }

    private void acceptPoint(Group point, long idx, Map<Long, Accumulator> acc) {
        Double t = ParquetGroups.optDouble(point, timeField);
        Accumulator a = accumulator(acc, idx);
        Double in = pointIntensity(point, a);
        a.time.add(t  == null ? Double.NaN : timeToMinutes.applyAsDouble(t));
        a.intensity.add(in == null ? 0.0 : in);
        for (int k = 0; k < extras.size(); k++) {
            if (unreadable[k]) {
                continue;
            }
            Double cell;
            try {
                cell = ParquetGroups.optDouble(point, extras.get(k).values().field());
            } catch (RuntimeException e) {
                markUnreadable(k, e);
                continue;
            }
            a.add(k, cell == null ? Double.NaN : conversions[k].applyAsDouble(cell));
        }
    }

    /**
     * The first non-null value among the plan's value-bearing intensity columns, or {@code null} when none has one;
     * the column that has it is noted on {@code a}.
     */
    private Double pointIntensity(Group point, Accumulator a) {
        for (Column column : intensityColumns) {
            Double v = ParquetGroups.optDouble(point, column.field());
            if (v != null) {
                a.supplied(column);
                return v;
            }
        }
        return null;
    }

    private void acceptChunk(Group chunk, long idx, Map<Long, Accumulator> acc) {
        String encoding = encodingField == null ? null : ParquetGroups.optString(chunk, encodingField);
        if (encoding != null && !encoding.equals(DELTA_ENCODING)) {
            throw new MzPeakException("unsupported chunk encoding " + encoding + " for " + indexField + " " + idx);
        }
        for (Map.Entry<String, String> transform : axisTransforms.entrySet()) {
            if (ChunkCodecs.carriesValue(chunk, transform.getValue())) {
                throw new MzPeakException(indexField + " " + idx + " stores its time axis with the chunk transform "
                        + transform.getKey() + " (column " + transform.getValue() + "), which is not decoded for"
                        + " chromatograms");
            }
        }
        // Time axis: chunk start + cumulative sum of the chunk values
        Double start = startField == null ? null : ParquetGroups.optDouble(chunk, startField);
        if (start == null) {
            return;
        }
        Accumulator a = accumulator(acc, idx);
        double[] deltas    = timeField == null ? new double[0] : ParquetGroups.doubleListNullable(chunk, timeField);
        double[] intensity = chunkIntensity(chunk, idx, a);

        // Reconstruct time axis in minutes: time[0] = start; time[i+1] = time[i] + deltas[i]
        // intensity.length == deltas.length + 1 (intensity[0] is at the chunk start)
        int n = deltas.length + 1;
        double[] time = new double[n];
        time[0] = startToMinutes.applyAsDouble(start);
        for (int i = 0; i < deltas.length; i++) {
            double d = deltas[i];
            time[i + 1] = time[i] + (Double.isNaN(d) ? 0.0 : timeToMinutes.applyAsDouble(d));
        }

        // Pair with intensity; guard against length mismatch (shouldn't happen in well-formed files)
        for (int i = 0; i < n; i++) {
            a.time.add(time[i]);
            double in = (i < intensity.length) ? intensity[i] : 0.0;
            a.intensity.add(Double.isNaN(in) ? 0.0 : in);
        }
        for (int k = 0; k < extras.size(); k++) {
            if (!unreadable[k] && !a.dropped(k)) {
                acceptChunkExtra(chunk, idx, a, k, n);
            }
        }
    }

    /** Appends the {@code n} cells of extra {@code k} for one chunk; a null list gives NaN cells. */
    private void acceptChunkExtra(Group chunk, long idx, Accumulator a, int k, int n) {
        Extra extra = extras.get(k);
        double[] cells;
        try {
            cells = ParquetGroups.has(chunk, extra.values().field())
                    ? ParquetGroups.doubleListNullable(chunk, extra.values().field()) : null;
        } catch (RuntimeException e) {
            markUnreadable(k, e);
            return;
        }
        if (cells == null) {
            for (int i = 0; i < n; i++) {
                a.add(k, Double.NaN);
            }
        } else if (cells.length != n) {
            a.drop(k);
            LOG.log(Level.WARNING, () -> indexField + " " + idx + ": extra array '" + extra.key() + "' ("
                    + extra.values().path() + ") has " + cells.length + " values in a chunk of " + n
                    + " time points; the array is dropped for this chromatogram");
        } else {
            for (double cell : cells) {
                a.add(k, conversions[k].applyAsDouble(cell));
            }
        }
    }

    /**
     * Intensity values of a chunk row: the first value-bearing intensity list of the plan that carries a value,
     * otherwise the values of the first SLOF column whose bytes decode to any; the column used is noted on {@code a}.
     * Returns an empty array when none has values; an empty list or SLOF payload (or a SLOF header without values) is
     * then still noted on {@code a}.
     *
     * @throws MzPeakException when SLOF bytes do not decode, or when no list or SLOF column has values but an intensity
     *                         chunk transform column that is not decoded (PIC or another) carries a value
     */
    private double[] chunkIntensity(Group chunk, long chromatogramIndex, Accumulator a) {
        Column list = carrying(chunk, intensityColumns);
        if (list != null) {
            a.supplied(list);
            return ParquetGroups.doubleListNullable(chunk, list.field());
        }
        for (Column slof : slofColumns) {
            double[] values = slofValues(chunk, slof, chromatogramIndex);
            if (values.length > 0) {
                a.supplied(slof);
                return values;
            }
        }
        Column undecoded = carrying(chunk, undecodedIntensity);
        if (undecoded != null) {
            throw undecodedIntensityError(undecoded, chromatogramIndex);
        }
        // No intensities: an empty list or SLOF payload still names the unit.
        Column empty = present(chunk, intensityColumns);
        if (empty == null) {
            empty = present(chunk, slofColumns);
        }
        if (empty != null) {
            a.supplied(empty);
        }
        return new double[0];
    }

    /**
     * The decoded values of the SLOF column {@code slof} in {@code chunk}; none when it is null, empty, or a SLOF
     * header without values.
     */
    private double[] slofValues(Group chunk, Column slof, long chromatogramIndex) {
        if (!ParquetGroups.has(chunk, slof.field())) {
            return new double[0];
        }
        byte[] bytes = ParquetGroups.byteList(chunk, slof.field());
        if (bytes.length == 0) {
            return new double[0];
        }
        try {
            return MSNumpress.decode(MSNumpress.ACC_NUMPRESS_SLOF, bytes, bytes.length);
        } catch (RuntimeException e) {
            throw new MzPeakException("Failed to Numpress-decode intensity for " + indexField + " "
                    + chromatogramIndex, e);
        }
    }

    /** The first of {@code columns} that carries a value in {@code chunk} (non-null and not empty), or null. */
    private static Column carrying(Group chunk, List<Column> columns) {
        for (Column column : columns) {
            if (ChunkCodecs.carriesValue(chunk, column.field())) {
                return column;
            }
        }
        return null;
    }

    /** The first of {@code columns} that is non-null in {@code chunk}, or null. */
    private static Column present(Group chunk, List<Column> columns) {
        for (Column column : columns) {
            if (ParquetGroups.has(chunk, column.field())) {
                return column;
            }
        }
        return null;
    }

    /** The error for a chunk whose only intensities are in {@code column}, a chunk transform that is not decoded. */
    private MzPeakException undecodedIntensityError(Column column, long chromatogramIndex) {
        if (MSNumpress.ACC_NUMPRESS_PIC.equals(column.transform())) {
            // PIC chromatograms are not decoded (deferred: MSNumpress.decode() rejects PIC payloads < 8 bytes)
            return new MzPeakException("PIC-encoded chromatogram intensities are not supported (column "
                    + column.field() + ") for " + indexField + " " + chromatogramIndex);
        }
        String transform = column.transform() == null
                ? "an unnamed chunk transform" : "the chunk transform " + column.transform();
        return new MzPeakException(indexField + " " + chromatogramIndex + " stores its intensities with " + transform
                + " (column " + column.field() + "), which is not decoded for chromatograms");
    }

    /** Drops extra {@code k} from the member, with a WARNING the first time. */
    private void markUnreadable(int k, RuntimeException e) {
        unreadable[k] = true;
        Extra extra = extras.get(k);
        LOG.log(Level.WARNING, () -> "chromatogram extra array '" + extra.key() + "' (" + extra.values().path()
                + ") cannot be read as numbers (" + e.getMessage() + "); the array is dropped");
    }

    private Accumulator accumulator(Map<Long, Accumulator> acc, long idx) {
        return acc.computeIfAbsent(idx, k -> new Accumulator(extras.size()));
    }

    /** The arrays of one chromatogram, with its extras that hold a number and its intensity unit. */
    private Decoded decoded(long idx, Accumulator a) {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        for (int k = 0; k < extras.size(); k++) {
            if (!unreadable[k] && !a.dropped(k) && a.hasNumber[k]) {
                arrays.put(extras.get(k).key(), a.cells[k].toArray());
            }
        }
        return new Decoded(a.time.toArray(), a.intensity.toArray(), intensityUnit(idx, a), arrays);
    }

    private String intensityUnit(long idx, Accumulator a) {
        if (!unitsDeclared || a.units.isEmpty()) {
            return null;
        }
        if (a.units.size() > 1) {
            LOG.log(Level.WARNING, () -> indexField + " " + idx + ": the intensities come from columns of"
                    + " different units " + a.units + "; the intensity unit is left unset");
            return null;
        }
        return a.units.iterator().next();
    }

    /** The arrays of one chromatogram index under construction. */
    private static final class Accumulator {
        final DoubleArrayBuilder time = new DoubleArrayBuilder();
        final DoubleArrayBuilder intensity = new DoubleArrayBuilder();
        /** Per extra: its cells, or null once the extra is dropped for this chromatogram. */
        final DoubleArrayBuilder[] cells;
        /** Per extra: some cell is not NaN. */
        final boolean[] hasNumber;
        /** The units of the intensity columns that supplied values, in first-use order (null allowed). */
        final Set<String> units = new LinkedHashSet<>();
        private Column lastSupplier;

        Accumulator(int extraCount) {
            cells = new DoubleArrayBuilder[extraCount];
            hasNumber = new boolean[extraCount];
            for (int k = 0; k < extraCount; k++) {
                cells[k] = new DoubleArrayBuilder();
            }
        }

        void supplied(Column column) {
            if (column != lastSupplier) {
                lastSupplier = column;
                units.add(column.unit());
            }
        }

        void add(int k, double cell) {
            cells[k].add(cell);
            hasNumber[k] |= !Double.isNaN(cell);
        }

        boolean dropped(int k) {
            return cells[k] == null;
        }

        void drop(int k) {
            cells[k] = null;
        }
    }
}
