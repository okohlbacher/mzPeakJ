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
 *   <li><b>chunk</b>, one row per chunk, decoded by {@link ChunkRowDecoder} as a spectrum chunk row with the time
 *       as its main axis (C4): basic (MS:1000576), delta (null or MS:1003089, null-aware: the value after a null is
 *       absolute), coordinate grid and MS-Numpress linear time, with the intensities of the first SLOF column whose
 *       payload decodes to values, else of the first such PIC column, else of the plain list; an intensity list of
 *       another length than the times, or than the times plus the chunk start, throws. A null intensity reads as 0,
 *       and a chunk without a start decodes from time 0. The null-marked times of each chunk row are filled by the
 *       spec's rule ({@link NullFill}) when the reader's {@code reconstructProfile} option is set, else dropped with
 *       their intensities and extra cells; a time that cannot be filled throws.
 * </ul>
 * Rows without the prefix struct or an index are skipped; the chunks and points of one index are appended in row
 * order.
 *
 * <p><b>Time unit</b> (D-12): the times are as stored, in the member's time unit ({@link #timeUnit}): the unit of
 * the time values column's plan entry, else (a chunk layout without a values column) that of the chunk start
 * column, minute (UO:0000031) when the chosen entry names none. A chunk start column whose unit differs from the
 * values column's (no unit taken as minute) is converted to it, both among minute, second, millisecond and hour;
 * another unit of either throws an {@link MzPeakException} naming the member, the column and the unit, before any
 * row is read. A time unit that is not converted is otherwise passed through with the stored values.
 *
 * <p>What cannot be decoded throws an {@link MzPeakException} instead of reading as wrong or empty arrays: an index
 * beyond the supported range (a negative value in a signed column, or a uint64 with the high bit set; an unsigned
 * INT32 is read without sign extension, see {@link ParquetGroups#optIndexKey}); on the first decoded
 * row, a plan that names no time values column (point layout) or neither a value-bearing intensity column nor a SLOF
 * or PIC column; and a chunk row that {@link ChunkRowDecoder} cannot decode, such as one of another
 * {@code chunk_encoding} or one in which only an intensity chunk transform column that is not decoded carries a value;
 * and a chunk row in which a time axis chunk transform column other than MS-Numpress linear (MS:1002312) or the
 * coordinate grid (MS:1003826) carries a value.
 *
 * <p><b>Extra arrays</b> (AP-9) are the plan's {@link SignalPlan#extras() extras}, under the plan's keys, parallel
 * to the time axis: one cell per point row, and per chunk row a list that aligns 1:1 with the time values the chunk
 * decodes to. A null cell, and every cell of a null list, reads as NaN. A key whose cells in a chromatogram are all
 * NaN gets no entry for that chromatogram. A drift-time array ({@link ArrayUnits#DRIFT_TIME_ARRAYS}) is in
 * milliseconds, as for spectra: each cell is converted from the unit of its plan entry, milliseconds or no unit as
 * stored, seconds and minutes converted. Extras never throw; with a WARNING,
 * <ul>
 *   <li>an extra stored in a chunk transform column is dropped from the member (transforms are not decoded for
 *       chromatogram extras);
 *   <li>a drift-time array in any other unit is dropped from the member;
 *   <li>an extra whose cells are not numbers is dropped from the member;
 *   <li>a chunk list of another length than its chunk's time values drops that key for that chromatogram.
 * </ul>
 *
 * <p><b>Intensity unit</b> (AP-9): the unit of the plan entry that supplied a chromatogram's values, the intensity
 * column, SLOF or PIC column chosen above; a chunk without intensities takes the first of them that is non-null (an
 * empty list or payload, or a SLOF header). It is null when no entry supplied a value, when the entries that did
 * differ in unit (with a WARNING), and for an {@link ArrayIndex.Source#INFERRED INFERRED} plan, whose entries carry
 * no unit.
 */
final class ChromatogramDataDecoder {

    private static final System.Logger LOG = System.getLogger(ChromatogramDataDecoder.class.getName());

    /** UO:0000031 minute, the time unit when the plan names none. */
    private static final String MINUTE = "UO:0000031";

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
    /** The chunk row decoder; null for the point layout. */
    private final ChunkRowDecoder chunks;
    /**
     * The time axis chunk transforms that {@link ChunkRowDecoder} does not decode (neither MS-Numpress linear nor the
     * coordinate grid), CURIE to column.
     */
    private final Map<String, String> undecodedAxisTransforms;
    /** Whether null-marked chunk times are filled rather than dropped. */
    private final boolean reconstructProfile;
    private final String timeField;
    /** The value-bearing intensity columns, in index order. */
    private final List<Column> intensityColumns;
    /** The plan's intensity columns by field, for the unit of the column a chunk row's intensities came from. */
    private final Map<String, Column> intensityByField = new LinkedHashMap<>();
    /** Why the plan cannot decode a row, thrown on the first decoded row; null when it can. */
    private final String unreadablePlan;
    private final boolean unitsDeclared;
    /** The decodable extras, in plan order. */
    private final List<Extra> extras;
    /** Per extra: its cells did not read as numbers, or its unit is not converted, so it is dropped from the member. */
    private final boolean[] unreadable;
    /** Per extra: the conversion of its stored cells, to milliseconds for a drift-time array, else as stored. */
    private final DoubleUnaryOperator[] conversions;

    private ChromatogramDataDecoder(SignalPlan plan, String member, boolean reconstructProfile) {
        this.member = member;
        this.prefix = plan.prefix();
        this.indexField = plan.indexField();
        this.chunks = plan.layout() == SignalPlan.Layout.CHUNK
                ? new ChunkRowDecoder(plan, startConversion(plan.axis(), member)) : null;
        Map<String, String> undecodedAxis = new LinkedHashMap<>(plan.axis().transformFields());
        undecodedAxis.remove(MSNumpress.ACC_NUMPRESS_LINEAR);
        undecodedAxis.remove(ArrayIndex.GRID_TRANSFORM);
        this.undecodedAxisTransforms = undecodedAxis;
        this.reconstructProfile = reconstructProfile;
        this.timeField = field(plan.axis().values());
        this.intensityColumns = plan.intensities().stream()
                .filter(c -> c.format().valueBearing())
                .toList();
        boolean numpress = false;
        for (Column column : plan.intensities()) {
            intensityByField.putIfAbsent(column.field(), column);
            numpress |= isSlofOrPic(column);
        }
        if (chunks == null && timeField == null) {
            unreadablePlan = "names no time values column";
        } else if (intensityColumns.isEmpty() && !numpress) {
            unreadablePlan = "names neither a value-bearing intensity column nor an MS-Numpress SLOF or PIC column";
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
     * @param member             the name of the member in errors
     * @param reconstructProfile whether the null-marked chunk times are filled, else dropped with their intensities
     *                           and extra cells
     * @throws MzPeakException when the chunk start and time values columns differ in unit and one of them is not
     *                         converted (the message names the member);
     *                         when Numpress bytes do not decode, a row cannot be decoded (see the class comment) or a
     *                         null-marked time cannot be filled; the message names the chromatogram index
     */
    static Map<Long, Decoded> decode(InputFile dataFile, String member, SignalPlan plan, boolean reconstructProfile) {
        ChromatogramDataDecoder decoder = new ChromatogramDataDecoder(plan, member, reconstructProfile);
        Map<Long, Accumulator> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> decoder.accept(row, acc));

        Map<Long, Decoded> out = new LinkedHashMap<>();
        acc.forEach((idx, a) -> out.put(idx, decoder.decoded(idx, a)));
        return out;
    }

    private static String field(Column column) {
        return column == null ? null : column.field();
    }

    /**
     * The time unit of the chromatograms of a member decoded with {@code plan} (see the class comment): the unit of
     * the time values column, else of the chunk start column, else minute (UO:0000031).
     */
    static String timeUnit(SignalPlan plan) {
        Column column = plan.axis().values() != null ? plan.axis().values() : plan.axis().start();
        return unit(column);
    }

    /** The unit of {@code column}, minute when it names none or there is no column. */
    private static String unit(Column column) {
        return column == null || column.unit() == null ? MINUTE : column.unit();
    }

    /**
     * The conversion of the chunk start column of {@code axis} to the unit of its time values column; none when it
     * lacks either.
     */
    private static DoubleUnaryOperator startConversion(SignalPlan.Axis axis, String member) {
        if (axis.start() == null || axis.values() == null) {
            return DoubleUnaryOperator.identity();
        }
        return ArrayUnits.timeConversion(unit(axis.start()), axis.start().path(), unit(axis.values()),
                axis.values().path(), member);
    }

    /** True if {@code column} is stored as the MS-Numpress SLOF or PIC chunk transform. */
    private static boolean isSlofOrPic(Column column) {
        return column.format() == BufferFormat.CHUNK_TRANSFORM
                && (MSNumpress.ACC_NUMPRESS_SLOF.equals(column.transform())
                || MSNumpress.ACC_NUMPRESS_PIC.equals(column.transform()));
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
        if (chunks != null) {
            acceptChunk(group, idx, acc);
        } else {
            acceptPoint(group, idx, acc);
        }
    }

    private void acceptPoint(Group point, long idx, Map<Long, Accumulator> acc) {
        Double t = ParquetGroups.optDouble(point, timeField);
        Accumulator a = accumulator(acc, idx);
        Double in = pointIntensity(point, a);
        a.time.add(t  == null ? Double.NaN : t);
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
        for (Map.Entry<String, String> transform : undecodedAxisTransforms.entrySet()) {
            if (ChunkCodecs.carriesValue(chunk, transform.getValue())) {
                throw new MzPeakException(indexField + " " + idx + " stores its time axis with the chunk transform "
                        + transform.getKey() + " (column " + transform.getValue() + "), which is not decoded for"
                        + " chromatograms");
            }
        }
        ChunkRowDecoder.Row row = chunks.decodeSignal(chunk, idx);
        Accumulator a = accumulator(acc, idx);
        if (row.intensityField() != null) {
            a.supplied(intensityByField.get(row.intensityField()));
        }
        a.timeRows.add(row.axis());
        for (double in : row.intensity()) {
            a.intensity.add(in);
        }
        int n = row.axis().length;
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

    /**
     * The arrays of one chromatogram, with its extras that hold a number and its intensity unit; the null-marked times
     * of a chunked one filled per chunk row ({@code reconstructProfile}), else dropped.
     *
     * @throws MzPeakException when a null-marked time cannot be filled
     */
    private Decoded decoded(long idx, Accumulator a) {
        Map<String, double[]> arrays = new LinkedHashMap<>();
        for (int k = 0; k < extras.size(); k++) {
            if (!unreadable[k] && !a.dropped(k) && a.hasNumber[k]) {
                arrays.put(extras.get(k).key(), a.cells[k].toArray());
            }
        }
        double[] intensity = a.intensity.toArray();
        if (chunks == null) {
            return new Decoded(a.time.toArray(), intensity, intensityUnit(idx, a), arrays);
        }
        if (reconstructProfile) {
            double[] time = NullFill.fill(a.timeRows, true, null, member, SignalEntity.CHROMATOGRAM.ref(idx));
            return new Decoded(time, intensity, intensityUnit(idx, a), arrays);
        }
        SpectrumArrayStore.Arrays kept = NullFill.dropNulls(NullFill.concat(a.timeRows), intensity, arrays);
        return new Decoded(kept.mz(), kept.intensity(), intensityUnit(idx, a), kept.extras());
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
        /** Point layout: the times. */
        final DoubleArrayBuilder time = new DoubleArrayBuilder();
        /** Chunk layout: the times, one array per chunk row. */
        final List<double[]> timeRows = new ArrayList<>();
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
