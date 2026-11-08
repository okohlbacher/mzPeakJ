package org.mzpeak.io;

import org.mzpeak.examples.ExtractSpectra;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The datasets mzPeakJ writes for the gated interop tests: {@link UpstreamInteropTest} reads them with the HEAD Python
 * reader (WR-7), and the Rust interop test of WR-13 with the reference's {@code read} example. Every one is written by
 * a public {@link MzPeakWriter} method with the default metadata layout, the split one:
 * <ul>
 *   <li>the archive and the directory of legacy small.unpacked and of current small.unpacked, has_uv,
 *       Example_Processed.img and diaPASEF.ref, each read with {@link ForwardBackwardCorpusTest#readSource} and written
 *       by the 6-argument {@link MzPeakWriter#writeArchive} or {@link MzPeakWriter#writeDirectory} (point layout);
 *   <li>the Numpress SLOF archive ({@link MzPeakWriter#writeArchiveNumpress}) and the Numpress PIC archive (the
 *       {@link Options} form) of current small;
 *   <li>the MS2 subset of current small as the {@link ExtractSpectra} example writes it: its 34 MS2 spectra, numbered
 *       0..33, with the TIC.
 * </ul>
 * {@link #writePacked} writes legacy small.unpacked as an archive in the pin-era packed layout instead, which the
 * current readers reject.
 */
final class InteropOutputs {

    /** The source datasets of the archive and directory outputs, in output order. */
    static final List<Path> SOURCES = List.of(
            Fixtures.legacy("small.unpacked.mzpeak"),
            Fixtures.current("small.unpacked.mzpeak"),
            Fixtures.current("has_uv.mzpeak"),
            Fixtures.current("Example_Processed.img.mzpeak"),
            Fixtures.current("diaPASEF.ref.mzpeak"));

    /** Current small, the source of the Numpress archives and of the MS2 subset. */
    static final Path SMALL = Fixtures.current("small.mzpeak");

    /** How an output was written from its source. */
    enum Kind {
        /** Every entity of the source, point layout, as a STORED ZIP archive. */
        ARCHIVE,
        /** Every entity of the source, point layout, as an unpacked directory. */
        DIRECTORY,
        /** Every entity of the source as an archive, Numpress chunks with SLOF intensities. */
        NUMPRESS_SLOF,
        /** Every entity of the source as an archive, Numpress chunks with PIC intensities. */
        NUMPRESS_PIC,
        /** The MS2 spectra of the source and its chromatograms, as an archive, through {@link ExtractSpectra}. */
        MS2_SUBSET,
        /** Every entity of the source as an archive in the packed metadata layout. */
        PACKED_ARCHIVE
    }

    /**
     * One written dataset.
     *
     * @param name              a unique name, also the output's file name without {@code .mzpeak}
     * @param path              the archive or directory
     * @param source            the dataset it was written from
     * @param kind              how it was written
     * @param spectra           the spectra written, as mzPeakJ read them from the source, in the order they were
     *                          written, which is their written index 0..n-1
     * @param chromatograms     the chromatograms written, as mzPeakJ read them from the source
     * @param wavelengthSpectra the wavelength spectra written, as mzPeakJ read them from the source
     */
    record Output(String name, Path path, Path source, Kind kind, List<Spectrum> spectra,
                  List<Chromatogram> chromatograms, List<WavelengthSpectrum> wavelengthSpectra) {

        /** Whether the signal is Numpress-encoded, hence lossy. */
        boolean numpress() {
            return kind == Kind.NUMPRESS_SLOF || kind == Kind.NUMPRESS_PIC;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private InteropOutputs() {
    }

    /** Writes every split-layout output into {@code dir}, which must exist, in the order of the class comment. */
    static List<Output> write(Path dir) {
        List<Output> outputs = new ArrayList<>();
        for (Path source : SOURCES) {
            Source s = ForwardBackwardCorpusTest.readSource(source);
            Output archive = output(dir, source, Kind.ARCHIVE, s);
            MzPeakWriter.writeArchive(archive.path(), s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                    s.fileMetadata);
            outputs.add(archive);
            Output directory = output(dir, source, Kind.DIRECTORY, s);
            MzPeakWriter.writeDirectory(directory.path(), s.spectra, s.chromatograms, s.wavelengthSpectra,
                    s.scanSettings, s.fileMetadata);
            outputs.add(directory);
        }
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        Output slof = output(dir, SMALL, Kind.NUMPRESS_SLOF, small);
        MzPeakWriter.writeArchiveNumpress(slof.path(), small.spectra, small.chromatograms, small.wavelengthSpectra,
                small.scanSettings, small.fileMetadata);
        outputs.add(slof);
        Output pic = output(dir, SMALL, Kind.NUMPRESS_PIC, small);
        MzPeakWriter.writeArchive(pic.path(), small.spectra, small.chromatograms, small.wavelengthSpectra,
                small.scanSettings, small.fileMetadata, Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
                        .withNumpressIntensity(NumpressIntensityEncoding.PIC));
        outputs.add(pic);
        Source ms2 = new Source();
        small.spectra.stream().filter(sp -> sp.description().msLevel() == 2).forEach(ms2.spectra::add);
        ms2.chromatograms = small.chromatograms;
        ms2.wavelengthSpectra = List.of();
        Output subset = output(dir, SMALL, Kind.MS2_SUBSET, ms2);
        ExtractSpectra.main(new String[] {SMALL.toString(), subset.path().toString(), "--ms-level", "2"});
        outputs.add(subset);
        return outputs;
    }

    /** Writes legacy small.unpacked into {@code dir} as an archive in the packed metadata layout. */
    static Output writePacked(Path dir) {
        Path source = SOURCES.get(0);
        Source s = ForwardBackwardCorpusTest.readSource(source);
        Output packed = output(dir, source, Kind.PACKED_ARCHIVE, s);
        MzPeakWriter.writeArchive(packed.path(), s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, Options.defaults().withMetadataLayout(MetadataLayout.PACKED));
        return packed;
    }

    /** The output of {@code kind} in {@code dir}, which writes the entities of {@code written}. */
    private static Output output(Path dir, Path source, Kind kind, Source written) {
        if (!Files.isDirectory(dir)) {
            throw new UncheckedIOException(new IOException("no output directory " + dir));
        }
        String set = source.startsWith(Fixtures.LEGACY) ? "legacy" : "current";
        String fixture = source.getFileName().toString();
        String name = set + "-" + fixture.substring(0, fixture.length() - ".mzpeak".length()) + "-"
                + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return new Output(name, dir.resolve(name + ".mzpeak"), source, kind, List.copyOf(written.spectra),
                List.copyOf(written.chromatograms), List.copyOf(written.wavelengthSpectra));
    }
}
