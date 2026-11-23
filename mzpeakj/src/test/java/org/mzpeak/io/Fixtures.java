package org.mzpeak.io;

import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The two vendored HUPO-PSI example sets, in the test resources of the library module {@code mzpeakj}. Their
 * root is the system property {@code mzpeak.test.resources} (the converter module's surefire configuration points
 * it at the library's), else {@code src/test/resources}, relative to the module directory, the working directory of
 * the Maven test run. When that directory has no {@code mzpeak/}, as in a test that an IDE starts in the converter
 * module without the property, the root is {@code ../mzpeakj/src/test/resources}.
 *
 * <ul>
 *   <li>{@link #CURRENT}: the 8 examples of HUPO-PSI/mzPeak 2ee2589 (split metadata facets, array index,
 *       SHA-512 checksums), byte-identical to upstream. The reader reads their spectrum metadata (RD-5),
 *       chromatogram metadata (RD-8) and wavelength-spectrum metadata (RD-9), and decodes the grid-encoded signal
 *       of diaPASEF.grid (RD-20).
 *   <li>{@link #LEGACY}: the 5 examples of HUPO-PSI/mzPeak 29e59b2 (packed metadata layout). They guard the
 *       packed layout that mzPeakJ 0.10.3 and earlier write and the existing tests run on.
 * </ul>
 *
 * Each directory holds an {@code UPSTREAM_COMMIT.txt} naming its commit.
 */
public final class Fixtures {

    /** Root of the library's test resources; see the class comment. */
    private static final Path ROOT = root();

    /** Directory of the current upstream examples (HUPO-PSI/mzPeak 2ee2589). */
    public static final Path CURRENT = ROOT.resolve("mzpeak");

    /** Directory of the pinned packed-layout examples (HUPO-PSI/mzPeak 29e59b2). */
    public static final Path LEGACY = ROOT.resolve("mzpeak-legacy");

    /** The datasets in {@link #CURRENT}: 7 single-file ZIPs and one unpacked directory. */
    public static final List<String> CURRENT_NAMES = List.of(
            "small.mzpeak",
            "small.chunked.mzpeak",
            "small.numpress.mzpeak",
            "small.unpacked.mzpeak",
            "has_uv.mzpeak",
            "diaPASEF.ref.mzpeak",
            "diaPASEF.grid.mzpeak",
            "Example_Processed.img.mzpeak");

    /** The datasets in {@link #LEGACY}: 4 single-file ZIPs and one unpacked directory. */
    public static final List<String> LEGACY_NAMES = List.of(
            "small.mzpeak",
            "small.chunked.mzpeak",
            "small.numpress.mzpeak",
            "small.unpacked.mzpeak",
            "has_uv.mzpeak");

    private Fixtures() {
    }

    private static Path root() {
        Path root = Path.of(System.getProperty("mzpeak.test.resources", "src/test/resources"));
        return Files.isDirectory(root.resolve("mzpeak")) ? root : Path.of("../mzpeakj/src/test/resources");
    }

    /** The current dataset {@code name}, one of {@link #CURRENT_NAMES}. */
    public static Path current(String name) {
        return resolve(CURRENT, CURRENT_NAMES, name);
    }

    /** The legacy dataset {@code name}, one of {@link #LEGACY_NAMES}. */
    public static Path legacy(String name) {
        return resolve(LEGACY, LEGACY_NAMES, name);
    }

    /**
     * {@code meta} with the instrument configuration its run names as default but does not declare, as the run of
     * diaPASEF.ref and diaPASEF.grid names configuration 1 while only 0 is declared: the configuration of that id
     * without components and params, with the first configuration's software, is appended, which is the stub the
     * writer added for it before it rejected such a reference (W12, D13-9). Any other {@code meta} is returned as it
     * is.
     */
    public static FileMetadata withDeclaredDefaults(FileMetadata meta) {
        MsRun run = meta.run();
        if (run == null || run.defaultInstrumentId() == null || meta.instrumentConfigurations().stream()
                .anyMatch(ic -> ic.id() == run.defaultInstrumentId())) {
            return meta;
        }
        List<InstrumentConfiguration> configurations = new ArrayList<>(meta.instrumentConfigurations());
        configurations.add(new InstrumentConfiguration(run.defaultInstrumentId(), List.of(),
                configurations.get(0).softwareReference(), List.of()));
        return new FileMetadata(meta.fileDescription(), configurations, meta.dataProcessingMethods(), meta.samples(),
                meta.software(), run);
    }

    /**
     * {@code spectra}, in order, each without the references the writer checks against the file metadata (W12): the
     * instrument configuration of every scan and the data processing of the spectrum are {@code null}, everything
     * else is kept. A write without file metadata declares nothing such a reference could name, so the spectra a
     * reader returns are written without file metadata this way.
     */
    public static List<Spectrum> withoutReferences(List<Spectrum> spectra) {
        List<Spectrum> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            SpectrumDescription d = s.description();
            List<ScanEvent> scans = new ArrayList<>(d.scans().size());
            for (ScanEvent e : d.scans()) {
                scans.add(new ScanEvent(e.startTime(), e.injectionTime(), e.filterString(),
                        e.presetScanConfiguration(), null, e.spectrumReference(), e.scanWindows(), e.parameters()));
            }
            SpectrumDescription bare = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                    d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans,
                    d.precursors(), d.parameters(), null, d.products());
            out.add(new Spectrum(bare, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                    s.intensityUnit(), s.peakIntensityUnit(), s.auxiliaryArrays()));
        }
        return out;
    }

    /** {@code chromatograms}, in order, each without its data processing reference, for the same reason. */
    public static List<Chromatogram> chromatogramsWithoutReferences(List<Chromatogram> chromatograms) {
        List<Chromatogram> out = new ArrayList<>(chromatograms.size());
        for (Chromatogram c : chromatograms) {
            out.add(new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                    c.arrays(), c.polarity(), null, c.parameters(), c.precursors(), c.timeUnit(), c.products(),
                    c.auxiliaryArrays()));
        }
        return out;
    }

    private static Path resolve(Path dir, List<String> names, String name) {
        if (!names.contains(name)) {
            throw new IllegalArgumentException("no fixture " + name + " in " + dir + "; known: " + names);
        }
        return dir.resolve(name);
    }
}
