package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.Entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the wavelength-spectrum metadata member into one {@link Header} per wavelength spectrum, in either layout,
 * through {@link MetadataTable}: the pin-era packed struct {@code spectrum} or the split table keyed by
 * {@code index}. Columns are resolved by accession. Only the wavelength-spectrum table itself is read; its scans
 * facet (the packed {@code scan} struct or the split scans member) and the Params of either layout are not decoded.
 */
final class WavelengthMetadataDecoder {

    private static final String F_ID = "id";
    private static final String F_TIME = "time";
    /** Lambda max; its pin-era packed name is a {@link ColumnTable} fallback. */
    private static final String LAMBDA_MAX = "MS:1003812";

    private WavelengthMetadataDecoder() {
    }

    /**
     * The identity of one wavelength spectrum.
     *
     * @param index     the wavelength spectrum's {@code index}
     * @param id        its {@code id}, or {@code null}
     * @param time      its {@code time}, or {@code NaN} when the cell is null
     * @param lambdaMax its MS:1003812 (lambda max) cell, in nm, or {@code null}
     */
    record Header(long index, String id, double time, Double lambdaMax) {
    }

    /**
     * The headers of the member {@code file}, whose manifest entry is {@code entry}, in the order their indices
     * first occur. A repeated index keeps its first position and takes the values of its last row. Packed
     * placeholder rows (a null struct or index) are skipped.
     *
     * @throws MzPeakException if the member has rows but neither metadata shape, or a split row's index is null, or
     *                         an index has the high bit set
     */
    static List<Header> decode(InputFile file, Entry entry) {
        Map<Long, Header> byIndex = new LinkedHashMap<>();
        MetadataTable.forEachRow(file, entry, Facet.WAVELENGTH_SPECTRUM, row -> {
            ColumnResolver columns = row.columns();
            Group g = row.group();
            long index = row.key();
            Double time = columns.doubleValue(g, F_TIME);
            byIndex.put(index, new Header(index, columns.stringValue(g, F_ID), time == null ? Double.NaN : time,
                    columns.doubleValue(g, columns.resolve(LAMBDA_MAX))));
        });
        return List.copyOf(byIndex.values());
    }
}
