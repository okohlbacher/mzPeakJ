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
 * Decodes the spectrum metadata member into {@link SpectrumDescription}s. The member's own schema selects the
 * layout ({@link MetadataShape}): the packed layout is read by {@link PackedMetadataReader}; the split layout is
 * not supported yet.
 */
final class SpectrumMetadataDecoder {

    /** The spectrum key column. */
    static final String F_INDEX = "index";

    /** Names a member given directly as an {@link InputFile} in error messages, by its manifest role. */
    private static final String MEMBER = "spectrum metadata member";

    private SpectrumMetadataDecoder() {
    }

    /**
     * Decodes the member that {@code manifest} lists under the role (spectrum, metadata); error messages name it by
     * its file name.
     *
     * @throws MzPeakException when the manifest lists no such member
     */
    static Decoded decode(MzPeakSource source, MzPeakManifest manifest) {
        MzPeakManifest.Entry entry = manifest.find(EntityType.SPECTRUM, DataKind.METADATA)
                .orElseThrow(() -> new MzPeakException("Manifest has no spectrum metadata file"));
        return decode(source.inputFile(entry.name()), entry.name());
    }

    /** Decodes a spectrum metadata member given directly; error messages name it by its role. */
    static Decoded decode(InputFile metadataFile) {
        return decode(metadataFile, MEMBER);
    }

    private static Decoded decode(InputFile metadataFile, String member) {
        ParquetFooter footer = ParquetFooter.read(metadataFile);
        MetadataShape shape = MetadataShape.detect(footer, "spectrum", F_INDEX, member);
        if (shape == MetadataShape.SPLIT) {
            throw new MzPeakException(member + ": split metadata layout not supported yet");
        }
        if (shape == MetadataShape.EMPTY) {
            return new Decoded(new ArrayList<>(), new HashMap<>());
        }
        return PackedMetadataReader.read(metadataFile, footer.schema());
    }

    /** Decoded spectrum metadata plus the per-spectrum {@code mz_delta_model} polynomial coefficients. */
    record Decoded(List<SpectrumDescription> descriptions, Map<Long, double[]> deltaModels) {
    }
}
