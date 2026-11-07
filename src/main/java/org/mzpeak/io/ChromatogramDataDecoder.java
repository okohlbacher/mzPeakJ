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
 *       values as deltas, whatever the chunk encoding; a null delta adds 0. The intensities are the first non-null
 *       value-bearing intensity list, else the MS-Numpress SLOF bytes, else none; a null intensity reads as 0,
 *       missing ones are padded with 0 and surplus ones dropped. A chunk without a start is skipped.
 * </ul>
 * Rows without the prefix struct or an index are skipped; the chunks and points of one index are appended in row
 * order. These are Q-U5's semantics; sharing the spectrum chunk decoder would change them (deferred).
 *
 * <p><b>Extra arrays</b> (AP-9) are the plan's {@link SignalPlan#extras() extras}, under the plan's keys, parallel
 * to the time axis: one cell per point row, and per chunk row a list that aligns 1:1 with the time values the chunk
 * decodes to. A null cell, and every cell of a null list, reads as NaN. A key whose cells in a chromatogram are all
 * NaN gets no entry for that chromatogram. Extras never throw; with a WARNING,
 * <ul>
 *   <li>an extra stored in a chunk transform column is dropped from the member (transforms are not decoded for
 *       chromatograms);
 *   <li>an extra whose cells are not numbers is dropped from the member;
 *   <li>a chunk list of another length than its chunk's time values drops that key for that chromatogram.
 * </ul>
 *
 * <p><b>Intensity unit</b> (AP-9): the unit of the plan entry that supplied a chromatogram's values, the intensity
 * column or SLOF column chosen above. It is null when no entry supplied a value, when the entries that did differ in
 * unit (with a WARNING), and for an {@link ArrayIndex.Source#INFERRED INFERRED} plan, whose entries carry no unit.
 */
final class ChromatogramDataDecoder {

    private static final System.Logger LOG = System.getLogger(ChromatogramDataDecoder.class.getName());

    /**
     * The decoded arrays of one chromatogram.
     *
     * @param intensityUnit the unit of the entry that supplied the intensities, or null
     * @param arrays        the extra arrays by key, in plan order, each as long as {@code time}
     */
    record Decoded(double[] time, double[] intensity, String intensityUnit, Map<String, double[]> arrays) {
    }

    private final String prefix;
    private final String indexField;
    private final boolean chunked;
    private final String timeField;
    private final String startField;
    private final List<Column> intensityColumns;
    private final Column slofColumn;
    private final boolean unitsDeclared;
    /** The decodable extras, in plan order. */
    private final List<Extra> extras;
    /** Per extra: its cells did not read as numbers, so it is dropped from the member. */
    private final boolean[] unreadable;

    private ChromatogramDataDecoder(SignalPlan plan) {
        this.prefix = plan.prefix();
        this.indexField = plan.indexField();
        this.chunked = plan.layout() == SignalPlan.Layout.CHUNK;
        this.timeField = field(plan.axis().values());
        this.startField = field(plan.axis().start());
        this.intensityColumns = plan.intensities().stream()
                .filter(c -> c.format().valueBearing())
                .toList();
        this.slofColumn = plan.intensities().stream()
                .filter(c -> c.format() == BufferFormat.CHUNK_TRANSFORM
                        && MSNumpress.ACC_NUMPRESS_SLOF.equals(c.transform()))
                .findFirst().orElse(null);
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
    }

    /**
     * Groups the rows of {@code dataFile} by chromatogram index, in the order the indices first occur.
     *
     * @throws MzPeakException when SLOF bytes do not decode; the message names the chromatogram index
     */
    static Map<Long, Decoded> decode(InputFile dataFile, SignalPlan plan) {
        ChromatogramDataDecoder decoder = new ChromatogramDataDecoder(plan);
        Map<Long, Accumulator> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> decoder.accept(row, acc));

        Map<Long, Decoded> out = new LinkedHashMap<>();
        acc.forEach((idx, a) -> out.put(idx, decoder.decoded(idx, a)));
        return out;
    }

    private static String field(Column column) {
        return column == null ? null : column.field();
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
        Long idx = ParquetGroups.optLong(group, indexField);
        if (idx == null) {
            return;
        }
        if (chunked) {
            acceptChunk(group, idx, acc);
        } else {
            acceptPoint(group, idx, acc);
        }
    }

    private void acceptPoint(Group point, long idx, Map<Long, Accumulator> acc) {
        Double t = timeField == null ? null : ParquetGroups.optDouble(point, timeField);
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
            } catch (IllegalStateException | ClassCastException e) {
                markUnreadable(k, e);
                continue;
            }
            a.add(k, cell == null ? Double.NaN : cell);
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
        // Time axis: chunk start + cumulative sum of the chunk values
        Double start = startField == null ? null : ParquetGroups.optDouble(chunk, startField);
        if (start == null) {
            return;
        }
        Accumulator a = accumulator(acc, idx);
        double[] deltas    = timeField == null ? new double[0] : ParquetGroups.doubleListNullable(chunk, timeField);
        double[] intensity = chunkIntensity(chunk, idx, a);

        // Reconstruct time axis: time[0] = start; time[i+1] = time[i] + deltas[i]
        // intensity.length == deltas.length + 1 (intensity[0] is at the chunk start)
        int n = deltas.length + 1;
        double[] time = new double[n];
        time[0] = start;
        for (int i = 0; i < deltas.length; i++) {
            double d = deltas[i];
            time[i + 1] = time[i] + (Double.isNaN(d) ? 0.0 : d);
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
        } catch (IllegalStateException | ClassCastException e) {
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
                a.add(k, cell);
            }
        }
    }

    /**
     * Intensity values of a chunk row: the first non-null value-bearing intensity list of the plan, otherwise the
     * plan's MS-Numpress SLOF bytes; the column used is noted on {@code a}. Returns an empty array when neither is
     * present.
     */
    private double[] chunkIntensity(Group chunk, long chromatogramIndex, Accumulator a) {
        for (Column column : intensityColumns) {
            if (ParquetGroups.has(chunk, column.field())) {
                a.supplied(column);
                return ParquetGroups.doubleListNullable(chunk, column.field());
            }
        }
        if (slofColumn != null && ParquetGroups.has(chunk, slofColumn.field())) {
            a.supplied(slofColumn);
            byte[] bytes = ParquetGroups.byteList(chunk, slofColumn.field());
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
        // A chunk with only PIC bytes is not decoded yet and reads as zero intensities
        // (deferred: PIC chromatograms, via MSNumpress.decodePic, since decode() rejects PIC payloads < 8 bytes).
        return new double[0];
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
