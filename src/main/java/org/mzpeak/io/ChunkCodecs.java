package org.mzpeak.io;

import ms.numpress.MSNumpress;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.parquet.ParquetGroups;

import java.util.Set;

/**
 * Decodes the payloads of a spectrum signal chunk row (the main-axis values and MS-Numpress byte lists) and builds the
 * errors for chunks the reader cannot decode.
 */
final class ChunkCodecs {

    private static final String DELTA_ENCODING = "MS:1003089";
    private static final String NO_COMPRESSION = "MS:1000576";
    private static final String TRUNCATION_ZLIB = "MS:1003088";
    private static final String GRID_ENCODING = "MS:1003826";
    /** The Numpress columns decoded here; any other column whose name contains "numpress" is not understood. */
    private static final Set<String> NUMPRESS_COLUMNS = Set.of(
            "mz_numpress_linear_bytes", "intensity_numpress_slof_bytes", "intensity_numpress_pic_bytes");

    private ChunkCodecs() {
    }

    static double[] decodeNumpress(Group chunk, String field, String cvAccession, long si, String what) {
        byte[] bytes = ParquetGroups.byteList(chunk, field);
        if (bytes.length == 0) {
            return new double[0];
        }
        try {
            return MSNumpress.decode(cvAccession, bytes, bytes.length);
        } catch (RuntimeException e) {
            throw new MzPeakException("Failed to Numpress-decode " + what + " for spectrum_index " + si, e);
        }
    }

    /** Throws if Numpress intensity in {@code field} decodes to values that have no Numpress m/z to pair with. */
    static void rejectUnpairedNumpressIntensity(Group chunk, String field, String cvAccession,
                                                String kind, long si) {
        if (ParquetGroups.has(chunk, field)
                && decodeNumpress(chunk, field, cvAccession, si, "intensity").length > 0) {
            throw new MzPeakException("unsupported chunk: Numpress " + kind + " intensity (" + field
                    + ") without Numpress linear m/z (chunk_encoding "
                    + ParquetGroups.optString(chunk, "chunk_encoding") + ") for spectrum_index " + si);
        }
    }

    /**
     * Throws for a {@code *numpress*} column other than the three decoded ones when it carries a value in this
     * row: its data would otherwise be dropped (or, in place of the SLOF column, the whole chunk skipped).
     */
    static void rejectUnknownNumpressColumns(Group chunk, long si) {
        for (Type field : chunk.getType().getFields()) {
            String name = field.getName();
            if (name.contains("numpress") && !NUMPRESS_COLUMNS.contains(name) && carriesValue(chunk, name)) {
                throw new MzPeakException("unsupported Numpress column " + name + " for spectrum_index " + si
                        + "; only mz_numpress_linear_bytes, intensity_numpress_slof_bytes and"
                        + " intensity_numpress_pic_bytes are decoded");
            }
        }
    }

    /** True if {@code field} is non-null in this row and, if it is a group (such as a LIST), not empty. */
    private static boolean carriesValue(Group g, String field) {
        if (!ParquetGroups.has(g, field)) {
            return false;
        }
        if (g.getType().getType(field).isPrimitive()) {
            return true;
        }
        Group value = g.getGroup(field, 0);
        for (int i = 0; i < value.getType().getFieldCount(); i++) {
            if (value.getFieldRepetitionCount(i) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * The error for a coordinate-grid chunk ({@code MS:1003826}, a draft encoding not decoded yet): one whose
     * {@code chunk_encoding} names the grid, or one with an {@code mz_grid} struct and intensities but no chunk
     * values. Without this check the intensities fail the length check with a misleading message.
     */
    static MzPeakException unsupportedGrid(Group chunk, long si) {
        Group grid = ParquetGroups.optGroup(chunk, "mz_grid");
        String gridType = grid == null ? null : ParquetGroups.optString(grid, "grid_type");
        return new MzPeakException("unsupported chunk encoding " + GRID_ENCODING
                + " (coordinate grid, draft) with grid_type " + (gridType == null ? "(none)" : gridType)
                + " for spectrum_index " + si + "; grid decoding is not implemented");
    }

    /**
     * Whether a non-Numpress chunk with {@code encoding} stores absolute m/z ({@code MS:1000576},
     * {@code MS:1003088}) rather than deltas from {@code chunk_start} ({@code null}, {@code MS:1003089}).
     * Numpress ({@code MS:1002312}) is chosen by column presence; a row that names it without carrying a Numpress
     * column decodes as delta, as before. Any other encoding throws instead of being decoded as delta, also on a
     * row whose Numpress columns would select the Numpress decoder.
     */
    static boolean isAbsolute(String encoding, long si) {
        if (encoding == null || encoding.equals(DELTA_ENCODING)
                || encoding.equals(MSNumpress.ACC_NUMPRESS_LINEAR)) {
            return false;
        }
        if (encoding.equals(NO_COMPRESSION) || encoding.equals(TRUNCATION_ZLIB)) {
            return true;
        }
        throw new MzPeakException("unsupported chunk encoding " + encoding + " for spectrum_index " + si);
    }

    /**
     * Decode the chunk values of the main axis (m/z for spectra) parallel to {@code values} (NaN preserved as gaps).
     *
     * <p>"No compression" encodings ({@code MS:1000576}) and "truncation + zlib" ({@code MS:1003088})
     * store absolute coordinates — emitted as-is. Delta-prediction ({@code MS:1003089}) stores deltas
     * relative to a running predictor seeded at {@code chunk_start}, but a {@code null} (NaN) breaks the
     * prediction chain: the next non-null value is then <em>absolute</em>, not a delta. This mirrors the
     * spec's {@code null_delta_decode} reference algorithm (chunked-layout, "Delta encoding"). Treating a
     * post-null value as a delta (the previous behaviour) double-counted absolute re-anchors and produced
     * non-monotonic, out-of-range m/z.
     */
    static double[] decodeAxisValues(double[] values, double start, boolean noDelta) {
        double[] out = new double[values.length];
        if (noDelta) {
            System.arraycopy(values, 0, out, 0, values.length); // absolute; NaN stays NaN
            return out;
        }
        // Null-aware delta decode: `last` is the previous emitted m/z; `lastValid` is false right after a
        // null, which forces the next value to be read as an absolute re-anchor (predictor reset).
        double last = start;
        boolean lastValid = true;
        for (int i = 0; i < values.length; i++) {
            double v = values[i];
            if (Double.isNaN(v)) {
                out[i] = Double.NaN;   // gap; breaks the delta chain
                lastValid = false;
            } else if (lastValid) {
                last += v;             // delta from the running predictor
                out[i] = last;
            } else {
                last = v;              // absolute re-anchor after a null
                out[i] = last;
                lastValid = true;
            }
        }
        return out;
    }
}
