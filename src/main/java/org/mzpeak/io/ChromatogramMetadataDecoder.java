package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.mzpeak.io.MzPeakManifest.Entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the chromatogram metadata member into one {@link Header} per chromatogram, in either layout, through
 * {@link MetadataTable}: the pin-era packed struct {@code chromatogram} or the split table keyed by {@code index}.
 * Columns are resolved by accession. Only the chromatogram table itself is read; its precursors and selected-ions
 * facets and the Params of either layout are not decoded.
 */
final class ChromatogramMetadataDecoder {

    private static final String F_ID = "id";
    /** The chromatogram type term marker; its pin-era packed name is a {@link ColumnTable} fallback. */
    private static final String CHROMATOGRAM_TYPE = "MS:1000626";

    private ChromatogramMetadataDecoder() {
    }

    /**
     * The identity of one chromatogram.
     *
     * @param index     the chromatogram's {@code index}
     * @param id        its {@code id}, or {@code null}
     * @param typeCurie the CURIE in its MS:1000626 (chromatogram type) term-marker cell, or {@code null}
     */
    record Header(long index, String id, String typeCurie) {
    }

    /**
     * The headers of the member {@code entry} names, in the order their indices first occur. A repeated index keeps
     * its first position and takes the values of its last row. Packed placeholder rows (a null struct or index) are
     * skipped.
     *
     * @throws MzPeakException if the member has rows but neither metadata shape, or a split row's index is null, or
     *                         an index has the high bit set
     */
    static List<Header> decode(MzPeakSource source, Entry entry) {
        Map<Long, Header> byIndex = new LinkedHashMap<>();
        MetadataTable.forEachRow(source, entry, Facet.CHROMATOGRAM, row -> {
            ColumnResolver columns = row.columns();
            Group g = row.group();
            long index = row.key();
            byIndex.put(index, new Header(index, columns.stringValue(g, F_ID),
                    columns.stringValue(g, columns.resolve(CHROMATOGRAM_TYPE))));
        });
        return List.copyOf(byIndex.values());
    }
}
