package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.SignalPlan.Column;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes one chunk row of a spectrum signal member into paired m/z and intensity values, reading the columns the
 * member's {@link SignalPlan} names (design §8.2). Per row:
 * <ul>
 *   <li>A value in a Numpress column (the axis's MS:1002312 transform, or a SLOF or PIC intensity transform) selects
 *       the Numpress decoder: column presence wins over a known chunk_encoding. The intensity is SLOF, then PIC, then
 *       the plain list, chosen per row. Numpress intensity that decodes to values without Numpress m/z throws; an
 *       empty payload is skipped.
 *   <li>Otherwise the axis values are decoded from the chunk start with a null-aware delta (chunk_encoding null or
 *       MS:1003089) or kept absolute (MS:1000576, MS:1003088), and paired with the plain intensity list.
 *   <li>A grid chunk (MS:1003826) and any other chunk_encoding throw, also on a Numpress row, as does a value in a
 *       {@code *numpress*} column the plan does not decode.
 * </ul>
 */
final class ChunkRowDecoder {

    /** The values of one chunk row in stored order; both arrays have the same length. */
    record Row(double[] axis, double[] intensity) {
    }

    private static final Row EMPTY = new Row(new double[0], new double[0]);

    private final String start;
    private final String values;
    private final String encoding;
    private final String linear;
    private final String grid;
    private final String intensity;
    private final String slof;
    private final String pic;
    /** The Numpress columns decoded here (linear m/z, SLOF, PIC); any other {@code *numpress*} column is not. */
    private final List<String> numpressColumns;

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
        intensity = field(plan.plainIntensity().orElse(null));
        slof = intensityTransform(plan, MSNumpress.ACC_NUMPRESS_SLOF);
        pic = intensityTransform(plan, MSNumpress.ACC_NUMPRESS_PIC);
        List<String> numpress = new ArrayList<>();
        for (String column : new String[] {linear, slof, pic}) {
            if (column != null && !numpress.contains(column)) {
                numpress.add(column);
            }
        }
        numpressColumns = List.copyOf(numpress);
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
        ChunkCodecs.rejectUnknownNumpressColumns(chunk, numpressColumns, si);
        String chunkEncoding = encoding == null ? null : ParquetGroups.optString(chunk, encoding);
        if (chunkEncoding != null && chunkEncoding.contains("1003826")) {
            throw ChunkCodecs.unsupportedGrid(chunk, grid, si);
        }
        // Checked for every row: Numpress column presence picks the decoder, but never admits an unknown CURIE.
        boolean absolute = ChunkCodecs.isAbsolute(chunkEncoding, si);
        if (has(chunk, linear) || has(chunk, slof) || has(chunk, pic)) {
            return decodeNumpress(chunk, chunkEncoding, si);
        }
        Double storedStart = start == null ? null : ParquetGroups.optDouble(chunk, start);
        double axisStart = storedStart == null ? 0.0 : storedStart;
        double[] rawAxis = doubles(chunk, values);
        double[] chunkIntensity = doubles(chunk, intensity);
        if (has(chunk, grid) && rawAxis.length == 0 && chunkIntensity.length > 0) {
            // A grid chunk without the grid chunk_encoding: the intensities pair with grid indices, not values.
            throw ChunkCodecs.unsupportedGrid(chunk, grid, si);
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
        for (int i = 0; i < chunkIntensity.length; i++) {
            if (Double.isNaN(chunkIntensity[i])) {
                chunkIntensity[i] = 0.0; // a null intensity element reads as NaN; the pair keeps intensity 0
            }
        }
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
     * <p>Numpress intensity without Numpress m/z has no m/z to pair with: an empty payload is skipped, and
     * one that decodes to values throws rather than dropping them.
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
        if (has(chunk, slof)) {
            chunkIntensity = ChunkCodecs.decodeNumpress(chunk, slof, MSNumpress.ACC_NUMPRESS_SLOF, si, "intensity");
        } else if (has(chunk, pic)) {
            chunkIntensity = ChunkCodecs.decodeNumpress(chunk, pic, MSNumpress.ACC_NUMPRESS_PIC, si, "intensity");
        } else if (has(chunk, intensity)) {
            // Mixed format: m/z is Numpress-compressed, intensity is a plain double list.
            chunkIntensity = ParquetGroups.doubleListNullable(chunk, intensity);
        } else {
            // No intensity data at all — skip this chunk; the spectrum will materialise as empty.
            return EMPTY;
        }
        if (axis.length != chunkIntensity.length) {
            throw new MzPeakException("Numpress chunk m/z (" + axis.length + ") and intensity ("
                    + chunkIntensity.length + ") lengths differ for spectrum_index " + si);
        }
        return new Row(axis, chunkIntensity);
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
