package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.mzpeak.io.SpectrumMetadataDecoder.Decoded;
import org.mzpeak.io.SpectrumRowDecoder.SpectrumHead;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SpectrumDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a spectrum metadata member in the "packed parallel tables" layout into {@link SpectrumDescription}s.
 *
 * <p>The {@code spectrum}, {@code scan}, {@code precursor} and {@code selected_ion} struct columns are
 * <em>not</em> row-aligned: each facet is densely packed and carries its own {@code source_index} pointing
 * back to the owning spectrum. Placeholder facet rows have a null {@code source_index} and are skipped.
 * Therefore we join strictly by {@code source_index}, never by row position.
 */
final class PackedMetadataReader {

    // facet keys
    private static final String F_SOURCE_INDEX = "source_index";

    private PackedMetadataReader() {
    }

    /** Reads every row of {@code metadataFile}, whose schema has the packed shape, in one pass. */
    static Decoded read(InputFile metadataFile) {
        Map<Long, SpectrumHead> builders = new LinkedHashMap<>();
        Map<Long, List<Group>> scansBySource = new HashMap<>();
        Map<Long, List<Group>> precursorsBySource = new HashMap<>();
        Map<Long, List<Group>> selectedIonsBySource = new HashMap<>();
        Map<Long, double[]> deltaModels = new HashMap<>();

        ParquetGroups.forEach(metadataFile, row -> {
            Group spectrum = ParquetGroups.optGroup(row, "spectrum");
            if (spectrum != null) {
                Long idx = ParquetGroups.optLong(spectrum, SpectrumMetadataDecoder.F_INDEX);
                if (idx != null) {
                    if (idx < 0) {
                        throw new MzPeakException("spectrum.index exceeds supported range (uint64 high bit set): " + idx);
                    }
                    SpectrumHead head = SpectrumRowDecoder.decode(spectrum, idx);
                    if (builders.putIfAbsent(idx, head) != null) {
                        throw new MzPeakException("duplicate spectrum.index in metadata: " + idx);
                    }
                    double[] model = head.mzDeltaModel;
                    if (model.length > 0) {
                        deltaModels.put(idx, model);
                    }
                }
            }
            indexFacet(row, "scan", scansBySource);
            indexFacet(row, "precursor", precursorsBySource);
            indexFacet(row, "selected_ion", selectedIonsBySource);
        });

        List<SpectrumDescription> out = new ArrayList<>(builders.size());
        for (SpectrumHead b : builders.values()) {
            List<ScanEvent> scans = buildScans(scansBySource.get(b.index));
            List<Precursor> precursors = PrecursorJoin.join(
                    precursorsBySource.get(b.index), selectedIonsBySource.get(b.index));
            out.add(new SpectrumDescription(
                    b.index, b.id, b.msLevel, b.time, b.polarity, b.continuity,
                    b.numberOfDataPoints, b.numberOfPeaks, scans, precursors,
                    b.spectrumParams));
        }
        out.sort((a, b) -> Long.compare(a.index(), b.index()));
        return new Decoded(out, deltaModels);
    }

    private static void indexFacet(Group row, String facetName, Map<Long, List<Group>> target) {
        Group facet = ParquetGroups.optGroup(row, facetName);
        if (facet != null) {
            Long src = ParquetGroups.optLong(facet, F_SOURCE_INDEX);
            if (src != null) {
                if (src < 0) {
                    // uint64 with the high bit set — out of the supported range; a negative key would be
                    // silently bucketed and never joined to a spectrum. Fail loudly, as for spectrum.index.
                    throw new MzPeakException(
                            facetName + ".source_index exceeds supported range (uint64 high bit set): " + src);
                }
                target.computeIfAbsent(src, k -> new ArrayList<>()).add(facet);
            }
        }
    }

    private static List<ScanEvent> buildScans(List<Group> scans) {
        if (scans == null) {
            return List.of();
        }
        List<ScanEvent> out = new ArrayList<>(scans.size());
        for (Group scan : scans) {
            out.add(ScanRowDecoder.decode(scan));
        }
        return out;
    }
}
