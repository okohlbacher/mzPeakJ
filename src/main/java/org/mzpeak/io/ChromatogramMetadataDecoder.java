package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.Precursor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Decodes the chromatogram metadata into one {@link Header} per chromatogram, in either layout, through
 * {@link MetadataTable}: the pin-era packed struct {@code chromatogram} or the split table keyed by {@code index}.
 * Columns are resolved by accession. A chromatogram's precursors come from the precursor and selected-ion facets: in
 * the packed layout the {@code precursor} and {@code selected_ion} structs of the same member, in the split layout
 * the members the manifest lists for them. Their rows are decoded as those of spectra, by
 * {@link PrecursorRowDecoder} and {@link SelectedIonRowDecoder}, and joined by {@link PrecursorJoin} on the key
 * {@code (source_index, precursor_index)}. A chromatogram's {@code precursor_index} names a chromatogram (spec HEAD
 * chromatograms.md:75-80); it is read as stored.
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
     * @param precursors        its precursors in file order, as {@link PrecursorJoin} joins the precursor and
     *                          selected-ion rows whose {@code source_index} is its index; empty when there are none
     */
    record Header(long index, String id, String typeCurie, Polarity polarity, String dataProcessingRef,
                  List<Param> parameters, List<Precursor> precursors) {
    }

    /**
     * The headers of the member that {@code manifest} lists as {@code entry} in {@code source}, as
     * {@link #decode(InputFile, Entry)} reads them, with, in the split layout, the precursors of the precursor and
     * selected-ion members the manifest lists, read from {@code source}. A facet member the manifest does not list
     * contributes no rows.
     *
     * @throws MzPeakException as {@link #decode(InputFile, Entry)}, and if a facet member has rows but neither
     *                         metadata shape, or a split row's {@code source_index} is null, or a key has the high
     *                         bit set
     */
    static List<Header> decode(MzPeakSource source, MzPeakManifest manifest, Entry entry) {
        return decode(source.inputFile(entry.name()), entry, source, manifest);
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
     * <p>The precursors are those of the member's own {@code precursor} and {@code selected_ion} structs in the packed
     * layout, each struct read when the schema has it. A split member keeps its facets in members of their own,
     * which only a manifest names ({@link #decode(MzPeakSource, MzPeakManifest, Entry)}), so here its chromatograms
     * have no precursors.
     *
     * @throws MzPeakException if the member has rows but neither metadata shape, or a split row's index is null, or
     *                         an index or {@code source_index} has the high bit set
     */
    static List<Header> decode(InputFile file, Entry entry) {
        return decode(file, entry, null, null);
    }

    /**
     * The headers of {@code file}, with the precursors of its own structs when it is packed, else of the facet
     * members {@code manifest} lists in {@code source}, unless {@code manifest} is {@code null}.
     */
    private static List<Header> decode(InputFile file, Entry entry, MzPeakSource source, MzPeakManifest manifest) {
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
                    parameters(columns.params(g), columns.leftovers(g, consumed)), List.of()));
        });
        // The member was read, so it has a shape: packed, split, or empty without rows.
        ParquetFooter footer = ParquetFooter.read(file);
        boolean packed = MetadataShape.detect(footer, Facet.CHROMATOGRAM.packedStruct(),
                FacetRow.keyColumn(footer.schema(), Facet.CHROMATOGRAM), entry.name()) == MetadataShape.PACKED;
        // facet -> source_index -> the facet's rows of that chromatogram, in file order
        Map<Facet, Map<Long, List<FacetRow>>> facetRows = new EnumMap<>(Facet.class);
        for (Facet facet : List.of(Facet.CHROMATOGRAM_PRECURSOR, Facet.CHROMATOGRAM_SELECTED_ION)) {
            Map<Long, List<FacetRow>> rows = new HashMap<>();
            facetRows.put(facet, rows);
            Consumer<FacetRow> bucket = row -> rows.computeIfAbsent(row.key(), k -> new ArrayList<>()).add(row);
            if (packed) {
                if (footer.schema().containsField(facet.packedStruct())) {
                    MetadataTable.forEachRow(file, entry, facet, bucket);
                }
            } else {
                Optional<Entry> member = manifest == null ? Optional.empty() : facet.entry(manifest);
                if (member.isPresent()) {
                    MetadataTable.forEachRow(source.inputFile(member.get().name()), member.get(), facet, bucket);
                }
            }
        }
        List<Header> out = new ArrayList<>(byIndex.size());
        for (Header h : byIndex.values()) {
            out.add(new Header(h.index(), h.id(), h.typeCurie(), h.polarity(), h.dataProcessingRef(), h.parameters(),
                    PrecursorJoin.join(facetRows.get(Facet.CHROMATOGRAM_PRECURSOR).get(h.index()),
                            facetRows.get(Facet.CHROMATOGRAM_SELECTED_ION).get(h.index()))));
        }
        return List.copyOf(out);
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
