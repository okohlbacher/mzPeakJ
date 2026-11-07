package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.model.SpectrumDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes the spectrum metadata into {@link SpectrumDescription}s. The spectrum metadata member's own schema selects
 * the layout ({@link MetadataShape}): the packed layout is read by {@link PackedMetadataReader}, the split layout,
 * whose facets are members of their own, by {@link SplitMetadataReader}.
 */
final class SpectrumMetadataDecoder {

    /** The spectrum key column. */
    static final String F_INDEX = "index";

    /** Names a member given directly as an {@link InputFile} in error messages, by its manifest role. */
    private static final String MEMBER = "spectrum metadata member";

    private SpectrumMetadataDecoder() {
    }

    /**
     * Decodes the member that {@code manifest} lists under the role (spectrum, metadata) and, in the split layout,
     * the scans, precursors and selected-ions members it lists; error messages name a member by its file name.
     *
     * @throws MzPeakException when the manifest lists no spectrum metadata member
     */
    static Decoded decode(MzPeakSource source, MzPeakManifest manifest) {
        MzPeakManifest.Entry entry = manifest.find(EntityType.SPECTRUM, DataKind.METADATA)
                .orElseThrow(() -> new MzPeakException("Manifest has no spectrum metadata file"));
        InputFile metadataFile = source.inputFile(entry.name());
        ParquetFooter footer = ParquetFooter.read(metadataFile);
        return switch (MetadataShape.detect(footer, "spectrum", F_INDEX, entry.name())) {
            case PACKED -> PackedMetadataReader.read(metadataFile, footer.schema(), entry.name());
            case SPLIT -> {
                SpectrumFacetAssembler assembler = new SpectrumFacetAssembler();
                SplitMetadataReader.read(source, manifest, assembler);
                yield assembler.build();
            }
            case EMPTY -> empty();
        };
    }

    /**
     * Decodes a packed spectrum metadata member given directly; error messages name it by its role.
     *
     * @throws MzPeakException when the member has the split layout, whose facet members only the manifest names
     */
    static Decoded decode(InputFile metadataFile) {
        ParquetFooter footer = ParquetFooter.read(metadataFile);
        return switch (MetadataShape.detect(footer, "spectrum", F_INDEX, MEMBER)) {
            case PACKED -> PackedMetadataReader.read(metadataFile, footer.schema(), MEMBER);
            case SPLIT -> throw new MzPeakException(MEMBER + ": the split metadata layout keeps its scans,"
                    + " precursors and selected ions in members of their own; decode it through the manifest");
            case EMPTY -> empty();
        };
    }

    private static Decoded empty() {
        return new Decoded(new ArrayList<>(), new HashMap<>());
    }

    /** Decoded spectrum metadata plus the per-spectrum m/z delta model polynomial coefficients. */
    record Decoded(List<SpectrumDescription> descriptions, Map<Long, double[]> deltaModels) {
    }
}
