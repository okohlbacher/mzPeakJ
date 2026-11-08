package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.ParquetOutput.SignalFile;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.io.WritePlan.SpectrumSignal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.mzpeak.io.ManifestWriter.writeManifest;
import static org.mzpeak.io.NumpressSignalWriter.appendNumpressChunks;
import static org.mzpeak.io.NumpressSignalWriter.openNumpressChunks;
import static org.mzpeak.io.PointSignalWriter.appendSpectrumPoints;
import static org.mzpeak.io.PointSignalWriter.openSpectrumPoints;
import static org.mzpeak.io.PointSignalWriter.writeChromatogramData;
import static org.mzpeak.io.PointSignalWriter.writeWavelengthData;

/**
 * Writes one dataset directory for a {@link MzPeakWriter.Session}, one pipeline for both signal encodings (design §7,
 * MZ-10, MZ-11): {@link #open()} creates the directory if need be and opens spectra_data; {@link #append} appends the
 * signal of one spectrum at a time to spectra_data and spectra_peaks in the signal encoding of the options
 * ({@link SpectrumSignals}) and keeps only the spectrum's light {@link SpectrumEntry}; {@link #closeSignals()} closes
 * both with the spectra appended as their spectrum_count; {@link #writeMetadata} then writes the spectrum metadata from
 * the plan's spectrum entries, which are the entries appended, the chromatograms and wavelength spectra in point
 * layout, and the manifest last, once every member is closed, so that it can list each member's checksum. Only the
 * members the plan names are written, and the manifest lists them in the order spectrum metadata, spectra_data,
 * spectra_peaks, chromatograms, wavelength spectra. The metadata members are written by the
 * {@link MetadataTablesWriter} of the plan's metadata layout, whose dialect the manifest uses. Every member is written
 * in parquet's OVERWRITE mode, replacing a file of its name, and no other file of the directory is touched. An I/O
 * failure throws {@link MzPeakException} naming the directory.
 */
final class DatasetWriter {

    private final Path directory;
    private final Options options;
    private final List<ExtraColumn> extras;
    private final boolean writesSpectraData;
    /** spectra_data and spectra_peaks as they open, which the manifest lists after the spectrum metadata. */
    private final List<WrittenMember> signalMembers = new ArrayList<>();
    /** The other members as they are written, then the manifest's list with the signal members inserted. */
    private final List<WrittenMember> members = new ArrayList<>();
    private final List<SpectrumEntry> entries = new ArrayList<>();
    /** The open spectrum signal files, null before {@link #open()} and after {@link #closeSignals()}. */
    private SpectrumSignals signals;

    /**
     * A writer of {@code directory} whose spectrum signal files have the {@code extras} columns, written with
     * {@code options}; spectra_data is written when {@code writesSpectraData} says so ({@link WritePlan}).
     */
    DatasetWriter(Path directory, Options options, List<ExtraColumn> extras, boolean writesSpectraData) {
        this.directory = directory;
        this.options = options;
        this.extras = extras;
        this.writesSpectraData = writesSpectraData;
    }

    /** Creates the directory, with its parents, when it does not exist, and opens spectra_data when it is written. */
    void open() {
        io(() -> {
            Files.createDirectories(directory);
            signals = new SpectrumSignals(directory, signalMembers, extras, options, writesSpectraData);
        });
    }

    /** Appends the rows of {@code spectrum} to the signal files its entry names and keeps its entry. */
    void append(SpectrumSignal spectrum) {
        io(() -> signals.append(spectrum));
        entries.add(spectrum.entry());
    }

    /** The entries of the spectra appended, in the order appended (unmodifiable view). */
    List<SpectrumEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /** Closes the open spectrum signal files, if any, each with the spectra appended as its spectrum_count. */
    void closeSignals() {
        if (signals != null) {
            SpectrumSignals open = signals;
            signals = null;
            io(open::close);
        }
    }

    /**
     * Writes the metadata members, chromatograms_data and wavelength_spectra_data of {@code plan}, whose spectra are
     * the entries appended, and the manifest last; the spectrum signal files must be closed.
     */
    void writeMetadata(WritePlan plan) {
        MetadataTablesWriter metadata = MetadataTablesWriter.of(plan.options().metadataLayout());
        io(() -> {
            metadata.writeSpectrumMetadata(directory, members, plan);
            members.addAll(signalMembers);
            if (plan.writesChromatograms()) {
                metadata.writeChromatogramMetadata(directory, members, plan);
                writeChromatogramData(member(directory, members, "chromatograms_data.parquet",
                        EntityType.CHROMATOGRAM, DataKind.DATA_ARRAYS), plan);
            }
            if (plan.writesWavelengthSpectra()) {
                metadata.writeWavelengthMetadata(directory, members, plan);
                writeWavelengthData(member(directory, members, "wavelength_spectra_data.parquet",
                        EntityType.WAVELENGTH_SPECTRUM, DataKind.DATA_ARRAYS), plan);
            }
            writeManifest(directory, members, plan, metadata.dialect());
        });
    }

    /**
     * Deletes every member this writer has begun to write and the manifest, for a directory that held none of them
     * when the write began, and with {@code andDirectory} the directory too when nothing else is in it. Best effort:
     * what cannot be deleted stays. The signal files must be closed.
     */
    void deleteFiles(boolean andDirectory) {
        Set<Path> files = new LinkedHashSet<>();
        for (WrittenMember member : signalMembers) {
            files.add(directory.resolve(member.name()));
        }
        for (WrittenMember member : members) {
            files.add(directory.resolve(member.name()));
        }
        files.add(directory.resolve(ManifestWriter.INDEX));
        if (andDirectory) {
            files.add(directory);
        }
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // best effort: what cannot be deleted, such as a directory that holds another file, stays
            }
        }
    }

    /** A step of the write that may fail with an {@link IOException}. */
    private interface IoStep {
        void run() throws IOException;
    }

    /** Runs {@code step}; an {@link IOException} is thrown as {@link MzPeakException} naming the directory. */
    private void io(IoStep step) {
        try {
            step.run();
        } catch (IOException e) {
            throw new MzPeakException((options.signalEncoding() == SignalEncoding.NUMPRESS
                    ? "Failed to write Numpress mzPeak dataset to " : "Failed to write mzPeak dataset to ")
                    + directory, e);
        }
    }

    /**
     * Records {@code name} with its role as the next manifest entry and returns its path in {@code directory}. The
     * member's writer closes the file before the manifest is written.
     */
    static Path member(Path directory, List<WrittenMember> members, String name, EntityType entity, DataKind kind) {
        return member(directory, members, name, entity, kind, List.of());
    }

    /** {@link #member(Path, List, String, EntityType, DataKind)} with the member's {@code columnMapping}. */
    static Path member(Path directory, List<WrittenMember> members, String name, EntityType entity, DataKind kind,
                       List<ColumnMapping> columnMapping) {
        members.add(new WrittenMember(name, entity, kind, columnMapping));
        return directory.resolve(name);
    }

    /**
     * spectra_data and spectra_peaks of a dataset being written in the signal encoding of its options, to which the
     * signal of one spectrum at a time is appended (design §7, MZ-10). spectra_data is opened at once when it is
     * written, spectra_peaks when the first spectrum whose rows go there is appended; each is recorded in
     * {@code members} when it opens. A spectrum's rows go to the files its {@link WritePlan.SpectrumEntry} names; a
     * dataset that does not write spectra_data sends none there. {@link #close()} closes the open files, each with the
     * spectra appended as its {@code spectrum_count}.
     */
    static final class SpectrumSignals implements Closeable {

        private final Path directory;
        private final List<WrittenMember> members;
        private final List<ExtraColumn> extras;
        private final Options options;
        private final boolean numpress;
        private final SignalFile data;
        private SignalFile peaks;
        private long spectra;

        /**
         * Opens spectra_data in {@code directory}, with the {@code extras} columns and written with {@code options},
         * when {@code writesSpectraData}.
         */
        SpectrumSignals(Path directory, List<WrittenMember> members, List<ExtraColumn> extras, Options options,
                        boolean writesSpectraData) throws IOException {
            this.directory = directory;
            this.members = members;
            this.extras = extras;
            this.options = options;
            this.numpress = options.signalEncoding() == SignalEncoding.NUMPRESS;
            this.data = writesSpectraData ? open("spectra_data.parquet", DataKind.DATA_ARRAYS) : null;
        }

        /** Appends the rows of {@code spectrum} to the files its entry names, opening spectra_peaks on first need. */
        void append(SpectrumSignal spectrum) throws IOException {
            if (spectrum.entry().data()) {
                append(data, spectrum, false);
            }
            if (spectrum.entry().peaks()) {
                if (peaks == null) {
                    peaks = open("spectra_peaks.parquet", DataKind.PEAKS);
                }
                append(peaks, spectrum, true);
            }
            spectra++;
        }

        /** Closes the open files, each with the spectra appended as its {@code spectrum_count}. */
        @Override
        public void close() throws IOException {
            try (SignalFile dataFile = data; SignalFile peaksFile = peaks) {
                if (dataFile != null) {
                    dataFile.setEntityCount(spectra);
                }
                if (peaksFile != null) {
                    peaksFile.setEntityCount(spectra);
                }
            }
        }

        private SignalFile open(String name, DataKind kind) throws IOException {
            Path file = member(directory, members, name, EntityType.SPECTRUM, kind);
            return numpress ? openNumpressChunks(file, extras, options) : openSpectrumPoints(file, extras, options);
        }

        private void append(SignalFile file, SpectrumSignal spectrum, boolean peaksFile) throws IOException {
            if (numpress) {
                appendNumpressChunks(file, spectrum, extras, peaksFile, options.numpressIntensity());
            } else {
                appendSpectrumPoints(file, spectrum, extras, peaksFile);
            }
        }
    }
}
