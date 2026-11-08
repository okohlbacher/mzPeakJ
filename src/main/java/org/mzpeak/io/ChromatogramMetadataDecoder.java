package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decodes the chromatogram metadata member into one {@link Header} per chromatogram, in either layout, through
 * {@link MetadataTable}: the pin-era packed struct {@code chromatogram} or the split table keyed by {@code index}.
 * Columns are resolved by accession. Only the chromatogram table itself is read; its precursors and selected-ions
 * facets are not decoded.
 */
final class ChromatogramMetadataDecoder {

    private static final String F_ID = "id";
    /** The chromatogram type term marker; its pin-era packed name is a {@link ColumnTable} fallback. */
    private static final String CHROMATOGRAM_TYPE = "MS:1000626";
    /** Scan polarity; its pin-era packed name is a {@link ColumnTable} fallback. */
    private static final String POLARITY = "MS:1000465";
    /** Number of data points: the length of the data arrays, so not a Param. */
    private static final String N_POINTS = "MS:1003060";
    /** The data processing reference as the spec spells it. */
    private static final String F_DATA_PROCESSING_REF = "data_processing_ref";
    /** The data processing reference as the Rust writer spells it. */
    private static final String F_DATA_PROCESSING_ID = "data_processing_id";

    private ChromatogramMetadataDecoder() {
    }

    /**
     * The identity of one chromatogram.
     *
     * @param index             the chromatogram's {@code index}
     * @param id                its {@code id}, or {@code null}
     * @param typeCurie         the CURIE in its MS:1000626 (chromatogram type) term-marker cell, or {@code null}
     * @param polarity          its MS:1000465 (scan polarity) cell by {@link Polarity#fromCode}: {@code UNKNOWN} for
     *                          0, a null cell or no column
     * @param dataProcessingRef its {@code data_processing_ref} cell, else its {@code data_processing_id} cell, or
     *                          {@code null}
     * @param parameters        its Params, as {@link #decode} reads them
     */
    record Header(long index, String id, String typeCurie, Polarity polarity, String dataProcessingRef,
                  List<Param> parameters) {
    }

    /**
     * The headers of the member {@code file}, whose manifest entry is {@code entry}, in the order their indices
     * first occur. A repeated index keeps its first position and takes the values of its last row. Packed
     * placeholder rows (a null struct or index) are skipped.
     *
     * <p>A row's Params are its {@code parameters} list, then, in the split layout, its leftovers
     * ({@link ColumnResolver#leftovers}): the scalar columns that neither a header field nor the point count is read
     * from. Both spellings of the data processing reference are structural, never Params. A leftover is kept only
     * when its accession is new to the row's Params, so a column that repeats a list entry adds nothing; an unmapped
     * column has no accession and is kept. The packed layout has no leftovers.
     *
     * @throws MzPeakException if the member has rows but neither metadata shape, or a split row's index is null, or
     *                         an index has the high bit set
     */
    static List<Header> decode(InputFile file, Entry entry) {
        Map<Long, Header> byIndex = new LinkedHashMap<>();
        MetadataTable.forEachRow(file, entry, Facet.CHROMATOGRAM, row -> {
            ColumnResolver columns = row.columns();
            Group g = row.group();
            long index = row.key();
            Set<String> consumed = new HashSet<>();
            String id = columns.stringValue(g, consume(consumed, F_ID));
            String type = columns.stringValue(g, consume(consumed, columns.resolve(CHROMATOGRAM_TYPE)));
            Polarity polarity = Polarity.fromCode(columns.intValue(g, consume(consumed, columns.resolve(POLARITY))));
            consume(consumed, columns.resolve(N_POINTS));
            String dataProcessingRef = columns.stringValue(g, columns.resolveName(F_DATA_PROCESSING_REF));
            if (dataProcessingRef == null) {
                dataProcessingRef = columns.stringValue(g, columns.resolveName(F_DATA_PROCESSING_ID));
            }
            byIndex.put(index, new Header(index, id, type, polarity, dataProcessingRef,
                    parameters(columns.params(g), columns.leftovers(g, consumed))));
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
