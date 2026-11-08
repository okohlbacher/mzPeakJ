package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Chromatogram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code chromatograms_metadata.parquet} + {@code chromatograms_data.parquet} into {@link Chromatogram}s.
 * The metadata member, packed or split, is decoded by {@link ChromatogramMetadataDecoder}; chromatograms keep its
 * order, and an index without data rows reads as empty arrays, without intensity unit or extra arrays.
 * The data member, in {@code point} or {@code chunk} layout, is decoded by {@link ChromatogramDataDecoder} through
 * its {@link SignalPlan}: from the member's {@code chromatogram_array_index} footer, else inferred from its column
 * names. The decoder also gives each chromatogram's intensity unit and extra arrays.
 */
final class ChromatogramStore {

    /** Names a member given directly as an {@link InputFile} in error messages, by its manifest role. */
    private static final String MEMBER = "chromatogram metadata member";

    private final List<Chromatogram> all;
    private final Map<Long, Chromatogram> byIndex;
    private final Map<String, Chromatogram> byId;
    private final SignalPlan plan;

    private ChromatogramStore(List<Chromatogram> all, SignalPlan plan) {
        this.all = List.copyOf(all);
        this.byIndex = new LinkedHashMap<>();
        this.byId = new LinkedHashMap<>();
        for (Chromatogram c : all) {
            byIndex.put(c.index(), c);
            if (c.id() != null) {
                byId.putIfAbsent(c.id(), c);
            }
        }
        this.plan = plan;
    }

    List<Chromatogram> all()                    { return all; }
    Optional<Chromatogram> byIndex(long index)  { return Optional.ofNullable(byIndex.get(index)); }
    Optional<Chromatogram> byId(String id)      { return Optional.ofNullable(byId.get(id)); }

    /** The plan the data member was decoded with, or {@code null} when it was not read (no chromatograms). */
    SignalPlan plan()                           { return plan; }

    /**
     * Loads the members that {@code manifest} lists under the roles (chromatogram, metadata) and (chromatogram, data
     * arrays); error messages name the metadata member by its file name.
     *
     * @return the store, or {@code null} when the manifest lacks either member
     */
    static ChromatogramStore load(MzPeakSource source, MzPeakManifest manifest) {
        Entry metadata = manifest.find(EntityType.CHROMATOGRAM, DataKind.METADATA).orElse(null);
        String dataName = manifest.find(EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS)
                .map(Entry::name).orElse(null);
        if (metadata == null || dataName == null) {
            return null;
        }
        return load(ChromatogramMetadataDecoder.decode(source, metadata), source.inputFile(dataName));
    }

    /**
     * Loads a metadata member of either layout and a data member given directly; error messages name the metadata
     * member by its role.
     */
    static ChromatogramStore load(InputFile metadataFile, InputFile dataFile) {
        Entry metadata = new Entry(MEMBER, EntityType.CHROMATOGRAM.canonical(), DataKind.METADATA.canonical());
        return load(ChromatogramMetadataDecoder.decode(singleMember(metadataFile), metadata), dataFile);
    }

    /** Pairs {@code headers} with the data member's arrays; the data member is not read when there are none. */
    private static ChromatogramStore load(List<ChromatogramMetadataDecoder.Header> headers, InputFile dataFile) {
        if (headers.isEmpty()) {
            return new ChromatogramStore(List.of(), null);
        }
        // ---- data: point or chunk layout, as the plan says -------------------------
        SignalPlan plan = plan(dataFile);
        Map<Long, ChromatogramDataDecoder.Decoded> data = ChromatogramDataDecoder.decode(dataFile, plan);

        // ---- assemble --------------------------------------------------------------
        List<Chromatogram> all = new ArrayList<>(headers.size());
        for (ChromatogramMetadataDecoder.Header h : headers) {
            ChromatogramDataDecoder.Decoded d = data.get(h.index());
            if (d == null) {
                all.add(new Chromatogram(h.index(), h.id(), h.typeCurie(), new double[0], new double[0]));
            } else {
                all.add(new Chromatogram(h.index(), h.id(), h.typeCurie(), d.time(), d.intensity(),
                        d.intensityUnit(), d.arrays()));
            }
        }
        return new ChromatogramStore(all, plan);
    }

    /** The signal plan of a data member: from its array index footer, else inferred from its column names. */
    private static SignalPlan plan(InputFile dataFile) {
        ParquetFooter footer = ParquetFooter.read(dataFile);
        return SignalPlan.of(ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.CHROMATOGRAM),
                footer.schema(), SignalEntity.CHROMATOGRAM);
    }

    /** A source whose every member is {@code file}; it has no manifest. */
    private static MzPeakSource singleMember(InputFile file) {
        return new MzPeakSource() {
            @Override
            public byte[] readManifestBytes() {
                throw new UnsupportedOperationException(MEMBER + " given directly has no manifest");
            }

            @Override
            public InputFile inputFile(String name) {
                return file;
            }

            @Override
            public String describe() {
                return MEMBER;
            }

            @Override
            public void close() {
            }
        };
    }
}
