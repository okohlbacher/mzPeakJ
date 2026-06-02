package org.mzpeak.io;

import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Files;
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
 * Reads an unpacked mzPeak directory (the {@code *.mzpeak/} form). Minimal milestone-1 surface:
 * open, count, metadata access, and spectrum (m/z + intensity) materialization, plus iteration.
 *
 * <p>Spectrum metadata is loaded eagerly on {@link #open(Path)}. The point/peak signal files are loaded
 * lazily on first access and cached. Supports the {@code point} layout, unpacked directory, spectra only.
 *
 * <p>Not thread-safe for concurrent first-access loading; treat one reader as single-threaded.
 */
public final class MzPeakReader implements Iterable<Spectrum>, AutoCloseable {

    private final MzPeakManifest manifest;
    private final List<SpectrumDescription> descriptions;
    private final Map<Long, Integer> indexToOrdinal;
    private final Path dataFile;   // nullable: spectra_data.parquet (profile points)
    private final Path peaksFile;  // nullable: spectra_peaks.parquet (centroids)

    private boolean dataLoaded;
    private PointArrayStore dataStore;
    private boolean peaksLoaded;
    private PointArrayStore peakStore;

    private MzPeakReader(MzPeakManifest manifest,
                         List<SpectrumDescription> descriptions,
                         Path dataFile,
                         Path peaksFile) {
        this.manifest = manifest;
        this.descriptions = descriptions;
        this.dataFile = dataFile;
        this.peaksFile = peaksFile;
        this.indexToOrdinal = new HashMap<>();
        for (int i = 0; i < descriptions.size(); i++) {
            indexToOrdinal.put(descriptions.get(i).index(), i);
        }
    }

    /** Open an unpacked mzPeak directory. */
    public static MzPeakReader open(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new MzPeakException("Not a directory: " + directory
                    + " (milestone 1 supports only the unpacked *.mzpeak directory form)");
        }
        MzPeakManifest manifest = MzPeakManifest.fromDirectory(directory);
        MzPeakManifest.Entry metaEntry = manifest.find("spectrum", "metadata")
                .orElseThrow(() -> new MzPeakException("Manifest has no spectrum metadata file"));
        List<SpectrumDescription> descriptions =
                SpectrumMetadataDecoder.decode(directory.resolve(metaEntry.name()));
        Path dataFile = manifest.find("spectrum", "data arrays")
                .map(e -> directory.resolve(e.name())).orElse(null);
        Path peaksFile = manifest.find("spectrum", "peaks")
                .map(e -> directory.resolve(e.name())).orElse(null);
        return new MzPeakReader(manifest, descriptions, dataFile, peaksFile);
    }

    public MzPeakManifest manifest() {
        return manifest;
    }

    /** Number of spectra. */
    public int size() {
        return descriptions.size();
    }

    /** All spectrum metadata, ordered by spectrum index. */
    public List<SpectrumDescription> metadata() {
        return Collections.unmodifiableList(descriptions);
    }

    /** Metadata for a spectrum by its mzPeak {@code index}. */
    public Optional<SpectrumDescription> getMetadata(long index) {
        Integer ordinal = indexToOrdinal.get(index);
        return ordinal == null ? Optional.empty() : Optional.of(descriptions.get(ordinal));
    }

    /** Metadata by 0-based position in the (index-sorted) spectrum list. */
    public SpectrumDescription metadataAt(int ordinal) {
        return descriptions.get(ordinal);
    }

    /** Full spectrum (metadata + m/z/intensity arrays + centroid peaks) by mzPeak {@code index}. */
    public Optional<Spectrum> getSpectrum(long index) {
        Integer ordinal = indexToOrdinal.get(index);
        if (ordinal == null) {
            return Optional.empty();
        }
        return Optional.of(materialize(descriptions.get(ordinal)));
    }

    /** Full spectrum by 0-based position in the spectrum list. */
    public Spectrum getSpectrumAt(int ordinal) {
        return materialize(descriptions.get(ordinal));
    }

    private Spectrum materialize(SpectrumDescription description) {
        long index = description.index();
        PointArrayStore.Arrays profile = data() == null ? null : data().get(index);
        PointArrayStore.Arrays centroids = peaks() == null ? null : peaks().get(index);

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
            // Centroid-only spectrum: expose the centroid coordinates as the array payload too,
            // so consumers (incl. the FragPipe adapter) always get arrays.
            mz = centroids.mz();
            intensity = centroids.intensity();
        } else {
            mz = new double[0];
            intensity = new double[0];
        }
        return new Spectrum(description, mz, intensity, peakList);
    }

    private synchronized PointArrayStore data() {
        if (!dataLoaded) {
            dataStore = dataFile == null ? null : PointArrayStore.load(dataFile);
            dataLoaded = true;
        }
        return dataStore;
    }

    private synchronized PointArrayStore peaks() {
        if (!peaksLoaded) {
            peakStore = peaksFile == null ? null : PointArrayStore.load(peaksFile);
            peaksLoaded = true;
        }
        return peakStore;
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
        // In-memory caches; nothing to release in the prototype.
        dataStore = null;
        peakStore = null;
    }
}
