package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.GroupType;
import org.mzpeak.io.SpectrumMetadataDecoder.Decoded;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.SpectrumDescription;

/**
 * Reads a spectrum metadata member in the "packed parallel tables" layout into {@link SpectrumDescription}s.
 *
 * <p>The {@code spectrum}, {@code scan}, {@code precursor} and {@code selected_ion} struct columns are
 * <em>not</em> row-aligned: each facet is densely packed and carries its own {@code source_index} pointing
 * back to the owning spectrum. Placeholder facet rows have a null {@code source_index} and are skipped.
 * Therefore we join strictly by {@code source_index}, never by row position. The columns of each struct are
 * resolved by accession through {@link ColumnResolver#packed}, so pin-era names come from {@link ColumnTable}.
 */
final class PackedMetadataReader {

    // facet keys
    private static final String F_SOURCE_INDEX = "source_index";

    private PackedMetadataReader() {
    }

    /**
     * Reads every row of {@code metadataFile}, whose {@code schema} has the packed shape, in one pass; column errors
     * name the member {@code member}.
     */
    static Decoded read(InputFile metadataFile, GroupType schema, String member) {
        ColumnResolver spectrumColumns = packed(schema, Facet.SPECTRUM, member);
        ColumnResolver scanColumns = packed(schema, Facet.SPECTRUM_SCAN, member);
        ColumnResolver precursorColumns = packed(schema, Facet.SPECTRUM_PRECURSOR, member);
        ColumnResolver selectedIonColumns = packed(schema, Facet.SPECTRUM_SELECTED_ION, member);
        SpectrumFacetAssembler assembler = new SpectrumFacetAssembler();

        ParquetGroups.forEach(metadataFile, row -> {
            Group spectrum = ParquetGroups.optGroup(row, Facet.SPECTRUM.packedStruct());
            if (spectrum != null) {
                Long idx = ParquetGroups.optLong(spectrum, SpectrumMetadataDecoder.F_INDEX);
                if (idx != null) {
                    if (idx < 0) {
                        throw new MzPeakException("spectrum.index exceeds supported range (uint64 high bit set): " + idx);
                    }
                    assembler.accept(idx, new FacetRow(Facet.SPECTRUM, spectrum, spectrumColumns));
                }
            }
            indexFacet(row, Facet.SPECTRUM_SCAN, scanColumns, assembler);
            indexFacet(row, Facet.SPECTRUM_PRECURSOR, precursorColumns, assembler);
            indexFacet(row, Facet.SPECTRUM_SELECTED_ION, selectedIonColumns, assembler);
        });
        return assembler.build();
    }

    /**
     * The resolver of {@code facet}'s packed struct, or {@code null} when the schema has no such struct (then no row
     * has one either).
     */
    private static ColumnResolver packed(GroupType schema, Facet facet, String member) {
        String struct = facet.packedStruct();
        if (!schema.containsField(struct) || schema.getType(struct).isPrimitive()) {
            return null;
        }
        return ColumnResolver.packed(schema.getType(struct).asGroupType(), facet, member);
    }

    private static void indexFacet(Group row, Facet facet, ColumnResolver columns, SpectrumFacetAssembler assembler) {
        String facetName = facet.packedStruct();
        Group struct = ParquetGroups.optGroup(row, facetName);
        if (struct != null) {
            Long src = ParquetGroups.optLong(struct, F_SOURCE_INDEX);
            if (src != null) {
                if (src < 0) {
                    // uint64 with the high bit set — out of the supported range; a negative key would be
                    // silently bucketed and never joined to a spectrum. Fail loudly, as for spectrum.index.
                    throw new MzPeakException(
                            facetName + ".source_index exceeds supported range (uint64 high bit set): " + src);
                }
                assembler.accept(src, new FacetRow(facet, struct, columns));
            }
        }
    }
}
