package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.WavelengthSpectrum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code wavelength_spectra_metadata.parquet} + {@code wavelength_spectra_data.parquet} into
 * {@link WavelengthSpectrum}s. The metadata member, packed or split, is decoded by
 * {@link WavelengthMetadataDecoder}; spectra keep its order, and an index without data rows reads as empty arrays.
 * Their scans come from the split scans member {@code wavelength_spectra_metadata_scans} when the manifest lists one
 * (R8); without it, as in the legacy packed layout, whose scans live in its metadata member and are not read
 * (D13-24), and for members given directly, spectra have no scans.
 * The data member is read through its {@link SignalPlan}: from its {@code wavelength_spectrum_array_index} footer,
 * else inferred from its column names. In {@code point} layout its rows are grouped by {@link PointArrays}; in
 * {@code chunk} layout (C5, wavelength-spectra.md: point or chunked layout) each chunk row is decoded by
 * {@link ChunkRowDecoder} with the wavelength as its main axis, and the chunks of one index are appended in row order;
 * a null-marked wavelength of a chunk reads as NaN, as a null point wavelength does. A data member that cannot be read
 * that way throws an {@link MzPeakException} naming it, instead of giving empty arrays: one without the plan's prefix
 * struct, one whose plan names no wavelength values column or no value-bearing intensity column, and one with a chunk
 * row that {@link ChunkRowDecoder} cannot decode (naming the wavelength spectrum index).
 */
final class WavelengthSpectrumStore {

    /** Names a member given directly as an {@link InputFile} in error messages, by its manifest role. */
    private static final String MEMBER = "wavelength spectrum metadata member";
    private static final String DATA_MEMBER = "wavelength spectrum data member";

    private final List<WavelengthSpectrum> all;
    private final Map<Long, WavelengthSpectrum> byIndex;
    private final SignalPlan plan;

    private WavelengthSpectrumStore(List<WavelengthSpectrum> all, SignalPlan plan) {
        this.all = List.copyOf(all);
        this.byIndex = new LinkedHashMap<>();
        for (WavelengthSpectrum w : all) {
            byIndex.put(w.index(), w);
        }
        this.plan = plan;
    }

    List<WavelengthSpectrum> all() {
        return all;
    }

    Optional<WavelengthSpectrum> byIndex(long index) {
        return Optional.ofNullable(byIndex.get(index));
    }

    /** The plan the data member was decoded with, or {@code null} when it was not read (no wavelength spectra). */
    SignalPlan plan() {
        return plan;
    }

    /**
     * Loads the members that {@code manifest} lists under the roles (wavelength spectrum, metadata) and (wavelength
     * spectrum, data arrays), with the scans of the (wavelength spectrum, scans) member when it lists one; error
     * messages name the members by their file names.
     *
     * @return the store, or {@code null} when the manifest lacks either member
     */
    static WavelengthSpectrumStore load(MzPeakSource source, MzPeakManifest manifest) {
        Entry metadata = manifest.find(EntityType.WAVELENGTH_SPECTRUM, DataKind.METADATA).orElse(null);
        String dataName = manifest.find(EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS)
                .map(Entry::name).orElse(null);
        if (metadata == null || dataName == null) {
            return null;
        }
        Map<Long, List<ScanEvent>> scans = Facet.WAVELENGTH_SCAN.entry(manifest)
                .map(e -> WavelengthMetadataDecoder.scans(source.inputFile(e.name()), e)).orElse(Map.of());
        return load(WavelengthMetadataDecoder.decode(source.inputFile(metadata.name()), metadata), scans,
                source.inputFile(dataName), dataName);
    }

    /**
     * Loads a metadata member of either layout and a data member given directly, without scans; error messages name
     * the members by their roles.
     */
    static WavelengthSpectrumStore load(InputFile metadataFile, InputFile dataFile) {
        Entry metadata = new Entry(MEMBER, EntityType.WAVELENGTH_SPECTRUM.canonical(), DataKind.METADATA.canonical());
        return load(WavelengthMetadataDecoder.decode(metadataFile, metadata), Map.of(), dataFile, DATA_MEMBER);
    }

    /**
     * Pairs {@code headers} with their {@code scans}, by index, and the data member's arrays; the data member is not
     * read when there are no headers. Errors name the data member {@code dataName}.
     */
    private static WavelengthSpectrumStore load(List<WavelengthMetadataDecoder.Header> headers,
                                                Map<Long, List<ScanEvent>> scans, InputFile dataFile,
                                                String dataName) {
        if (headers.isEmpty()) {
            return new WavelengthSpectrumStore(List.of(), null);
        }
        ParquetFooter footer = ParquetFooter.read(dataFile);
        SignalPlan plan = SignalPlan.of(
                ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.WAVELENGTH_SPECTRUM), footer.schema(),
                SignalEntity.WAVELENGTH_SPECTRUM);
        requireReadable(plan, footer.schema(), dataName);
        Map<Long, PointArrays.XY> data = plan.layout() == SignalPlan.Layout.CHUNK
                ? groupChunks(dataFile, plan, dataName) : PointArrays.groupByIndex(dataFile, plan, dataName);

        List<WavelengthSpectrum> all = new ArrayList<>(headers.size());
        for (WavelengthMetadataDecoder.Header h : headers) {
            PointArrays.XY xy = data.get(h.index());
            double[] wavelength = xy == null ? new double[0] : xy.x();
            double[] intensity = xy == null ? new double[0] : xy.intensity();
            all.add(new WavelengthSpectrum(h.index(), h.id(), h.time(), h.lambdaMax(), wavelength, intensity,
                    h.parameters(), scans.get(h.index()), h.auxiliaryArrays()));
        }
        return new WavelengthSpectrumStore(all, plan);
    }

    /**
     * Decodes the chunk rows of a data file with {@link ChunkRowDecoder} and appends their wavelengths and
     * intensities by the plan's index field, in the order the indices first occur; rows without the prefix struct or
     * an index are skipped, as point rows are.
     *
     * @param member the name of the data member in errors
     */
    private static Map<Long, PointArrays.XY> groupChunks(InputFile dataFile, SignalPlan plan, String member) {
        ChunkRowDecoder decoder = new ChunkRowDecoder(plan);
        Map<Long, DoubleArrayBuilder[]> acc = new LinkedHashMap<>();
        ParquetGroups.forEach(dataFile, row -> {
            Group chunk = ParquetGroups.optGroup(row, plan.prefix());
            if (chunk == null) {
                return;
            }
            Long idx = ParquetGroups.optIndexKey(chunk, plan.indexField(), member);
            if (idx == null) {
                return;
            }
            ChunkRowDecoder.Row decoded = decoder.decodeSignal(chunk, idx);
            DoubleArrayBuilder[] b = acc.computeIfAbsent(idx,
                    k -> new DoubleArrayBuilder[] {new DoubleArrayBuilder(), new DoubleArrayBuilder()});
            for (int i = 0; i < decoded.axis().length; i++) {
                b[0].add(decoded.axis()[i]);
                b[1].add(decoded.intensity()[i]);
            }
        });
        Map<Long, PointArrays.XY> out = new LinkedHashMap<>();
        acc.forEach((idx, b) -> out.put(idx, new PointArrays.XY(b[0].toArray(), b[1].toArray())));
        return out;
    }

    /** Throws unless {@code plan} reads the rows of the data member {@code dataName} of schema {@code schema}. */
    private static void requireReadable(SignalPlan plan, MessageType schema, String dataName) {
        if (!schema.containsField(plan.prefix()) || schema.getType(plan.prefix()).isPrimitive()) {
            throw new MzPeakException(dataName + " has no '" + plan.prefix() + "' struct, so its wavelength spectra"
                    + " cannot be read");
        }
        if (plan.axis().values() == null) {
            throw new MzPeakException(dataName + ": the signal plan names no wavelength values column");
        }
        if (plan.plainIntensity().isEmpty()) {
            throw new MzPeakException(dataName + ": the signal plan names no value-bearing intensity column");
        }
    }
}
