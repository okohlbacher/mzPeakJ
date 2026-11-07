package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.Entry;

import java.util.List;
import java.util.Optional;

/**
 * Reads spectrum metadata in the split layout (HUPO-PSI/mzPeak 0a28759 and later): one flat table per facet, each
 * its own member found by manifest role. The spectrum table ({@code index}) and its scans, precursors and selected
 * ions ({@code source_index}) feed a {@link SpectrumFacetAssembler}, which joins them strictly by key. A facet
 * member the manifest does not list is legal and contributes no rows; members of other roles (products, provenance,
 * signature, other) are not read.
 */
final class SplitMetadataReader {

    /** The spectrum facets read, the entity's own table first. */
    private static final List<Facet> FACETS = List.of(
            Facet.SPECTRUM, Facet.SPECTRUM_SCAN, Facet.SPECTRUM_PRECURSOR, Facet.SPECTRUM_SELECTED_ION);

    private SplitMetadataReader() {
    }

    /**
     * Passes every row of the spectrum facet members that {@code manifest} lists to {@code assembler}, facet by
     * facet in file order, keyed by the row's {@link FacetRow#key()}.
     *
     * @throws MzPeakException if a member has neither metadata shape, or a row key is null or out of range
     */
    static void read(MzPeakSource source, MzPeakManifest manifest, SpectrumFacetAssembler assembler) {
        for (Facet facet : FACETS) {
            Optional<Entry> entry = facet.entry(manifest);
            if (entry.isEmpty()) {
                continue;
            }
            MetadataTable.forEachRow(source, entry.get(), facet, row -> assembler.accept(row.key(), row));
        }
    }
}
