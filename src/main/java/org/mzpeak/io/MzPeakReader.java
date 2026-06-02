package org.mzpeak.io;

import org.apache.parquet.io.InputFile;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Reads an mzPeak dataset — either an unpacked {@code *.mzpeak/} directory or a single-file (STORED)
 * {@code .mzpeak} ZIP. Surface: open, count, metadata access, spectrum (m/z + intensity + peaks)
 * materialization, lookup by index / scan number / native id / retention time, iteration, and chromatograms.
 *
 * <p>Metadata is loaded eagerly; signal files are loaded lazily and cached. Profile null-marked points are
 * reconstructed by default (so the point count matches the declared {@code number_of_data_points}); pass
 * {@code reconstructProfile=false} to get only the stored non-null points. Not thread-safe.
 */
public final class MzPeakReader implements Iterable<Spectrum>, AutoCloseable {

    private final MzPeakSource source;
    private final MzPeakManifest manifest;
    private final boolean reconstructProfile;
    private final List<SpectrumDescription> descriptions;
    private final Map<Long, double[]> deltaModels;
    private final Map<Long, Integer> indexToOrdinal;
    private final Map<Integer, Long> scanNumberToIndex;
    private final Map<String, Long> idToIndex;
    private final double[] sortedTimes;
    private final long[] timeIndexOrder;

    private final String metadataFileName;
    private final String dataFileName;   // nullable
    private final String peaksFileName;  // nullable
    private boolean fileMetadataLoaded;
    private org.mzpeak.model.meta.FileMetadata fileMetadata;

    private boolean dataLoaded;
    private SpectrumArrayStore dataStore;
    private boolean peaksLoaded;
    private SpectrumArrayStore peakStore;
    private boolean chromatogramsLoaded;
    private ChromatogramStore chromatogramStore;
    private boolean wavelengthLoaded;
    private WavelengthSpectrumStore wavelengthStore;

    private MzPeakReader(MzPeakSource source, MzPeakManifest manifest, boolean reconstructProfile,
                         SpectrumMetadataDecoder.Decoded decoded, String metadataFileName,
                         String dataFileName, String peaksFileName) {
        this.source = source;
        this.manifest = manifest;
        this.reconstructProfile = reconstructProfile;
        this.descriptions = decoded.descriptions();
        this.deltaModels = decoded.deltaModels();
        this.metadataFileName = metadataFileName;
        this.dataFileName = dataFileName;
        this.peaksFileName = peaksFileName;

        this.indexToOrdinal = new HashMap<>();
        this.scanNumberToIndex = new HashMap<>();
        this.idToIndex = new HashMap<>();
        for (int i = 0; i < descriptions.size(); i++) {
            SpectrumDescription d = descriptions.get(i);
            indexToOrdinal.put(d.index(), i);
            if (d.id() != null) {
                idToIndex.put(d.id(), d.index());
                org.mzpeak.model.NativeId.scanNumber(d.id())
                        .ifPresent(scan -> scanNumberToIndex.putIfAbsent(scan, d.index()));
            }
        }
        // RT lookup: (time, index) sorted by time, excluding spectra with non-finite retention times.
        List<Integer> finite = new ArrayList<>();
        for (int i = 0; i < descriptions.size(); i++) {
            if (Double.isFinite(descriptions.get(i).retentionTime())) {
                finite.add(i);
            }
        }
        finite.sort((a, b) ->
                Double.compare(descriptions.get(a).retentionTime(), descriptions.get(b).retentionTime()));
        this.sortedTimes = new double[finite.size()];
        this.timeIndexOrder = new long[finite.size()];
        for (int i = 0; i < finite.size(); i++) {
            sortedTimes[i] = descriptions.get(finite.get(i)).retentionTime();
            timeIndexOrder[i] = descriptions.get(finite.get(i)).index();
        }
    }

    /** Open an mzPeak dataset (directory or ZIP), reconstructing null-marked profile points. */
    public static MzPeakReader open(Path path) {
        return open(path, true);
    }

    /** Open an mzPeak dataset; {@code reconstructProfile=false} returns only stored (non-null) profile points. */
    public static MzPeakReader open(Path path, boolean reconstructProfile) {
        MzPeakSource source = MzPeakSource.open(path);
        try {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            MzPeakManifest.Entry metaEntry = manifest.find("spectrum", "metadata")
                    .orElseThrow(() -> new MzPeakException("Manifest has no spectrum metadata file"));
            SpectrumMetadataDecoder.Decoded decoded =
                    SpectrumMetadataDecoder.decode(source.inputFile(metaEntry.name()));
            String dataFile = manifest.find("spectrum", "data arrays").map(MzPeakManifest.Entry::name).orElse(null);
            String peaksFile = manifest.find("spectrum", "peaks").map(MzPeakManifest.Entry::name).orElse(null);
            return new MzPeakReader(source, manifest, reconstructProfile, decoded,
                    metaEntry.name(), dataFile, peaksFile);
        } catch (RuntimeException e) {
            source.close();
            throw e;
        }
    }

    public MzPeakManifest manifest() {
        return manifest;
    }

    public int size() {
        return descriptions.size();
    }

    public List<SpectrumDescription> metadata() {
        return Collections.unmodifiableList(descriptions);
    }

    public Optional<SpectrumDescription> getMetadata(long index) {
        Integer ordinal = indexToOrdinal.get(index);
        return ordinal == null ? Optional.empty() : Optional.of(descriptions.get(ordinal));
    }

    public SpectrumDescription metadataAt(int ordinal) {
        return descriptions.get(ordinal);
    }

    public Optional<Spectrum> getSpectrum(long index) {
        Integer ordinal = indexToOrdinal.get(index);
        return ordinal == null ? Optional.empty() : Optional.of(materialize(descriptions.get(ordinal)));
    }

    public Spectrum getSpectrumAt(int ordinal) {
        return materialize(descriptions.get(ordinal));
    }

    /** Look up by native id string (e.g. {@code "controllerType=0 controllerNumber=1 scan=3"}). */
    public Optional<Spectrum> getSpectrumById(String id) {
        Long index = idToIndex.get(id);
        return index == null ? Optional.empty() : getSpectrum(index);
    }

    /** Look up by vendor scan number parsed from the native id. */
    public Optional<Spectrum> getSpectrumByScanNumber(int scanNumber) {
        Long index = scanNumberToIndex.get(scanNumber);
        return index == null ? Optional.empty() : getSpectrum(index);
    }

    /** The spectrum whose retention time is nearest {@code time}; empty only if there are no spectra. */
    public Optional<Spectrum> getSpectrumByTime(double time) {
        if (sortedTimes.length == 0 || !Double.isFinite(time)) {
            return Optional.empty();
        }
        int pos = java.util.Arrays.binarySearch(sortedTimes, time);
        if (pos < 0) {
            int ins = -pos - 1;
            if (ins == 0) {
                pos = 0;
            } else if (ins >= sortedTimes.length) {
                pos = sortedTimes.length - 1;
            } else {
                pos = (time - sortedTimes[ins - 1] <= sortedTimes[ins] - time) ? ins - 1 : ins;
            }
        }
        return getSpectrum(timeIndexOrder[pos]);
    }

    private Spectrum materialize(SpectrumDescription description) {
        long index = description.index();
        SpectrumArrayStore.Arrays profile = data() == null ? null : data().get(index);
        SpectrumArrayStore.Arrays centroids = peaks() == null ? null : peaks().get(index);

        List<CentroidPeak> peakList = List.of();
        if (centroids != null) {
            double[] cmz = centroids.mz();
            double[] cin = centroids.intensity();
            List<CentroidPeak> built = new ArrayList<>(cmz.length);
            for (int i = 0; i < cmz.length; i++) {
                built.add(new CentroidPeak(cmz[i], cin[i], i));
            }
            peakList = built;
        }

        double[] mz;
        double[] intensity;
        if (profile != null) {
            mz = profile.mz();
            intensity = profile.intensity();
        } else if (centroids != null) {
            mz = centroids.mz();
            intensity = centroids.intensity();
        } else {
            mz = new double[0];
            intensity = new double[0];
        }
        return new Spectrum(description, mz, intensity, peakList);
    }

    // ---- chromatograms ----------------------------------------------------------------------------

    /** All chromatograms (TIC, BPC, ...) ordered by index. */
    public List<org.mzpeak.model.Chromatogram> chromatograms() {
        return chromatograms0() == null ? List.of() : chromatograms0().all();
    }

    public Optional<org.mzpeak.model.Chromatogram> getChromatogram(long index) {
        return chromatograms0() == null ? Optional.empty() : chromatograms0().byIndex(index);
    }

    public Optional<org.mzpeak.model.Chromatogram> getChromatogramById(String id) {
        return chromatograms0() == null ? Optional.empty() : chromatograms0().byId(id);
    }

    // ---- wavelength (UV/DAD) spectra --------------------------------------------------------------

    /** All wavelength (UV/DAD) spectra ordered by index; empty if the dataset has none. */
    public List<org.mzpeak.model.WavelengthSpectrum> wavelengthSpectra() {
        return wavelength0() == null ? List.of() : wavelength0().all();
    }

    public Optional<org.mzpeak.model.WavelengthSpectrum> getWavelengthSpectrum(long index) {
        return wavelength0() == null ? Optional.empty() : wavelength0().byIndex(index);
    }

    // ---- file/run metadata ------------------------------------------------------------------------

    /** File/run-level metadata (instrument, software, run, file description, ...) from the Parquet footer. */
    public synchronized org.mzpeak.model.meta.FileMetadata fileMetadata() {
        if (!fileMetadataLoaded) {
            fileMetadata = FooterMetadataReader.read(source.inputFile(metadataFileName));
            fileMetadataLoaded = true;
        }
        return fileMetadata;
    }

    // ---- lazy stores ------------------------------------------------------------------------------

    private synchronized SpectrumArrayStore data() {
        if (!dataLoaded) {
            dataStore = dataFileName == null ? null
                    : SpectrumArrayStore.load(source.inputFile(dataFileName), reconstructProfile, deltaModels);
            dataLoaded = true;
        }
        return dataStore;
    }

    private synchronized SpectrumArrayStore peaks() {
        if (!peaksLoaded) {
            peakStore = peaksFileName == null ? null
                    : SpectrumArrayStore.load(source.inputFile(peaksFileName), reconstructProfile, deltaModels);
            peaksLoaded = true;
        }
        return peakStore;
    }

    private synchronized ChromatogramStore chromatograms0() {
        if (!chromatogramsLoaded) {
            String metaName = manifest.find("chromatogram", "metadata").map(MzPeakManifest.Entry::name).orElse(null);
            String dataName = manifest.find("chromatogram", "data arrays").map(MzPeakManifest.Entry::name).orElse(null);
            chromatogramStore = (metaName == null || dataName == null) ? null
                    : ChromatogramStore.load(source.inputFile(metaName), source.inputFile(dataName));
            chromatogramsLoaded = true;
        }
        return chromatogramStore;
    }

    private synchronized WavelengthSpectrumStore wavelength0() {
        if (!wavelengthLoaded) {
            String metaName = manifest.find("wavelength spectrum", "metadata")
                    .map(MzPeakManifest.Entry::name).orElse(null);
            String dataName = manifest.find("wavelength spectrum", "data arrays")
                    .map(MzPeakManifest.Entry::name).orElse(null);
            wavelengthStore = (metaName == null || dataName == null) ? null
                    : WavelengthSpectrumStore.load(source.inputFile(metaName), source.inputFile(dataName));
            wavelengthLoaded = true;
        }
        return wavelengthStore;
    }

    @Override
    public Iterator<Spectrum> iterator() {
        return new Iterator<>() {
            private int pos = 0;

            @Override
            public boolean hasNext() {
                return pos < descriptions.size();
            }

            @Override
            public Spectrum next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getSpectrumAt(pos++);
            }
        };
    }

    @Override
    public void close() {
        if (dataStore != null) {
            dataStore.close();
            dataStore = null;
        }
        if (peakStore != null) {
            peakStore.close();
            peakStore = null;
        }
        chromatogramStore = null;
        wavelengthStore = null;
        source.close();
    }
}
