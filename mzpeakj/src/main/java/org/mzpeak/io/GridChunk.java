package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.parquet.ParquetGroups;

/**
 * One coordinate grid of a chunk row (MS:1003826, design §8.6): the struct
 * {@code {grid_type, parameters list<double>, indices list<uint32>}} in the column the array index lists as the
 * array's MS:1003826 chunk transform, such as {@code mz_grid}.
 *
 * @param field      the grid column, for messages
 * @param gridType   the model CURIE, or null when the cell has none
 * @param parameters the model parameters
 * @param indices    the stored indices, each in 0..2^32-1
 */
record GridChunk(String field, String gridType, double[] parameters, long[] indices) {

    /** The largest unsigned 32-bit index. */
    static final long MAX_INDEX = 0xFFFF_FFFFL;

    /**
     * The grid in column {@code field} of {@code chunk}, or null when its cell is null. The indices are unsigned 32-bit
     * integers: an INT32 column holds their bit patterns, so a negative INT32 is read as its unsigned value.
     *
     * @throws MzPeakException when the cell cannot be read as such a struct (a column that is not a struct, a
     *                         grid_type that is not text, a parameters or indices list that is not numeric), or when
     *                         an index is null, not an integer or outside 0..2^32-1
     */
    static GridChunk read(Group chunk, String field, long si) {
        Group grid;
        String gridType;
        double[] parameters;
        double[] stored;
        boolean int32;
        try {
            grid = ParquetGroups.optGroup(chunk, field);
            if (grid == null) {
                return null;
            }
            gridType = ParquetGroups.optString(grid, "grid_type");
            parameters = ParquetGroups.doubleListNullable(grid, "parameters");
            stored = ParquetGroups.doubleListNullable(grid, "indices");
            int32 = ParquetGroups.has(grid, "indices")
                    && leaf(grid.getType(), "indices") == PrimitiveTypeName.INT32;
        } catch (RuntimeException e) {
            throw new MzPeakException("grid " + field + " cannot be read as {grid_type, parameters, indices} ("
                    + e.getMessage() + ") for spectrum_index " + si, e);
        }
        long[] indices = new long[stored.length];
        for (int i = 0; i < stored.length; i++) {
            double v = stored[i];
            long index = (long) v;
            if (index != v) {
                throw new MzPeakException("grid " + field + " has the index " + v + " at position " + i
                        + ", not an unsigned 32-bit integer, for spectrum_index " + si);
            }
            if (int32 && index < 0) {
                index += 1L << 32;
            }
            if (index < 0 || index > MAX_INDEX) {
                throw new MzPeakException("grid " + field + " has the index " + index + " at position " + i
                        + ", outside 0.." + MAX_INDEX + ", for spectrum_index " + si);
            }
            indices[i] = index;
        }
        return new GridChunk(field, gridType, parameters, indices);
    }

    /** The primitive type of the elements of list column {@code field} (3-level or 2-level list). */
    private static PrimitiveTypeName leaf(GroupType parent, String field) {
        Type t = parent.getType(field);
        while (!t.isPrimitive()) {
            GroupType g = t.asGroupType();
            if (g.getFieldCount() == 0) {
                return null;
            }
            t = g.getType(0);
        }
        return t.asPrimitiveType().getPrimitiveTypeName();
    }

    /**
     * The indices of a main-axis grid, which are delta-encoded: a running sum whose first term is absolute.
     *
     * @throws MzPeakException when a sum leaves 0..2^32-1
     */
    long[] cumulativeIndices(long si) {
        long[] out = new long[indices.length];
        long sum = 0;
        for (int i = 0; i < indices.length; i++) {
            sum += indices[i];
            if (sum > MAX_INDEX) {
                throw new MzPeakException("grid " + field + " indices sum to " + sum + " at position " + i
                        + ", beyond the largest index " + MAX_INDEX + ", for spectrum_index " + si);
            }
            out[i] = sum;
        }
        return out;
    }

    /**
     * The model of this grid, or null when its grid_type is not in {@link GridModels#KINDS}.
     *
     * @throws MzPeakException when the number of parameters does not fit the model
     */
    GridModels.Model model(long si) {
        GridModels.Kind kind = GridModels.kind(gridType);
        if (kind == null) {
            return null;
        }
        try {
            return kind.model(parameters);
        } catch (IllegalArgumentException e) {
            throw new MzPeakException("grid " + field + ": " + e.getMessage() + ", for spectrum_index " + si);
        }
    }
}
