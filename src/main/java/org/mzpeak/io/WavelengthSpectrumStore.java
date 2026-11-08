package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.apache.parquet.schema.MessageType;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakManifest.Entry;
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
 * UV/DAD data is dense {@code point} layout (no null-marking, no precursors), grouped by {@link PointArrays} through
 * the data member's {@link SignalPlan}: from its {@code wavelength_spectrum_array_index} footer, else inferred from
 * its column names. A data member that cannot be read that way throws an {@link MzPeakException} naming it, instead
 * of giving empty arrays: one in chunk layout (chunked wavelength spectra are not decoded), one without the plan's
 * prefix struct, and one whose plan names no wavelength values column or no value-bearing intensity column.
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
     * spectrum, data arrays); error messages name the metadata member by its file name.
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
        return load(WavelengthMetadataDecoder.decode(source, metadata), source.inputFile(dataName), dataName);
    }

    /**
     * Loads a metadata member of either layout and a data member given directly; error messages name the members by
     * their roles.
     */
    static WavelengthSpectrumStore load(InputFile metadataFile, InputFile dataFile) {
        Entry metadata = new Entry(MEMBER, EntityType.WAVELENGTH_SPECTRUM.canonical(), DataKind.METADATA.canonical());
        return load(WavelengthMetadataDecoder.decode(singleMember(metadataFile), metadata), dataFile, DATA_MEMBER);
    }

    /**
     * Pairs {@code headers} with the data member's arrays; the data member is not read when there are none. Errors
     * name the data member {@code dataName}.
     */
    private static WavelengthSpectrumStore load(List<WavelengthMetadataDecoder.Header> headers, InputFile dataFile,
                                                String dataName) {
        if (headers.isEmpty()) {
            return new WavelengthSpectrumStore(List.of(), null);
        }
        ParquetFooter footer = ParquetFooter.read(dataFile);
        SignalPlan plan = SignalPlan.of(
                ArrayIndex.of(footer.keyValue(), footer.schema(), SignalEntity.WAVELENGTH_SPECTRUM), footer.schema(),
                SignalEntity.WAVELENGTH_SPECTRUM);
        requireReadable(plan, footer.schema(), dataName);
        Map<Long, PointArrays.XY> data = PointArrays.groupByIndex(dataFile, plan);

        List<WavelengthSpectrum> all = new ArrayList<>(headers.size());
        for (WavelengthMetadataDecoder.Header h : headers) {
            PointArrays.XY xy = data.get(h.index());
            double[] wavelength = xy == null ? new double[0] : xy.x();
            double[] intensity = xy == null ? new double[0] : xy.intensity();
            all.add(new WavelengthSpectrum(h.index(), h.id(), h.time(), h.lambdaMax(), wavelength, intensity));
        }
        return new WavelengthSpectrumStore(all, plan);
    }

    /** Throws unless {@code plan} reads the point rows of the data member {@code dataName} of schema {@code schema}. */
    private static void requireReadable(SignalPlan plan, MessageType schema, String dataName) {
        if (plan.layout() == SignalPlan.Layout.CHUNK) {
            throw new MzPeakException(dataName + ": chunked wavelength spectra are not supported");
        }
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
