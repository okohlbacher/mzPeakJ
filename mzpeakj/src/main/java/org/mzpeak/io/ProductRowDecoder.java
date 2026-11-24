package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.model.Param;
import org.mzpeak.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Decodes the product rows of one spectrum or chromatogram (spectra.md §Spectrum product selection metadata,
 * chromatograms.md §products) into {@link Product}s. The isolation window is read as a precursor's
 * ({@link PrecursorRowDecoder#isolationWindow}); the row's Params are its {@code parameters} list followed by its
 * leftovers ({@link ColumnResolver#leftovers}). The {@code precursor_index} is read as stored.
 */
final class ProductRowDecoder {

    private static final String F_PRODUCT_INDEX = "product_index";
    private static final String F_PRECURSOR_INDEX = "precursor_index";
    private static final String F_ISOLATION = "isolation_window";

    private ProductRowDecoder() {
    }

    /**
     * The products of the product rows of one entity, ordered by {@code product_index} (rows with the same index keep
     * their file order); empty when {@code rows} is {@code null}.
     *
     * @throws MzPeakException if a row has no {@code product_index}, or one with the high bit set
     */
    static List<Product> decode(List<FacetRow> rows) {
        if (rows == null) {
            return List.of();
        }
        List<Product> out = new ArrayList<>(rows.size());
        for (FacetRow row : rows) {
            out.add(decode(row));
        }
        out.sort(Comparator.comparingLong(Product::productIndex)); // List.sort is stable
        return out;
    }

    private static Product decode(FacetRow row) {
        Long productIndex = row.optKey(F_PRODUCT_INDEX);
        if (productIndex == null) {
            throw new MzPeakException(row.columns().member() + ": the product row of source_index " + row.key()
                    + " has no product_index");
        }
        ColumnResolver columns = row.columns();
        Group g = row.group();
        FacetRow iso = row.child(F_ISOLATION);
        List<Param> params = new ArrayList<>(columns.params(g));
        params.addAll(columns.leftovers(g, Set.of()));
        return new Product(
                columns.longValue(g, F_PRECURSOR_INDEX),
                productIndex,
                iso == null ? null : PrecursorRowDecoder.isolationWindow(iso),
                params);
    }
}
