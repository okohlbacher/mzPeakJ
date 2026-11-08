package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.ParquetOutput.SignalFile;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.SpectrumSignal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mzpeak.io.ManifestWriter.writeManifest;
import static org.mzpeak.io.NumpressSignalWriter.appendNumpressChunks;
import static org.mzpeak.io.NumpressSignalWriter.openNumpressChunks;
import static org.mzpeak.io.PointSignalWriter.appendSpectrumPoints;
import static org.mzpeak.io.PointSignalWriter.openSpectrumPoints;
import static org.mzpeak.io.PointSignalWriter.writeChromatogramData;
import static org.mzpeak.io.PointSignalWriter.writeWavelengthData;

/**
 * Writes a {@link WritePlan} into an unpacked dataset directory, one pipeline for both signal encodings (design §7,
 * MZ-10): one pass over the plan's spectra that appends each spectrum's signal to spectra_data and spectra_peaks in
 * the plan's signal encoding ({@link SpectrumSignals}), then the spectrum metadata from the plan's spectrum entries,
 * chromatograms and wavelength spectra in point layout, and the manifest last, once every member is closed, so that
 * it can list each member's checksum. Only the members the plan names are written, and the manifest lists them in the
 * order spectrum metadata, spectra_data, spectra_peaks, chromatograms, wavelength spectra. The metadata members are
 * written by the {@link MetadataTablesWriter} of the plan's metadata layout, whose dialect the manifest uses.
 */
final class DatasetWriter {

    private DatasetWriter() {
    }

    static void write(Path directory, WritePlan plan) {
        boolean numpress = plan.options().signalEncoding() == SignalEncoding.NUMPRESS;
        MetadataTablesWriter metadata = MetadataTablesWriter.of(plan.options().metadataLayout());
        List<WrittenMember> members = new ArrayList<>();
        try {
            Files.createDirectories(directory);
            List<WrittenMember> signalMembers = new ArrayList<>();
            try (SpectrumSignals signals = new SpectrumSignals(directory, signalMembers, plan)) {
                for (SpectrumSignal spectrum : plan.spectrumSignals()) {
                    signals.append(spectrum);
                }
            }
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
        } catch (IOException e) {
            throw new MzPeakException((numpress ? "Failed to write Numpress mzPeak dataset to "
                    : "Failed to write mzPeak dataset to ") + directory, e);
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
     * spectra_data and spectra_peaks of a dataset being written in the plan's signal encoding, to which the signal of
     * one spectrum at a time is appended (design §7, MZ-10). spectra_data is opened at once when the plan writes it,
     * spectra_peaks when the first spectrum whose rows go there is appended; each is recorded in {@code members} when
     * it opens. A spectrum's rows go to the files its {@link WritePlan.SpectrumEntry} names; a plan that does not write
     * spectra_data sends none there. {@link #close()} closes the open files, each with the spectra appended as its
     * {@code spectrum_count}.
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

        /** Opens spectra_data in {@code directory} when {@code plan} writes it. */
        SpectrumSignals(Path directory, List<WrittenMember> members, WritePlan plan) throws IOException {
            this.directory = directory;
            this.members = members;
            this.extras = plan.spectrumExtras();
            this.options = plan.options();
            this.numpress = options.signalEncoding() == SignalEncoding.NUMPRESS;
            this.data = plan.writesSpectraData() ? open("spectra_data.parquet", DataKind.DATA_ARRAYS) : null;
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
