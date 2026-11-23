package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.io.LocalInputFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W10 (design D13-14; spec conformance.md l.36, a page index for every column): no NaN is written to a signal or
 * metadata column, since parquet-java 1.18.2 writes no column index for a page that holds a NaN. A NaN extra cell and a
 * NaN optional metadata double are written as null; a NaN m/z, time, wavelength or intensity is refused before
 * anything is written, in every encoding.
 *
 * <p>Oracle: the probe of the audit, built on current small.unpacked: spectrum 0 holds the first three points of
 * small's spectrum 0, spectrum 1 those of its spectrum 1 with a signal-to-noise array {@code [1, NaN, 3]}; and the
 * writes of current small.unpacked, has_uv and diaPASEF.ref.
 */
class NoNaNInWrittenColumnsTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String SIGNAL_TO_NOISE = "MS:1000517";
    private static final Options POINT = Options.defaults();
    private static final Options SLOF = Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS);
    private static final Options PIC = SLOF.withNumpressIntensity(NumpressIntensityEncoding.PIC);
    private static final String NAN_MESSAGE = "is NaN; mzPeak writes no NaN m/z, time, wavelength or intensity";

    static Stream<Arguments> encodings() {
        return Stream.of(Arguments.of("point", POINT), Arguments.of("SLOF", SLOF), Arguments.of("PIC", PIC));
    }

    // ---- the probe: a NaN extra cell -------------------------------------------------------------------------------

    /**
     * The probe's spectrum 1 extra {@code [1, NaN, 3]} is stored as {@code [1, null, 3]}, every column of every member
     * has a column index, and the extra reads back as {@code [1, NaN, 3]}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void aNaNExtraCellIsWrittenAsNullAndEveryColumnKeepsItsColumnIndex(String name, Options options,
                                                                       @TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("probe.mzpeak");
        MzPeakWriter.writeDirectory(out, probe(new double[] {1, Double.NaN, 3}), List.of(), List.of(), List.of(),
                FileMetadata.EMPTY, options);

        assertEveryColumnHasAColumnIndex(out);
        assertThat(storedSignalToNoise(out.resolve("spectra_data.parquet"), options == POINT))
                .containsExactly(1.0, null, 3.0);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrumAt(1).arrays().get(SIGNAL_TO_NOISE)).containsExactly(1, Double.NaN, 3);
            assertThat(reader.getSpectrumAt(0).arrays()).isEmpty();
        }
    }

    // ---- NaN axis and intensity values ----------------------------------------------------------------------------

    /** The probe's spectrum 0 with intensities {@code [NaN, ...]}: refused before the target exists. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("encodings")
    void aNaNIntensityIsRefusedBeforeAnyFileExists(String name, Options options, @TempDir Path tmp) {
        List<Spectrum> spectra = new ArrayList<>(probe(new double[] {1, 2, 3}));
        Spectrum s0 = spectra.get(0);
        double[] intensity = s0.intensity().clone();
        intensity[0] = Double.NaN;
        spectra.set(0, new Spectrum(s0.description(), s0.mz(), intensity, List.of()));

        for (boolean archive : new boolean[] {false, true}) {
            Path out = tmp.resolve("out-" + archive + ".mzpeak");
            assertThatThrownBy(() -> write(out, archive, spectra, List.of(), List.of(), options))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: the intensity at position 0 " + NAN_MESSAGE);
            assertThat(out).doesNotExist();
            assertThat(tmp.toFile().list()).as("nothing written next to the target").isEmpty();
        }
    }

    static Stream<Arguments> nanValues() {
        double[] mz = {100, 200, 300};
        double[] values = {1, 2, 3};
        double[] nanAt1 = {1, Double.NaN, 3};
        double[] mzNaN = {100, Double.NaN, 300};
        return Stream.of(
                Arguments.of("m/z", List.of(profile(0, mzNaN, values)), List.of(), List.of(),
                        "spectrum 0: the m/z at position 1 "),
                Arguments.of("peak intensity", List.of(new Spectrum(description(0, SignalContinuity.CENTROID),
                                new double[0], new double[0], List.of(new CentroidPeak(100, 1, 0),
                                new CentroidPeak(200, Double.NaN, 1)))), List.of(), List.of(),
                        "spectrum 0: the intensity of the peak at position 1 (m/z 200.0) "),
                Arguments.of("chromatogram time", List.of(), List.of(new Chromatogram(0, "TIC", "MS:1000235", mzNaN,
                        values)), List.of(), "chromatogram 0: the time at position 1 "),
                Arguments.of("chromatogram intensity", List.of(), List.of(new Chromatogram(0, "TIC", "MS:1000235", mz,
                        nanAt1)), List.of(), "chromatogram 0: the intensity at position 1 "),
                Arguments.of("wavelength", List.of(), List.of(), List.of(new WavelengthSpectrum(0, "w0", 1.0, null,
                        mzNaN, values)), "wavelength spectrum 0: the wavelength at position 1 "),
                Arguments.of("wavelength intensity", List.of(), List.of(), List.of(new WavelengthSpectrum(0, "w0",
                        1.0, null, mz, nanAt1)), "wavelength spectrum 0: the intensity at position 1 "));
    }

    /**
     * A NaN in each axis and intensity, of spectra (arrays and peak list), chromatograms and wavelength spectra: every
     * encoding refuses it, naming the entity and the position, before the target exists.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nanValues")
    void everyNaNAxisOrIntensityIsRefused(String name, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                                          List<WavelengthSpectrum> wavelengthSpectra, String message,
                                          @TempDir Path tmp) {
        for (Options options : List.of(POINT, SLOF, PIC)) {
            Path out = tmp.resolve("out.mzpeak");
            assertThatThrownBy(() -> write(out, true, spectra, chromatograms, wavelengthSpectra, options))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith(message + NAN_MESSAGE);
            assertThat(out).doesNotExist();
        }
    }

    /**
     * A session refuses a spectrum with a NaN intensity when it is added, writes nothing of it and goes on; a NaN
     * chromatogram time fails the finish, which leaves no archive.
     */
    @Test
    void aSessionRefusesANaNSpectrumAndANaNChromatogram(@TempDir Path tmp) {
        List<Spectrum> probe = probe(new double[] {1, 2, 3});
        Spectrum s0 = probe.get(0);
        Path out = tmp.resolve("session.mzpeak");
        try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(out, FileMetadata.EMPTY, List.of(),
                Set.of(SIGNAL_TO_NOISE), SLOF)) {
            assertThatThrownBy(() -> session.addSpectrum(new Spectrum(s0.description(), s0.mz(),
                    new double[] {1, 2, Double.NaN}, List.of())))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: the intensity at position 2 " + NAN_MESSAGE);
            session.addSpectrum(s0);
            session.finish();
        }
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(1);
            assertThat(reader.getSpectrumAt(0).intensity()).hasSize(3);
        }

        Path archive = tmp.resolve("session-archive.mzpeak");
        try (MzPeakWriter.Session session = MzPeakWriter.openArchive(archive, FileMetadata.EMPTY, List.of(),
                Set.of(), POINT)) {
            session.addSpectrum(s0);
            session.addChromatogram(new Chromatogram(0, "TIC", "MS:1000235", new double[] {Double.NaN},
                    new double[] {1}));
            assertThatThrownBy(session::finish).isInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("chromatogram 0: the time at position 0 " + NAN_MESSAGE);
        }
        assertThat(archive).doesNotExist();
    }

    // ---- NaN metadata doubles -------------------------------------------------------------------------------------

    static Stream<MetadataLayout> layouts() {
        return Stream.of(MetadataLayout.SPLIT, MetadataLayout.PACKED);
    }

    /**
     * NaN optional metadata doubles, written in both metadata layouts, are written as null and every column keeps its
     * column index: a scan's injection time and scan window lower limit, a Param's value, an isolation window target,
     * a selected ion's m/z and another's intensity, the total ion current of intensities
     * {@code [+Infinity, -Infinity]}, and a wavelength spectrum's lambda max. Each reads back absent; the ion without
     * m/z, the first, so that the packed layout, which writes the primary ion only, writes it too, reads back with
     * m/z NaN (D13-22).
     */
    @ParameterizedTest
    @MethodSource("layouts")
    void nanMetadataDoublesAreWrittenAsNull(MetadataLayout layout, @TempDir Path tmp) throws IOException {
        ScanEvent scan = new ScanEvent(1.0, Double.NaN, null, null, null, null,
                List.of(new ScanWindow(Double.NaN, 2000)), List.of(new Param("nan param", null, Double.NaN, null)));
        Precursor precursor = new Precursor(null, null, new IsolationWindow(Double.NaN, 1.0, 1.0),
                List.of(new SelectedIon(Double.NaN, 3, 7.0, List.of()),
                        new SelectedIon(500.0, 2, Double.NaN, List.of())), Activation.EMPTY);
        SpectrumDescription d = new SpectrumDescription(0, "nan=0", 2, 1.0, null, SignalContinuity.PROFILE, 0, 0,
                List.of(scan), List.of(precursor), List.of());
        Spectrum s = new Spectrum(d, new double[] {100, 200}, new double[] {Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY}, List.of());
        WavelengthSpectrum w = new WavelengthSpectrum(0, "w0", 1.0, Double.NaN, new double[] {200, 210},
                new double[] {1, 2});
        Path out = tmp.resolve("metadata.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(s), List.of(), List.of(w), List.of(), FileMetadata.EMPTY,
                POINT.withMetadataLayout(layout));

        assertEveryColumnHasAColumnIndex(out);
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            SpectrumDescription back = reader.getSpectrumAt(0).description();
            ScanEvent backScan = back.scans().get(0);
            assertThat(backScan.injectionTime()).isNull();
            assertThat(backScan.scanWindows()).isEmpty();
            assertThat(backScan.parameters()).filteredOn(p -> "nan param".equals(p.name())).singleElement()
                    .satisfies(p -> assertThat(p.value()).isNull());
            Precursor backPrecursor = back.primaryPrecursor();
            assertThat(backPrecursor.isolationWindow().targetMz()).isNull();
            assertThat(backPrecursor.isolationWindow().lowerOffset()).isEqualTo(1.0);
            assertThat(backPrecursor.selectedIons()).hasSize(layout == MetadataLayout.SPLIT ? 2 : 1);
            assertThat(backPrecursor.selectedIons().get(0)).satisfies(ion -> {
                assertThat(ion.mz()).isNaN();
                assertThat(ion.charge()).isEqualTo(3);
                assertThat(ion.intensity()).isEqualTo(7.0);
            });
            if (layout == MetadataLayout.SPLIT) {
                assertThat(backPrecursor.selectedIons().get(1)).satisfies(ion -> {
                    assertThat(ion.mz()).isEqualTo(500.0);
                    assertThat(ion.intensity()).isNull();
                });
            }
            assertThat(back.parameters()).noneMatch(p -> "MS:1000285".equals(p.accession()));
            assertThat(reader.wavelengthSpectra()).singleElement()
                    .satisfies(back2 -> assertThat(back2.lambdaMax()).isNull());
        }
    }

    // ---- the fixtures ---------------------------------------------------------------------------------------------

    static Stream<Arguments> fixtureWrites() {
        List<Arguments> out = new ArrayList<>();
        for (String fixture : List.of("small.unpacked.mzpeak", "has_uv.mzpeak", "diaPASEF.ref.mzpeak")) {
            out.add(Arguments.of(fixture, "point", POINT));
            out.add(Arguments.of(fixture, "SLOF", SLOF));
        }
        out.add(Arguments.of("small.unpacked.mzpeak", "PIC", PIC));
        return out.stream();
    }

    /**
     * Acceptance of W10: every column chunk of every member of the writes of current small.unpacked, has_uv and
     * diaPASEF.ref, in the point layout and as Numpress, has a column index.
     */
    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("fixtureWrites")
    void everyColumnOfEveryFixtureWriteHasAColumnIndex(String fixture, String encoding, Options options,
                                                       @TempDir Path tmp) throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current(fixture));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, options);
        assertEveryColumnHasAColumnIndex(out);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /**
     * The probe: spectrum 0 with the first three points of current small.unpacked's spectrum 0, spectrum 1 with those
     * of its spectrum 1 and the signal-to-noise array {@code signalToNoise}, both written as profile spectra.
     */
    static List<Spectrum> probe(double[] signalToNoise) {
        List<Spectrum> spectra = new ArrayList<>(2);
        try (MzPeakReader reader = MzPeakReader.open(SMALL)) {
            for (int i = 0; i < 2; i++) {
                Spectrum s = reader.getSpectrumAt(i);
                Map<String, double[]> arrays = i == 1 ? Map.of(SIGNAL_TO_NOISE, signalToNoise) : Map.of();
                spectra.add(new Spectrum(description(i, SignalContinuity.PROFILE), Arrays.copyOf(s.mz(), 3),
                        Arrays.copyOf(s.intensity(), 3), List.of(), arrays, Map.of()));
            }
        }
        return spectra;
    }

    private static Spectrum profile(long index, double[] mz, double[] intensity) {
        return new Spectrum(description(index, SignalContinuity.PROFILE), mz, intensity, List.of());
    }

    private static SpectrumDescription description(long index, SignalContinuity continuity) {
        return new SpectrumDescription(index, "probe=" + index, 1, 1.0, null, continuity, 0, 0, List.of(),
                List.of(), List.of());
    }

    private static void write(Path out, boolean archive, List<Spectrum> spectra, List<Chromatogram> chromatograms,
                              List<WavelengthSpectrum> wavelengthSpectra, Options options) {
        if (archive) {
            MzPeakWriter.writeArchive(out, spectra, chromatograms, wavelengthSpectra, List.of(), FileMetadata.EMPTY,
                    options);
        } else {
            MzPeakWriter.writeDirectory(out, spectra, chromatograms, wavelengthSpectra, List.of(),
                    FileMetadata.EMPTY, options);
        }
    }

    /**
     * The stored signal-to-noise cells of spectrum 1 in {@code spectraData}, in row order, a null cell or list
     * element as {@code null}: the {@code point.signal_to_noise} cells of the point layout, else the elements of the
     * {@code chunk.signal_to_noise} lists.
     */
    private static List<Double> storedSignalToNoise(Path spectraData, boolean point) {
        List<Double> out = new ArrayList<>();
        String column = "signal_to_noise";
        ParquetGroups.forEach(spectraData, row -> {
            Group g = row.getGroup(point ? "point" : "chunk", 0);
            if (g.getLong("spectrum_index", 0) != 1) {
                return;
            }
            if (point) {
                out.add(g.getFieldRepetitionCount(column) == 0 ? null : g.getDouble(column, 0));
                return;
            }
            Group list = g.getGroup(column, 0);
            for (int i = 0; i < list.getFieldRepetitionCount("list"); i++) {
                Group element = list.getGroup("list", i);
                out.add(element.getFieldRepetitionCount("item") == 0 ? null : element.getDouble("item", 0));
            }
        });
        return out;
    }

    /** Every column chunk of every Parquet member of the directory {@code out} has a column index. */
    static void assertEveryColumnHasAColumnIndex(Path out) throws IOException {
        int columns = 0;
        List<Path> members;
        try (Stream<Path> list = Files.list(out)) {
            members = list.filter(p -> p.toString().endsWith(".parquet")).sorted().toList();
        }
        for (Path member : members) {
            try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(member),
                    ParquetGroups.readOptions())) {
                for (BlockMetaData block : reader.getFooter().getBlocks()) {
                    for (ColumnChunkMetaData column : block.getColumns()) {
                        assertThat(reader.readColumnIndex(column)).as("%s %s column index", member.getFileName(),
                                column.getPath()).isNotNull();
                        columns++;
                    }
                }
            }
        }
        assertThat(columns).isPositive();
    }
}
