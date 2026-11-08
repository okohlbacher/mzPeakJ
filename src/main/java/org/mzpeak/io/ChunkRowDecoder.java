package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes one chunk row of a spectrum signal member into paired m/z and intensity values, and the plan's extra arrays,
 * reading the columns the member's {@link SignalPlan} names (design §8.2, §8.5). Per row:
 * <ul>
 *   <li>A value in a Numpress column (the axis's MS:1002312 transform, or a SLOF or PIC intensity transform) selects
 *       the Numpress decoder: column presence wins over a known chunk_encoding. The intensity is SLOF, then PIC, then
 *       the plain list, chosen per row. Numpress intensity that decodes to values without Numpress m/z throws, as
 *       does Numpress m/z that decodes to values without any intensity column; an empty payload is skipped.
 *   <li>Otherwise the axis values are decoded from the chunk start with a null-aware delta (chunk_encoding null or
 *       MS:1003089) or kept absolute (MS:1000576, MS:1003088), and paired with the plain intensity list. A row with
 *       intensities throws when the plan names neither an axis values nor a chunk start column.
 *   <li>The plain intensity list of a row is the first of the plan's value-bearing intensity columns, in index
 *       order, that is non-null in the row.
 *   <li>A grid chunk (chunk_encoding MS:1003826, also on a Numpress row) takes the m/z from the axis's grid
 *       ({@link GridChunk}): its indices are delta-encoded from an absolute first one, each maps to one m/z through the
 *       {@link GridModels} model of its grid_type, and the m/z pair one-to-one with the plain intensity list; there is
 *       no chunk start point. A grid row without a grid, or with a grid_type that is not in {@link GridModels#KINDS},
 *       throws, as does one whose grid cannot be read (the error names the grid column), and a row with a grid,
 *       intensities and no axis values whose chunk_encoding is not the grid encoding.
 *   <li>Any other chunk_encoding throws, also on a Numpress row, as does a value in a {@code *numpress*} column the
 *       plan does not decode (an extra array's values or transform column excepted).
 *   <li>Each extra array pairs one-to-one with the decoded m/z of the row, chunk start point included; a null list
 *       reads as NaN, as does a null element. An extra stored as a grid (its MS:1003826 transform column carries a
 *       value) is decoded from its indices as they are, which are absolute. An extra whose values or grid have another
 *       length, whose grid_type is unknown or whose grid cannot be read, whose other transform column (a Numpress
 *       transform, which is not decoded for extras) carries a value, or whose list cannot be read as numbers is not
 *       decoded: the row reports it as dropped and the m/z and intensity are unaffected.
 * </ul>
 */
final class ChunkRowDecoder {

    /**
     * The values of one chunk row in stored order; {@code axis}, {@code intensity} and each array of {@code extras}
     * have the same length.
     *
     * @param extras  the plan's extra arrays decoded from this row, keyed as in {@link SignalPlan#extras()}, in plan
     *                order
     * @param dropped the plan's extra arrays this row cannot be decoded for, with the reason; not in {@code extras}
     */
    record Row(double[] axis, double[] intensity, Map<String, double[]> extras, Map<String, String> dropped) {

        Row(double[] axis, double[] intensity) {
            this(axis, intensity, Map.of(), Map.of());
        }
    }

    /** The columns of one extra array: its values list (null when only a transform stores it) and transforms. */
    private record ExtraColumns(String key, String values, Map<String, String> transformFields) {
    }

    private static final Row EMPTY = new Row(new double[0], new double[0]);

    private final String start;
    private final String values;
    private final String encoding;
    private final String linear;
    private final String grid;
    /** The value-bearing intensity columns, in index order. */
    private final List<String> intensities;
    private final String slof;
    private final String pic;
    /** The Numpress columns decoded here (linear m/z, SLOF, PIC); any other {@code *numpress*} column is not. */
    private final List<String> numpressColumns;
    private final List<ExtraColumns> extras;
    /**
     * The value and transform columns of the extras: a value in a transform column drops the extra instead of failing
     * the row, and a value column is read whatever its name.
     */
    private final List<String> extraColumns;

    ChunkRowDecoder(SignalPlan plan) {
        if (plan.entity() != SignalEntity.SPECTRUM) {
            throw new IllegalArgumentException("chunk rows of " + plan.entity() + " are not decoded here");
        }
        SignalPlan.Axis axis = plan.axis();
        start = field(axis.start());
        values = field(axis.values());
        encoding = field(axis.encoding());
        linear = axis.transformFields().get(MSNumpress.ACC_NUMPRESS_LINEAR);
        grid = axis.transformFields().get(ArrayIndex.GRID_TRANSFORM);
        intensities = plan.intensities().stream()
                .filter(c -> c.format().valueBearing())
                .map(Column::field)
                .toList();
        slof = intensityTransform(plan, MSNumpress.ACC_NUMPRESS_SLOF);
        pic = intensityTransform(plan, MSNumpress.ACC_NUMPRESS_PIC);
        List<String> numpress = new ArrayList<>();
        for (String column : new String[] {linear, slof, pic}) {
            if (column != null && !numpress.contains(column)) {
                numpress.add(column);
            }
        }
        numpressColumns = List.copyOf(numpress);
        List<ExtraColumns> extraArrays = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        for (SignalPlan.Extra extra : plan.extras().values()) {
            extraArrays.add(new ExtraColumns(extra.key(), field(extra.values()), extra.transformFields()));
            if (extra.values() != null) {
                columns.add(extra.values().field());
            }
            columns.addAll(extra.transformFields().values());
        }
        extras = List.copyOf(extraArrays);
        extraColumns = List.copyOf(columns);
    }

    private static String field(Column column) {
        return column == null ? null : column.field();
    }

    /** The first intensity column stored as the chunk transform {@code transform}, or null. */
    private static String intensityTransform(SignalPlan plan, String transform) {
        return plan.intensities().stream()
                .filter(c -> c.format() == BufferFormat.CHUNK_TRANSFORM && transform.equals(c.transform()))
                .map(Column::field)
                .findFirst()
                .orElse(null);
    }

    /** Decodes {@code chunk}, a row of spectrum {@code si}. */
    Row decode(Group chunk, long si) {
        Row row = decodeSignal(chunk, si);
        return extras.isEmpty() ? row : withExtras(row, chunk, si);
    }

    /** The m/z and intensity of {@code chunk}. */
    private Row decodeSignal(Group chunk, long si) {
        ChunkCodecs.rejectUnknownNumpressColumns(chunk, numpressColumns, extraColumns, si);
        String chunkEncoding = encoding == null ? null : ParquetGroups.optString(chunk, encoding);
        if (ChunkCodecs.GRID_ENCODING.equals(chunkEncoding)) {
            return decodeGrid(chunk, si);
        }
        // Checked for every row: Numpress column presence picks the decoder, but never admits an unknown CURIE.
        boolean absolute = ChunkCodecs.isAbsolute(chunkEncoding, si);
        if (has(chunk, linear) || has(chunk, slof) || has(chunk, pic)) {
            return decodeNumpress(chunk, chunkEncoding, si);
        }
        Double storedStart = start == null ? null : ParquetGroups.optDouble(chunk, start);
        double axisStart = storedStart == null ? 0.0 : storedStart;
        double[] rawAxis = doubles(chunk, values);
        double[] chunkIntensity = doubles(chunk, plainIntensity(chunk));
        if (has(chunk, grid) && rawAxis.length == 0 && chunkIntensity.length > 0) {
            // A grid chunk without the grid chunk_encoding: the intensities pair with grid indices, not values.
            throw ChunkCodecs.gridWithoutGridEncoding(grid, chunkEncoding, si);
        }
        if (values == null && start == null && chunkIntensity.length > 0) {
            // no m/z to pair the intensities with: reading them at m/z 0 would invent a point
            throw new MzPeakException("chunk row of spectrum_index " + si + " carries " + chunkIntensity.length
                    + " intensities, but the signal plan names neither an m/z values nor an m/z chunk start column");
        }
        double[] decoded = ChunkCodecs.decodeAxisValues(rawAxis, axisStart, absolute);
        // A chunk whose intensity list is one longer than its m/z values emits chunk_start as a real point
        // (its intensity is intensity[0]); the delta-decoded values then pair with intensity[1..]. A chunk
        // with equal lengths pairs its values one-to-one with intensities and has no separate start point.
        int extra = chunkIntensity.length - decoded.length;
        double[] axis;
        if (extra == 1) {
            axis = new double[chunkIntensity.length];
            axis[0] = axisStart;
            System.arraycopy(decoded, 0, axis, 1, decoded.length);
        } else if (extra == 0) {
            axis = decoded;
        } else {
            throw new MzPeakException("chunk intensity length (" + chunkIntensity.length
                    + ") must equal m/z values (" + decoded.length + ") or values+1, for spectrum_index " + si);
        }
        zeroNulls(chunkIntensity);
        return new Row(axis, chunkIntensity);
    }

    /** A null intensity element reads as NaN; the pair keeps intensity 0. */
    private static void zeroNulls(double[] chunkIntensity) {
        for (int i = 0; i < chunkIntensity.length; i++) {
            if (Double.isNaN(chunkIntensity[i])) {
                chunkIntensity[i] = 0.0;
            }
        }
    }

    /**
     * Grid chunk: the m/z are the axis grid's model applied to its cumulative indices, paired one-to-one with the plain
     * intensity list; chunk values, if any, are not read.
     */
    private Row decodeGrid(Group chunk, long si) {
        GridChunk g = grid == null ? null : GridChunk.read(chunk, grid, si);
        GridModels.Model model = g == null ? null : g.model(si);
        if (model == null) {
            throw ChunkCodecs.undecodableGrid(grid, g != null, g == null ? null : g.gridType(), si);
        }
        double[] axis = GridModels.decode(model, g.cumulativeIndices(si));
        double[] chunkIntensity = doubles(chunk, plainIntensity(chunk));
        if (chunkIntensity.length != axis.length) {
            throw new MzPeakException("grid chunk intensity length (" + chunkIntensity.length + ") must equal its "
                    + grid + " indices (" + axis.length + ") for spectrum_index " + si);
        }
        zeroNulls(chunkIntensity);
        return new Row(axis, chunkIntensity);
    }

    /**
     * Numpress chunk: m/z is MS-Numpress linear, intensity is MS-Numpress SLOF, PIC, or (mixed format)
     * a regular double LIST. These encode the full absolute arrays directly — no chunk_start, no delta,
     * no null-marking — so they pair 1:1.
     *
     * <p>Some writers (e.g. Thermo Orbitrap profile exports via mzML2mzPeak) use mixed-format chunks
     * where m/z is Numpress-encoded but intensity is stored as a plain intensity LIST. We detect
     * this by falling back to the plain intensity column when no intensity Numpress bytes are present.
     *
     * <p>Numpress intensity without Numpress m/z has no m/z to pair with, and Numpress m/z without an intensity
     * column has no intensity: an empty payload is skipped, and one that decodes to values throws rather than
     * dropping them.
     */
    private Row decodeNumpress(Group chunk, String chunkEncoding, long si) {
        if (!has(chunk, linear)) {
            ChunkCodecs.rejectUnpairedNumpressIntensity(chunk, slof, MSNumpress.ACC_NUMPRESS_SLOF, "SLOF",
                    chunkEncoding, si);
            ChunkCodecs.rejectUnpairedNumpressIntensity(chunk, pic, MSNumpress.ACC_NUMPRESS_PIC, "PIC",
                    chunkEncoding, si);
            return EMPTY;
        }
        double[] axis = ChunkCodecs.decodeNumpress(chunk, linear, MSNumpress.ACC_NUMPRESS_LINEAR, si, "m/z");
        double[] chunkIntensity;
        String plain = plainIntensity(chunk);
        if (has(chunk, slof)) {
            chunkIntensity = ChunkCodecs.decodeNumpress(chunk, slof, MSNumpress.ACC_NUMPRESS_SLOF, si, "intensity");
        } else if (has(chunk, pic)) {
            chunkIntensity = ChunkCodecs.decodeNumpress(chunk, pic, MSNumpress.ACC_NUMPRESS_PIC, si, "intensity");
        } else if (plain != null) {
            // Mixed format: m/z is Numpress-compressed, intensity is a plain double list.
            chunkIntensity = ParquetGroups.doubleListNullable(chunk, plain);
        } else {
            // No intensity column: only an empty m/z payload is skipped; decoded m/z is never dropped.
            if (axis.length > 0) {
                throw new MzPeakException("unsupported chunk: Numpress linear m/z without an intensity column"
                        + " (SLOF, PIC or intensity) for spectrum_index " + si);
            }
            return EMPTY;
        }
        if (axis.length != chunkIntensity.length) {
            throw new MzPeakException("Numpress chunk m/z (" + axis.length + ") and intensity ("
                    + chunkIntensity.length + ") lengths differ for spectrum_index " + si);
        }
        return new Row(axis, chunkIntensity);
    }

    /** {@code row} with the extra arrays of {@code chunk}, each paired one-to-one with the row's m/z. */
    private Row withExtras(Row row, Group chunk, long si) {
        int n = row.axis().length;
        Map<String, double[]> values = new LinkedHashMap<>();
        Map<String, String> dropped = new LinkedHashMap<>();
        for (ExtraColumns extra : extras) {
            String transform = carriedTransform(chunk, extra);
            if (transform != null) {
                dropped.put(extra.key(), "is stored with the transform " + transform + ", which is not decoded");
                continue;
            }
            String gridField = extra.transformFields().get(ArrayIndex.GRID_TRANSFORM);
            double[] v;
            if (gridField != null && ChunkCodecs.carriesValue(chunk, gridField)) {
                GridChunk g;
                GridModels.Model model;
                try {
                    g = GridChunk.read(chunk, gridField, si);
                    model = g.model(si);
                } catch (RuntimeException e) {
                    dropped.put(extra.key(), "cannot be decoded from its grid (" + e.getMessage() + ")");
                    continue;
                }
                if (model == null) {
                    dropped.put(extra.key(), "is stored as a grid (column " + gridField + ") of the unknown grid_type "
                            + g.gridType());
                    continue;
                }
                v = GridModels.decode(model, g.indices()); // absolute indices
                if (v.length != n) {
                    dropped.put(extra.key(), "has " + v.length + " grid indices (column " + gridField
                            + ") in a chunk of " + n + " points");
                    continue;
                }
            } else if (has(chunk, extra.values())) {
                try {
                    v = ParquetGroups.doubleListNullable(chunk, extra.values());
                } catch (RuntimeException e) {
                    dropped.put(extra.key(), "cannot be read as a list of numbers (" + e.getMessage() + ")");
                    continue;
                }
                if (v.length != n) {
                    dropped.put(extra.key(), "has " + v.length + " values in a chunk of " + n + " points");
                    continue;
                }
            } else {
                v = new double[n];
                Arrays.fill(v, Double.NaN);
            }
            values.put(extra.key(), v);
        }
        return new Row(row.axis(), row.intensity(), values, dropped);
    }

    /**
     * The first transform of {@code extra} other than the grid whose column carries a value in this row, as text, or
     * null.
     */
    private static String carriedTransform(Group chunk, ExtraColumns extra) {
        for (Map.Entry<String, String> t : extra.transformFields().entrySet()) {
            if (!t.getKey().equals(ArrayIndex.GRID_TRANSFORM) && ChunkCodecs.carriesValue(chunk, t.getValue())) {
                return t.getKey() + " (column " + t.getValue() + ")";
            }
        }
        return null;
    }

    /** The first value-bearing intensity column of the plan that is non-null in this row, or null. */
    private String plainIntensity(Group chunk) {
        for (String field : intensities) {
            if (ParquetGroups.has(chunk, field)) {
                return field;
            }
        }
        return null;
    }

    /** True if the plan names {@code field} and it is non-null in this row. */
    private static boolean has(Group g, String field) {
        return field != null && ParquetGroups.has(g, field);
    }

    /** The list column {@code field} with nulls as NaN; empty when the plan names no such column. */
    private static double[] doubles(Group g, String field) {
        return field == null ? new double[0] : ParquetGroups.doubleListNullable(g, field);
    }
}
