package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes the wavelength-spectrum metadata member into one {@link Header} per wavelength spectrum, in either layout,
 * through {@link MetadataTable}: the pin-era packed struct {@code spectrum} or the split table keyed by
 * {@code index}. Columns are resolved by accession. Only the wavelength-spectrum table itself is read; its scans
 * facet (the packed {@code scan} struct or the split scans member) is not decoded.
 */
final class WavelengthMetadataDecoder {

    private static final String F_ID = "id";
    private static final String F_TIME = "time";
    /** Lambda max; its pin-era packed name is a {@link ColumnTable} fallback. */
    private static final String LAMBDA_MAX = "MS:1003812";
    /** Number of data points: the length of the data arrays, so not a Param. */
    private static final String N_POINTS = "MS:1003060";

    private WavelengthMetadataDecoder() {
    }

    /**
     * The identity of one wavelength spectrum.
     *
     * @param index      the wavelength spectrum's {@code index}
     * @param id         its {@code id}, or {@code null}
     * @param time       its {@code time}, or {@code NaN} when the cell is null
     * @param lambdaMax  its MS:1003812 (lambda max) cell, in nm, or {@code null}
     * @param parameters its Params, as {@link #decode} reads them
     */
    record Header(long index, String id, double time, Double lambdaMax, List<Param> parameters) {
    }

    /**
     * The headers of the member {@code file}, whose manifest entry is {@code entry}, in the order their indices
     * first occur. A repeated index keeps its first position and takes the values of its last row. Packed
     * placeholder rows (a null struct or index) are skipped.
     *
     * <p>A row's Params are its {@code parameters} list, then, in both layouts, its leftovers
     * ({@link ColumnResolver#leftoversInBothLayouts}): the scalar columns that neither a header field nor the point
     * count is read from, such as the spectrum type and representation term markers and the summary columns. A
     * leftover is kept only when its accession is new to the row's Params, so a column that repeats a list entry adds
     * nothing; an unmapped column has no accession and is kept.
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
            Set<String> consumed = new HashSet<>();
            String id = columns.stringValue(g, consume(consumed, F_ID));
            Double time = columns.doubleValue(g, consume(consumed, F_TIME));
            Double lambdaMax = columns.doubleValue(g, consume(consumed, columns.resolve(LAMBDA_MAX)));
            consume(consumed, columns.resolve(N_POINTS));
            byIndex.put(index, new Header(index, id, time == null ? Double.NaN : time, lambdaMax,
                    parameters(columns.params(g), columns.leftoversInBothLayouts(g, consumed))));
        });
        return List.copyOf(byIndex.values());
    }

    /**
     * {@code listed}, then each of {@code leftovers} whose accession neither {@code listed} nor an earlier leftover
     * has; a leftover without accession is always kept.
     */
    private static List<Param> parameters(List<Param> listed, List<Param> leftovers) {
        Set<String> accessions = new HashSet<>();
        for (Param p : listed) {
            if (p.accession() != null) {
                accessions.add(p.accession());
            }
        }
        List<Param> out = new ArrayList<>(listed);
        for (Param p : leftovers) {
            if (p.accession() == null || accessions.add(p.accession())) {
                out.add(p);
            }
        }
        return out;
    }

    /** Adds the resolved {@code field}, unless {@code null}, to {@code consumed}, and returns it. */
    private static String consume(Set<String> consumed, String field) {
        if (field != null) {
            consumed.add(field);
        }
        return field;
    }
}
