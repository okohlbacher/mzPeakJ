package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.model.Activation;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.DataProcessing;
import org.mzpeak.model.meta.FileMetadata.FileDescription;
import org.mzpeak.model.meta.FileMetadata.InstrumentConfiguration;
import org.mzpeak.model.meta.FileMetadata.MsRun;
import org.mzpeak.model.meta.FileMetadata.ProcessingMethod;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W12 (design D13-6, D13-9, D13-26): the writer refuses, before any file exists, a CURIE whose CV prefix
 * {@code metadata.cv_list} would not declare (conformance.md l.24-25) and an explicit reference that names nothing the
 * file metadata declares (conformance.md §Validation). A batch write refuses it before its target exists; a session
 * refuses file metadata and scan settings when it opens, and an entity when it is added. A unit that is not a CURIE is
 * written as given; a null run default is still filled.
 *
 * <p>Oracle: the HUPO-PSI example datasets current small, has_uv and diaPASEF.ref, each changed where a case says
 * so; the cases are those of the audit's writer probe (ic42, chromdp, ssref, runrefs, icsw, cvFOO).
 */
class WriterDeclarationsTest {

    private static final String UNDECLARED = ", whose CV prefix FOO metadata.cv_list would not declare: neither"
            + " mzPeakJ nor the cv list of the options knows it; conformance.md requires every CV prefix a file uses"
            + " to be declared, so give its CV with id, full name, URI and version to MzPeakWriter.Options.withCvList";
    private static final Param FOO = new Param("odd", "FOO:0000001", null, null);
    /** How to fix an undeclared run default, as the rejection of each says. */
    private static final String RUN_INSTRUMENT_FIX = "; declare the configuration in"
            + " FileMetadata.instrumentConfigurations or set MsRun.defaultInstrumentId to a declared one";
    private static final String RUN_PROCESSING_FIX = "; declare the data processing in"
            + " FileMetadata.dataProcessingMethods or set MsRun.defaultDataProcessingId to a declared one";
    private static final String RUN_SOURCE_FIX = "; declare the source file in FileMetadata.fileDescription or set"
            + " MsRun.defaultSourceFileId to a declared one";

    /** What a write gets: a fixture's entities and metadata, changed by a case. */
    record Write(List<Spectrum> spectra, List<Chromatogram> chromatograms, List<WavelengthSpectrum> wavelengthSpectra,
                 List<ScanSettings> scanSettings, FileMetadata fileMetadata, MzPeakWriter.Options options) {

        static Write of(Path fixture) {
            ForwardBackwardCorpusTest.Source s = ForwardBackwardCorpusTest.readSource(fixture);
            return new Write(s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings, s.fileMetadata,
                    MzPeakWriter.Options.defaults());
        }

        Write withSpectrum(int i, UnaryOperator<Spectrum> change) {
            List<Spectrum> changed = new ArrayList<>(spectra);
            changed.set(i, change.apply(changed.get(i)));
            return new Write(changed, chromatograms, wavelengthSpectra, scanSettings, fileMetadata, options);
        }

        Write withChromatogram(int i, UnaryOperator<Chromatogram> change) {
            List<Chromatogram> changed = new ArrayList<>(chromatograms);
            changed.set(i, change.apply(changed.get(i)));
            return new Write(spectra, changed, wavelengthSpectra, scanSettings, fileMetadata, options);
        }

        Write withWavelengthSpectrum(int i, UnaryOperator<WavelengthSpectrum> change) {
            List<WavelengthSpectrum> changed = new ArrayList<>(wavelengthSpectra);
            changed.set(i, change.apply(changed.get(i)));
            return new Write(spectra, chromatograms, changed, scanSettings, fileMetadata, options);
        }

        Write withScanSettings(List<ScanSettings> settings) {
            return new Write(spectra, chromatograms, wavelengthSpectra, settings, fileMetadata, options);
        }

        Write withFileMetadata(UnaryOperator<FileMetadata> change) {
            return new Write(spectra, chromatograms, wavelengthSpectra, scanSettings, change.apply(fileMetadata),
                    options);
        }

        Write withOptions(MzPeakWriter.Options changed) {
            return new Write(spectra, chromatograms, wavelengthSpectra, scanSettings, fileMetadata, changed);
        }

        /** The keys of every spectrum's arrays and peak arrays, in first-seen order. */
        Set<String> spectrumArrays() {
            Set<String> keys = new LinkedHashSet<>();
            for (Spectrum s : spectra) {
                keys.addAll(s.arrays().keySet());
                keys.addAll(s.peakArrays().keySet());
            }
            return keys;
        }
    }

    /** Where a session refuses a case: when it opens, or when the entity is added. */
    enum At { OPEN, ADD }

    /**
     * One probe case: the write, where a session refuses it, the message of a batch write and that of a session,
     * which names a spectrum array it is opened with as one of its {@code spectrumArrays}.
     */
    record Rejected(String name, Write write, At at, String message, String sessionMessage) {

        Rejected(String name, Write write, At at, String message) {
            this(name, write, at, message, message);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final Map<Path, Write> SOURCES = new LinkedHashMap<>();

    private static Write source(Path fixture) {
        return SOURCES.computeIfAbsent(fixture, Write::of);
    }

    private static Write small() {
        return source(Fixtures.current("small.mzpeak"));
    }

    /** The index of the first spectrum of small with a precursor that has an isolation window and a selected ion. */
    private static int firstMsn() {
        List<Spectrum> spectra = small().spectra();
        for (int i = 0; ; i++) {
            List<Precursor> precursors = spectra.get(i).description().precursors();
            if (!precursors.isEmpty() && precursors.get(0).isolationWindow() != null
                    && !precursors.get(0).selectedIons().isEmpty()) {
                return i;
            }
        }
    }

    static Stream<Rejected> rejected() {
        Write small = small();
        int msn = firstMsn();
        String precursor = "spectrum " + msn + ": precursor 0";
        return Stream.of(
                new Rejected("ic42", small.withSpectrum(3, s -> withScan(s, 42, List.of())), At.ADD,
                        // The message of the design's W12 section, word for word.
                        "spectrum 3: scan 0 instrument_configuration_id 42 names no instrument configuration"
                                + " (declared: 0, 1)"),
                new Rejected("spectrum data processing", small.withSpectrum(3, s -> withDataProcessing(s, "dpX")),
                        At.ADD, "spectrum 3: data_processing_id dpX names no data processing (declared: "
                        + processingIds(small) + ")"),
                new Rejected("chromdp", small.withChromatogram(0, c -> withDataProcessing(c, "dpX")), At.ADD,
                        "chromatogram 0: data_processing_id dpX names no data processing (declared: "
                                + processingIds(small) + ")"),
                new Rejected("ssref", small.withScanSettings(List.of(new ScanSettings("ss", List.of(),
                        List.of("sfX"), List.of()))), At.OPEN,
                        "scan settings 0 (ss): source file reference sfX names no source file (declared: "
                                + sourceFileIds(small) + ")"),
                new Rejected("runrefs instrument", small.withFileMetadata(m -> withRun(m, 7, null, null)), At.OPEN,
                        "file metadata: run: default_instrument_configuration_id 7 names no instrument configuration"
                                + " (declared: 0, 1)" + RUN_INSTRUMENT_FIX),
                new Rejected("runrefs data processing", small.withFileMetadata(m -> withRun(m, null, "dpX", null)),
                        At.OPEN, "file metadata: run: default_data_processing_id dpX names no data processing"
                        + " (declared: " + processingIds(small) + ")" + RUN_PROCESSING_FIX),
                new Rejected("runrefs source file", small.withFileMetadata(m -> withRun(m, null, null, "sfX")),
                        At.OPEN, "file metadata: run: default_source_file_id sfX names no source file (declared: "
                        + sourceFileIds(small) + ")" + RUN_SOURCE_FIX),
                new Rejected("runrefs without file description", small.withFileMetadata(m -> withRun(
                        new FileMetadata(null, m.instrumentConfigurations(), m.dataProcessingMethods(), m.samples(),
                                m.software(), m.run()), null, null, "sfX")), At.OPEN,
                        "file metadata: run: default_source_file_id sfX names no source file (declared: none)"
                                + RUN_SOURCE_FIX),
                new Rejected("icsw", small.withFileMetadata(m -> withConfigurationSoftware(m, "swX")), At.OPEN,
                        "file metadata: instrument configuration 0: software_reference swX names no software"
                                + " (declared: " + softwareIds(small) + ")"),
                new Rejected("processing method software", small.withFileMetadata(m -> withMethodSoftware(m, "swX")),
                        At.OPEN, "file metadata: data processing " + small.fileMetadata().dataProcessingMethods()
                        .get(0).id() + " method " + small.fileMetadata().dataProcessingMethods().get(0).methods()
                        .get(0).order() + ": software_reference swX names no software (declared: "
                        + softwareIds(small) + ")"),
                new Rejected("cvFOO", small.withSpectrum(3, s -> withParam(s, FOO)), At.ADD,
                        "spectrum 3: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("unit FOO", small.withSpectrum(3, s -> withScan(s, 0, List.of(
                        new Param("time", "MS:1000016", 1.0, "FOO:0001")))), At.ADD,
                        "spectrum 3: scan 0: " + new Param("time", "MS:1000016", 1.0, "FOO:0001")
                                + " uses FOO:0001" + UNDECLARED),
                new Rejected("isolation window FOO", small.withSpectrum(msn, s -> withPrecursor(s, 0)), At.ADD,
                        precursor + " isolation window: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("activation FOO", small.withSpectrum(msn, s -> withPrecursor(s, 1)), At.ADD,
                        precursor + " activation: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("selected ion FOO", small.withSpectrum(msn, s -> withPrecursor(s, 2)), At.ADD,
                        precursor + " selected ion 0: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("extra array FOO", small.withSpectrum(3, s -> withArray(s, "FOO:0000002")), At.OPEN,
                        "spectrum 3: array uses FOO:0000002" + UNDECLARED,
                        "spectrumArrays: array uses FOO:0000002" + UNDECLARED),
                new Rejected("chromatogram type FOO", small.withChromatogram(0, c -> withType(c, "FOO:0000003",
                        c.intensityUnit())), At.ADD, "chromatogram 0: type uses FOO:0000003" + UNDECLARED),
                new Rejected("chromatogram intensity unit FOO", small.withChromatogram(0, c -> withType(c,
                        c.typeCurie(), "FOO:0000004")), At.ADD,
                        "chromatogram 0: intensity unit uses FOO:0000004" + UNDECLARED),
                new Rejected("chromatogram param FOO", small.withChromatogram(0, c -> withParam(c, FOO)), At.ADD,
                        "chromatogram 0: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("chromatogram precursor FOO", small.withChromatogram(0, c -> withPrecursor(c)), At.ADD,
                        "chromatogram 0: precursor 0 activation: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("chromatogram array FOO", small.withChromatogram(0, c -> withArray(c, "FOO:0000005")),
                        At.ADD, "chromatogram 0: array uses FOO:0000005" + UNDECLARED),
                new Rejected("wavelength spectrum FOO", source(Fixtures.current("has_uv.mzpeak"))
                        .withWavelengthSpectrum(0, w -> withParam(w, FOO)), At.ADD,
                        "wavelength spectrum 0: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("software param FOO", small.withFileMetadata(m -> withSoftwareParam(m, FOO)), At.OPEN,
                        "file metadata: software " + small.fileMetadata().software().get(0).id() + ": " + FOO
                                + " uses FOO:0000001" + UNDECLARED),
                new Rejected("scan settings target FOO", small.withScanSettings(List.of(new ScanSettings("ss",
                        List.of(), List.of(), List.of(List.of(FOO))))), At.OPEN,
                        "scan settings 0 (ss): target 0: " + FOO + " uses FOO:0000001" + UNDECLARED),
                new Rejected("cv list entry without uri", small.withSpectrum(3, s -> withParam(s, FOO))
                        .withOptions(MzPeakWriter.Options.defaults().withCvList(List.of(
                                new CvEntry("FOO", "Foo", null, "1")))), At.ADD,
                        "spectrum 3: " + FOO + " uses FOO:0000001, whose CV prefix FOO metadata.cv_list would not"
                                + " declare: its cv list entry has no uri; conformance.md requires every CV prefix a"
                                + " file uses to be declared, so give its CV with id, full name, URI and version to"
                                + " MzPeakWriter.Options.withCvList"));
    }

    /** A batch write to a directory refuses each case before its target exists. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void aBatchDirectoryWriteRefusesBeforeAnyFileExists(Rejected c, @TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out.mzpeak");
        Write w = c.write();

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, w.spectra(), w.chromatograms(),
                w.wavelengthSpectra(), w.scanSettings(), w.fileMetadata(), w.options()))
                .isInstanceOf(MzPeakException.class).hasMessage(c.message());
        assertThat(entries(tmp)).isEmpty();
    }

    /** A batch write to an archive refuses each case before its target or a temp file exists. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void aBatchArchiveWriteRefusesBeforeAnyFileExists(Rejected c, @TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out.mzpeak");
        Write w = c.write();

        assertThatThrownBy(() -> MzPeakWriter.writeArchive(out, w.spectra(), w.chromatograms(),
                w.wavelengthSpectra(), w.scanSettings(), w.fileMetadata(), w.options()))
                .isInstanceOf(MzPeakException.class).hasMessage(c.message());
        assertThat(entries(tmp)).isEmpty();
    }

    /**
     * A session refuses a case of the file metadata, the scan settings or the spectrum arrays when it opens, before
     * its target exists, and a case of an entity when it is added; closing it then leaves no target.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rejected")
    void aSessionRefusesAtOpenOrAtAdd(Rejected c, @TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out.mzpeak");
        Write w = c.write();
        List<Runnable> added = new ArrayList<>();

        if (c.at() == At.OPEN) {
            assertThatThrownBy(() -> MzPeakWriter.openDirectory(out, w.fileMetadata(), w.scanSettings(),
                    w.spectrumArrays(), w.options())).isInstanceOf(MzPeakException.class)
                    .hasMessage(c.sessionMessage());
        } else {
            try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(out, w.fileMetadata(), w.scanSettings(),
                    w.spectrumArrays(), w.options())) {
                assertThat(out).isDirectory();
                w.spectra().forEach(s -> added.add(() -> session.addSpectrum(s)));
                w.chromatograms().forEach(ch -> added.add(() -> session.addChromatogram(ch)));
                w.wavelengthSpectra().forEach(ws -> added.add(() -> session.addWavelengthSpectrum(ws)));
                assertThatThrownBy(() -> added.forEach(Runnable::run)).isInstanceOf(MzPeakException.class)
                        .hasMessage(c.sessionMessage());
            }
        }
        assertThat(entries(tmp)).isEmpty();
    }

    /**
     * D13-26: a param unit that is not a CURIE, such as {@code seconds} or {@code counts: per second} (a colon but
     * not the shape {@code PREFIX:local}), is not prefix-checked and is written as given by the packed layout; so
     * is a CV prefix the caller's cv list declares with all four fields, which metadata.cv_list then declares. A file
     * description without contents (a null list) has no param to check. The split layout refuses such a unit
     * (D13-28, X-40, {@link SplitParamCurieTest}).
     */
    @Test
    void aUnitThatIsNoCurieAndACallerDeclaredPrefixAreWrittenAsGiven(@TempDir Path tmp) throws IOException {
        Param seconds = new Param("time", "MS:1000016", 1.5, "seconds");
        Param rate = new Param("rate", "MS:1000285", 2.5, "counts: per second");
        CvEntry foo = new CvEntry("FOO", "Foo ontology", "https://example.org/foo/1.0/foo.obo", "1.0");
        Write w = small().withSpectrum(3, s -> withParam(withParam(withParam(s, seconds), rate), FOO))
                .withFileMetadata(m -> new FileMetadata(new FileDescription(null,
                        m.fileDescription().sourceFiles()), m.instrumentConfigurations(), m.dataProcessingMethods(),
                        m.samples(), m.software(), m.run()))
                .withOptions(MzPeakWriter.Options.defaults().withCvList(List.of(foo))
                        .withMetadataLayout(MzPeakWriter.MetadataLayout.PACKED));
        Path out = tmp.resolve("out.mzpeak");

        MzPeakWriter.writeDirectory(out, w.spectra(), w.chromatograms(), w.wavelengthSpectra(), w.scanSettings(),
                w.fileMetadata(), w.options());

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrumAt(3).description().parameters()).contains(seconds, rate, FOO);
        }
        assertThat(Files.readString(out.resolve(ManifestWriter.INDEX))).contains("\"id\" : \"FOO\"")
                .doesNotContain("\"id\" : \"seconds\"").doesNotContain("\"id\" : \"counts\"");
    }

    /**
     * W12: without file metadata ({@link FileMetadata#EMPTY} or {@code null}) the writer declares no instrument
     * configuration, data processing or source file, so every reference names nothing and is refused: by the
     * overloads without file metadata before their target exists, and by a session when the entity is added, which
     * leaves no target. Small without any reference is written; a CV prefix is checked all the same.
     */
    @Test
    void withoutFileMetadataEveryReferenceIsRefused(@TempDir Path tmp) throws IOException {
        Write small = small();
        Write bare = new Write(Fixtures.withoutReferences(small.spectra()),
                Fixtures.chromatogramsWithoutReferences(small.chromatograms()), small.wavelengthSpectra(),
                small.scanSettings(), small.fileMetadata(), small.options());
        Write ic42 = bare.withSpectrum(3, s -> withScan(s, 42, List.of()));
        String ic42Message = "spectrum 3: scan 0 instrument_configuration_id 42 names no instrument configuration"
                + " (declared: none)";
        Path out = tmp.resolve("out.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, ic42.spectra(), ic42.chromatograms()))
                .isInstanceOf(MzPeakException.class).hasMessage(ic42Message);
        assertThatThrownBy(() -> MzPeakWriter.writeArchive(out, ic42.spectra(), ic42.chromatograms()))
                .isInstanceOf(MzPeakException.class).hasMessage(ic42Message);
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, ic42.spectra(), ic42.chromatograms(), List.of(),
                List.of(), null, MzPeakWriter.Options.defaults()))
                .isInstanceOf(MzPeakException.class).hasMessage(ic42Message);
        assertThat(entries(tmp)).isEmpty();

        Write dpX = bare.withSpectrum(3, s -> withDataProcessing(s, "dpX"));
        try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(out, FileMetadata.EMPTY, List.of(),
                dpX.spectrumArrays(), dpX.options())) {
            assertThatThrownBy(() -> dpX.spectra().forEach(session::addSpectrum))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 3: data_processing_id dpX names no data processing (declared: none)");
        }
        assertThat(entries(tmp)).isEmpty();

        MzPeakWriter.writeDirectory(out, bare.spectra(), bare.chromatograms());
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(bare.spectra().size());
        }
        Write foo = bare.withSpectrum(3, s -> withParam(s, FOO));
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("foo.mzpeak"), foo.spectra(),
                foo.chromatograms())).isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum 3: " + FOO + " uses FOO:0000001" + UNDECLARED);
    }

    /**
     * D13-9: diaPASEF.ref's run names instrument configuration 1, which its file metadata does not declare, so
     * writing what the reader returns is refused; {@link Fixtures#withDeclaredDefaults} adds the configuration, and
     * the write then names it as the reader read it.
     */
    @Test
    void diaPasefIsWrittenWithTheConfigurationItsRunNames(@TempDir Path tmp) {
        Write w = source(Fixtures.current("diaPASEF.ref.mzpeak"));
        FileMetadata read = RunReferenceConformanceTest.fileMetadata(Fixtures.current("diaPASEF.ref.mzpeak"));
        assertThat(read.instrumentConfigurations()).extracting(InstrumentConfiguration::id).containsExactly(0);
        Path out = tmp.resolve("out.mzpeak");

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(out, w.spectra(), w.chromatograms(),
                w.wavelengthSpectra(), w.scanSettings(), read, w.options()))
                .isInstanceOf(MzPeakException.class).hasMessage("file metadata: run:"
                        + " default_instrument_configuration_id 1 names no instrument configuration (declared: 0)"
                        + RUN_INSTRUMENT_FIX);
        assertThat(out).doesNotExist();

        FileMetadata declared = Fixtures.withDeclaredDefaults(read);
        assertThat(declared).isEqualTo(w.fileMetadata());
        assertThat(Fixtures.withDeclaredDefaults(declared)).isSameAs(declared);
        MzPeakWriter.writeDirectory(out, w.spectra(), w.chromatograms(), w.wavelengthSpectra(), w.scanSettings(),
                declared, w.options());

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.fileMetadata().run().defaultInstrumentId()).isEqualTo(1);
            assertThat(reader.fileMetadata().instrumentConfigurations()).extracting(InstrumentConfiguration::id)
                    .containsExactly(0, 1);
            assertThat(reader.size()).isEqualTo(w.spectra().size());
        }
    }

    /**
     * A null run default is still filled: with a run naming nothing, the first declared entry of each list; without
     * any entry, a stub.
     */
    @Test
    void nullRunDefaultsAreStillFilled(@TempDir Path tmp) {
        Write w = small().withFileMetadata(m -> withRun(m, null, null, null));
        Path out = tmp.resolve("out.mzpeak");

        MzPeakWriter.writeDirectory(out, w.spectra(), w.chromatograms(), w.wavelengthSpectra(), w.scanSettings(),
                w.fileMetadata(), w.options());

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            MsRun run = reader.fileMetadata().run();
            FileMetadata given = w.fileMetadata();
            assertThat(run.defaultInstrumentId()).isEqualTo(given.instrumentConfigurations().get(0).id());
            assertThat(run.defaultDataProcessingId()).isEqualTo(given.dataProcessingMethods().get(0).id());
            assertThat(run.defaultSourceFileId()).isEqualTo(given.fileDescription().sourceFiles().get(0).id());
        }
    }

    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.toList();
        }
    }

    private static String processingIds(Write w) {
        return String.join(", ", w.fileMetadata().dataProcessingMethods().stream().map(DataProcessing::id).toList());
    }

    private static String sourceFileIds(Write w) {
        return String.join(", ", w.fileMetadata().fileDescription().sourceFiles().stream()
                .map(FileMetadata.SourceFile::id).toList());
    }

    private static String softwareIds(Write w) {
        return String.join(", ", w.fileMetadata().software().stream().map(Software::id).toList());
    }

    private static Spectrum withDescription(Spectrum s, SpectrumDescription d) {
        return new Spectrum(d, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static SpectrumDescription changed(SpectrumDescription d, List<ScanEvent> scans,
                                               List<Precursor> precursors, List<Param> params, String processing) {
        return new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans, precursors, params,
                processing);
    }

    /** {@code s} with its first scan naming the instrument configuration {@code ic} and carrying {@code params}. */
    private static Spectrum withScan(Spectrum s, int ic, List<Param> params) {
        SpectrumDescription d = s.description();
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        ScanEvent first = scans.get(0);
        List<Param> scanParams = new ArrayList<>(first.parameters());
        scanParams.addAll(params);
        scans.set(0, new ScanEvent(first.startTime(), first.injectionTime(), first.filterString(),
                first.presetScanConfiguration(), ic, first.spectrumReference(), first.scanWindows(), scanParams));
        return withDescription(s, changed(d, scans, d.precursors(), d.parameters(), d.dataProcessingRef()));
    }

    private static Spectrum withDataProcessing(Spectrum s, String processing) {
        SpectrumDescription d = s.description();
        return withDescription(s, changed(d, d.scans(), d.precursors(), d.parameters(), processing));
    }

    private static Spectrum withParam(Spectrum s, Param param) {
        SpectrumDescription d = s.description();
        List<Param> params = new ArrayList<>(d.parameters());
        params.add(param);
        return withDescription(s, changed(d, d.scans(), d.precursors(), params, d.dataProcessingRef()));
    }

    /**
     * {@code s} with {@link #FOO} added to its first precursor's isolation window ({@code where} 0), activation (1)
     * or first selected ion (2).
     */
    private static Spectrum withPrecursor(Spectrum s, int where) {
        SpectrumDescription d = s.description();
        List<Precursor> precursors = new ArrayList<>(d.precursors());
        Precursor p = precursors.get(0);
        IsolationWindow w = p.isolationWindow();
        IsolationWindow window = where == 0 ? new IsolationWindow(w.targetMz(), w.lowerOffset(), w.upperOffset(),
                plus(w.parameters(), FOO)) : w;
        Activation activation = where == 1 ? new Activation(plus(p.activation().parameters(), FOO)) : p.activation();
        List<SelectedIon> ions = new ArrayList<>(p.selectedIons());
        if (where == 2) {
            SelectedIon ion = ions.get(0);
            ions.set(0, new SelectedIon(ion.mz(), ion.charge(), ion.intensity(), plus(ion.parameters(), FOO)));
        }
        precursors.set(0, new Precursor(p.precursorIndex(), p.precursorId(), window, ions, activation));
        return withDescription(s, changed(d, d.scans(), precursors, d.parameters(), d.dataProcessingRef()));
    }

    private static Spectrum withArray(Spectrum s, String key) {
        Map<String, double[]> arrays = new LinkedHashMap<>(s.arrays());
        arrays.put(key, new double[s.mz().length]);
        return new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(), arrays, s.peakArrays());
    }

    private static Chromatogram withDataProcessing(Chromatogram c, String processing) {
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), processing, c.parameters(), c.precursors(), c.timeUnit());
    }

    private static Chromatogram withType(Chromatogram c, String type, String intensityUnit) {
        return new Chromatogram(c.index(), c.id(), type, c.time(), c.intensity(), intensityUnit, c.arrays(),
                c.polarity(), c.dataProcessingRef(), c.parameters(), c.precursors(), c.timeUnit());
    }

    private static Chromatogram withParam(Chromatogram c, Param param) {
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), plus(c.parameters(), param), c.precursors(),
                c.timeUnit());
    }

    /** {@code c} with one precursor without isolation window whose activation carries {@link #FOO}. */
    private static Chromatogram withPrecursor(Chromatogram c) {
        Precursor p = new Precursor(null, null, null, List.of(), new Activation(List.of(FOO)));
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), c.parameters(), List.of(p), c.timeUnit());
    }

    private static Chromatogram withArray(Chromatogram c, String key) {
        Map<String, double[]> arrays = new LinkedHashMap<>(c.arrays());
        arrays.put(key, new double[c.time().length]);
        return new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                arrays, c.polarity(), c.dataProcessingRef(), c.parameters(), c.precursors(), c.timeUnit());
    }

    private static WavelengthSpectrum withParam(WavelengthSpectrum w, Param param) {
        return new WavelengthSpectrum(w.index(), w.id(), w.time(), w.lambdaMax(), w.wavelength(), w.intensity(),
                plus(w.parameters(), param));
    }

    private static FileMetadata withRun(FileMetadata m, Integer instrument, String processing, String sourceFile) {
        MsRun r = m.run();
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), m.dataProcessingMethods(),
                m.samples(), m.software(), new MsRun(r.id(), instrument, processing, sourceFile, r.startTime(),
                r.parameters()));
    }

    private static FileMetadata withConfigurationSoftware(FileMetadata m, String software) {
        List<InstrumentConfiguration> configurations = new ArrayList<>(m.instrumentConfigurations());
        InstrumentConfiguration c = configurations.get(0);
        configurations.set(0, new InstrumentConfiguration(c.id(), c.components(), software, c.parameters()));
        return new FileMetadata(m.fileDescription(), configurations, m.dataProcessingMethods(), m.samples(),
                m.software(), m.run());
    }

    private static FileMetadata withMethodSoftware(FileMetadata m, String software) {
        List<DataProcessing> processing = new ArrayList<>(m.dataProcessingMethods());
        DataProcessing dp = processing.get(0);
        List<ProcessingMethod> methods = new ArrayList<>(dp.methods());
        ProcessingMethod pm = methods.get(0);
        methods.set(0, new ProcessingMethod(pm.order(), software, pm.parameters()));
        processing.set(0, new DataProcessing(dp.id(), methods));
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), processing, m.samples(),
                m.software(), m.run());
    }

    private static FileMetadata withSoftwareParam(FileMetadata m, Param param) {
        List<Software> software = new ArrayList<>(m.software());
        Software s = software.get(0);
        software.set(0, new Software(s.id(), s.version(), plus(s.parameters(), param)));
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), m.dataProcessingMethods(),
                m.samples(), software, m.run());
    }

    private static List<Param> plus(List<Param> params, Param param) {
        List<Param> out = new ArrayList<>(params);
        out.add(param);
        return out;
    }
}
