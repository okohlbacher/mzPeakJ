package org.mzpeak.model;

import java.util.List;

/**
 * A product ion selection of a spectrum or chromatogram, as in selected or multiple reaction monitoring, where each
 * product ion is isolated with its own window (the Q3 transmission window of a triple quadrupole). Read from the
 * {@code products} facet; mzPeakJ does not write it.
 *
 * @param precursorIndex  the {@code precursor_index} of the precursor this product belongs to (the format's foreign
 *                        key), or {@code null} when the row gives none; read as stored
 * @param productIndex    the product's {@code product_index}, which identifies it across all rows of the facet
 * @param isolationWindow the product's isolation window; may be {@code null}
 * @param parameters      the product's other CV and user params; never {@code null}, may be empty. Unmodifiable, a
 *                        copy of the given list
 */
public record Product(Long precursorIndex, long productIndex, IsolationWindow isolationWindow,
                      List<Param> parameters) {

    public Product {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
