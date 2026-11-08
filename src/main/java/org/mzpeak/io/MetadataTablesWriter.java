package org.mzpeak.io;

import org.mzpeak.io.ManifestWriter.Dialect;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the metadata members of one metadata layout for {@link DatasetWriter} (design §11.3): the spectrum,
 * chromatogram and wavelength-spectrum metadata, and the manifest dialect that lists them. Each method writes its
 * members into the dataset directory and appends each to {@code members}, in manifest order, before the member is
 * written; every member is closed when the method returns.
 */
interface MetadataTablesWriter {

    /** The manifest dialect of this layout. */
    Dialect dialect();

    /** Writes the spectrum metadata of {@code plan}, which is written whether or not the plan has spectra. */
    void writeSpectrumMetadata(Path directory, List<WrittenMember> members, WritePlan plan) throws IOException;

    /** Writes the chromatogram metadata of {@code plan}; called only when the plan writes chromatograms. */
    void writeChromatogramMetadata(Path directory, List<WrittenMember> members, WritePlan plan) throws IOException;

    /** Writes the wavelength-spectrum metadata of {@code plan}; called only when the plan writes wavelength spectra. */
    void writeWavelengthMetadata(Path directory, List<WrittenMember> members, WritePlan plan) throws IOException;
}
