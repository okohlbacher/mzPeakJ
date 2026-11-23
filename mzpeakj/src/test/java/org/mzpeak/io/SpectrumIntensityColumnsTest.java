package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.ArrayIndex.Entry;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.Session;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * R5 writer (design D13-20; spec signal-data.md l.136-176): spectra_data and spectra_peaks get one intensity column
 * for each unit of the spectra's intensities, so that a spectrum reads back the unit it was written with. The column
 * of MS:1000131 (else of the first unit) is {@code intensity} and primary, every other one {@code intensity_<unit>}
 * and secondary; in the Numpress encoding the columns are {@code <column>_numpress_slof_bytes} or
 * {@code <column>_numpress_pic_bytes}. A batch write surveys the spectra; a session declares the units when it opens
 * ({@link Options#withSpectrumIntensityUnits}, MS:1000131 by default) and refuses a spectrum in another unit.
 *
 * <p>The data is the two-unit copy of current small.unpacked of {@link SpectrumIntensityUnitTest} (X-29): spectrum 1
 * holds the intensities [25, 100] in percent of base peak (MS:1000132) only, spectrum 7 reads without a unit (its
 * intensities come from columns of both units), every other spectrum reads in MS:1000131.
 */
class SpectrumIntensityColumnsTest {

    private static final String COUNTS = "MS:1000131";
    private static final String PERCENT = "MS:1000132";
    private static final String INTENSITY = "MS:1000515";

    @TempDir
    Path tmp;

    /** The two-unit data written by a batch write: each unit has a column, and every spectrum reads back as given. */
    @ParameterizedTest
    @ValueSource(strings = {"point", "SLOF", "PIC"})
    void aBatchWriteGivesEachIntensityUnitOfTheSpectraAColumn(String encoding) throws IOException {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        assertThat(source.spectra.get(1).intensityUnit()).isEqualTo(PERCENT);
        assertThat(source.spectra.get(7).intensityUnit()).isNull();
        Path out = tmp.resolve("batch.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, options(encoding));

        assertTwoIntensityColumns(out, encoding);
        assertReadsAsGiven(out, source, encoding);
    }

    /** A session opened with the default units, MS:1000131 only, refuses spectrum 1 and goes on with the others. */
    @Test
    void aSessionWithTheDefaultUnitsRefusesThePercentSpectrum() throws IOException {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        Path out = tmp.resolve("session.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(out, source.fileMetadata, source.scanSettings, Set.of(),
                Options.defaults())) {
            session.addSpectrum(source.spectra.get(0));
            assertThatThrownBy(() -> session.addSpectrum(source.spectra.get(1)))
                    .isExactlyInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 1: its intensity unit MS:1000132 is not one of the spectrum intensity units"
                            + " the session was opened with [MS:1000131]; declare it with"
                            + " MzPeakWriter.Options.withSpectrumIntensityUnits");
            session.addSpectrum(withIndex(source.spectra.get(2), 1));
            session.finish();
        }
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(2);
            assertThat(reader.getSpectrumAt(1).description().id()).isEqualTo(source.spectra.get(2).description().id());
        }
        assertThat(intensityEntries(out)).allSatisfy(entries -> assertThat(entries)
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity", COUNTS, "primary")));
    }

    /** A session opened with both units writes the two-unit data as the batch write does. */
    @ParameterizedTest
    @ValueSource(strings = {"point", "SLOF", "PIC"})
    void aSessionOpenedWithBothUnitsWritesThePercentSpectrum(String encoding) throws IOException {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        Path out = tmp.resolve("session.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(out, source.fileMetadata, source.scanSettings, Set.of(),
                options(encoding).withSpectrumIntensityUnits(COUNTS, PERCENT))) {
            for (Spectrum s : source.spectra) {
                session.addSpectrum(s);
            }
            source.chromatograms.forEach(session::addChromatogram);
            session.finish();
        }

        assertTwoIntensityColumns(out, encoding);
        assertReadsAsGiven(out, source, encoding);
    }

    /**
     * The column of MS:1000131 is primary even when another unit comes first; without MS:1000131 the first unit's
     * column is {@code intensity} and primary. A session declares its columns whether a spectrum uses them or not, and
     * refuses a spectrum without a unit, which is written in MS:1000131, when MS:1000131 is not declared.
     */
    @Test
    void theColumnOfCountsElseOfTheFirstUnitIsThePrimaryIntensity() throws IOException {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        Spectrum percent = source.spectra.get(1);

        Path percentOnly = tmp.resolve("percent-only.mzpeak");
        MzPeakWriter.writeDirectory(percentOnly, List.of(withIndex(percent, 0)));
        assertThat(intensityEntries(percentOnly)).singleElement().satisfies(entries -> assertThat(entries)
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity", PERCENT, "primary")));
        try (MzPeakReader reader = MzPeakReader.open(percentOnly)) {
            assertThat(reader.getSpectrumAt(0).intensityUnit()).isEqualTo(PERCENT);
            assertThat(reader.getSpectrumAt(0).intensity()).containsExactly(25.0, 100.0);
        }

        Path percentFirst = tmp.resolve("percent-first.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(percentFirst, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults().withSpectrumIntensityUnits(PERCENT, "MS:1000814", COUNTS, PERCENT))) {
            session.addSpectrum(withIndex(percent, 0));
            session.finish();
        }
        assertThat(intensityEntries(percentFirst)).singleElement().satisfies(entries -> assertThat(entries)
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity", COUNTS, "primary"),
                        tuple("intensity_MS_1000132", PERCENT, "secondary"),
                        tuple("intensity_MS_1000814", "MS:1000814", "secondary")));

        try (Session session = MzPeakWriter.openDirectory(tmp.resolve("refused.mzpeak"), FileMetadata.EMPTY,
                List.of(), Set.of(), Options.defaults().withSpectrumIntensityUnits(PERCENT))) {
            assertThatThrownBy(() -> session.addSpectrum(source.spectra.get(0)))
                    .isExactlyInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: its intensity unit MS:1000131 is not one of the spectrum"
                            + " intensity units the session was opened with [MS:1000132];");
            Spectrum unitless = withUnits(source.spectra.get(0), null, null);
            assertThatThrownBy(() -> session.addSpectrum(unitless)).isExactlyInstanceOf(MzPeakException.class)
                    .hasMessageStartingWith("spectrum 0: its intensity unit MS:1000131 is not one of");
        }
    }

    /**
     * An extra array is named after the intensity columns: its key {@code intensity_MS_1000132}, a non-standard array,
     * gets the column {@code intensity_MS_1000132_2}, and {@code intensity_MS_1000132_numpress_slof_bytes} the
     * column {@code intensity_MS_1000132_numpress_slof_bytes_2}, in a batch write and in a session.
     */
    @Test
    void extraColumnsTakeNoNameOfAnIntensityColumn() throws IOException {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        Spectrum one = source.spectra.get(1);
        String clash = "intensity_MS_1000132";
        String slofClash = "intensity_MS_1000132_numpress_slof_bytes";
        Spectrum withExtra = new Spectrum(one.description(), one.mz(), one.intensity(), one.peaks(),
                Map.of(clash, new double[] {1, 2}), Map.of(), PERCENT, null);
        Spectrum withSlofExtra = new Spectrum(one.description(), one.mz(), one.intensity(), one.peaks(),
                Map.of(slofClash, new double[] {3, 4}), Map.of(), PERCENT, null);

        WritePlan plan = WritePlanner.plan(List.of(source.spectra.get(0), withExtra), List.of(), List.of(),
                List.of(), FileMetadata.EMPTY, Options.defaults());
        assertThat(plan.spectrumIntensities()).extracting(WritePlan.IntensityColumn::column)
                .containsExactly("intensity", clash);
        assertThat(plan.spectrumExtras()).extracting(WritePlan.ExtraColumn::column).containsExactly(clash + "_2");

        Path out = tmp.resolve("clash.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(out, FileMetadata.EMPTY, List.of(),
                new LinkedHashSet<>(List.of(clash, slofClash)),
                Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
                        .withSpectrumIntensityUnits(COUNTS, PERCENT))) {
            session.addSpectrum(withIndex(withExtra, 0));
            session.addSpectrum(withIndex(withSlofExtra, 1));
            session.finish();
        }
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.getSpectrumAt(0).arrays().get(clash)).containsExactly(1, 2);
            assertThat(reader.getSpectrumAt(1).arrays().get(slofClash)).containsExactly(3, 4);
            assertThat(reader.getSpectrumAt(1).intensityUnit()).isEqualTo(PERCENT);
        }
        assertThat(BufferPriorityTest.indexes(out).get(0).entries()).extracting(Entry::field)
                .contains("intensity_numpress_slof_bytes", slofClash, clash + "_2", slofClash + "_2");
    }

    /**
     * A CENTROID spectrum whose arrays are its peak list is a peak copy, written to spectra_peaks only, when both have
     * one unit; with different units it writes its arrays to spectra_data and its peak list to spectra_peaks, and
     * each reads back with its unit.
     */
    @Test
    void aPeakCopyKeepsOneUnitAndArraysOfAnotherUnitAreWrittenApart() {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        Spectrum centroid = source.spectra.stream()
                .filter(s -> s.description().signalContinuity() == SignalContinuity.CENTROID && s.hasPeaks())
                .findFirst().orElseThrow();
        assertThat(WritePlanner.isPeakCopy(centroid)).isTrue();
        Spectrum bothPercent = withIndex(withUnits(centroid, PERCENT, PERCENT), 0);
        Spectrum percentArrays = withIndex(withUnits(centroid, PERCENT, COUNTS), 1);
        Spectrum nullAndCounts = withIndex(withUnits(centroid, null, COUNTS), 2);
        assertThat(WritePlanner.isPeakCopy(bothPercent)).isTrue();
        assertThat(WritePlanner.isPeakCopy(percentArrays)).isFalse();
        assertThat(WritePlanner.isPeakCopy(nullAndCounts)).as("null is MS:1000131").isTrue();
        assertThat(WritePlanner.isPeakCopy(withUnits(centroid, COUNTS, null))).isTrue();

        Path out = tmp.resolve("centroid.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(bothPercent, percentArrays, nullAndCounts));
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            Spectrum zero = reader.getSpectrumAt(0);
            assertThat(zero.intensityUnit()).isEqualTo(PERCENT);
            assertThat(zero.peakIntensityUnit()).isEqualTo(PERCENT);
            assertThat(zero.intensity()).containsExactly(centroid.intensity());
            Spectrum one = reader.getSpectrumAt(1);
            assertThat(one.intensityUnit()).isEqualTo(PERCENT);
            assertThat(one.peakIntensityUnit()).isEqualTo(COUNTS);
            assertThat(one.intensity()).containsExactly(centroid.intensity());
            assertThat(one.peaks()).isEqualTo(centroid.peaks());
            Spectrum two = reader.getSpectrumAt(2);
            assertThat(two.intensityUnit()).isEqualTo(COUNTS);
            assertThat(two.peakIntensityUnit()).isEqualTo(COUNTS);
        }
        assertThat(intensityEntries(out)).hasSize(2).allSatisfy(entries -> assertThat(entries)
                .extracting(Entry::field, Entry::unit).containsExactly(tuple("intensity", COUNTS),
                        tuple("intensity_MS_1000132", PERCENT)));
    }

    /**
     * W12: a spectrum's intensity and peak intensity units are CURIEs that metadata.cv_list must declare, as is each
     * unit a session is opened with; a unit without the CURIE shape is written as given.
     */
    @Test
    void anIntensityUnitWithAnUndeclaredCvPrefixIsRefused() throws IOException {
        ForwardBackwardCorpusTest.Source source = twoUnits();
        Spectrum zero = source.spectra.get(0);
        Spectrum two = source.spectra.get(2);
        String undeclared = "XYZ:0000001";

        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("a.mzpeak"),
                List.of(withUnits(zero, undeclared, null))))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("spectrum 0: intensity unit uses XYZ:0000001, whose CV prefix XYZ");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(tmp.resolve("b.mzpeak"),
                List.of(withIndex(withUnits(two, null, undeclared), 0))))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("spectrum 0: peak intensity unit uses XYZ:0000001, whose CV prefix XYZ");
        assertThatThrownBy(() -> MzPeakWriter.openDirectory(tmp.resolve("c.mzpeak"), FileMetadata.EMPTY, List.of(),
                Set.of(), Options.defaults().withSpectrumIntensityUnits(COUNTS, undeclared)))
                .isExactlyInstanceOf(MzPeakException.class)
                .hasMessageStartingWith("spectrumIntensityUnits: spectrum intensity unit uses XYZ:0000001, whose CV"
                        + " prefix XYZ");
        assertThat(tmp.resolve("c.mzpeak")).doesNotExist();

        Path plain = tmp.resolve("plain.mzpeak");
        try (Session session = MzPeakWriter.openDirectory(plain, FileMetadata.EMPTY, List.of(), Set.of(),
                Options.defaults().withSpectrumIntensityUnits("percent"))) {
            session.addSpectrum(withUnits(zero, "percent", null));
            session.finish();
        }
        try (MzPeakReader reader = MzPeakReader.open(plain)) {
            assertThat(reader.getSpectrumAt(0).intensityUnit()).isEqualTo("percent");
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The two-unit copy of current small.unpacked (X-29), read, its spectra and chromatograms without references, so
     * that the tests can write them without file metadata (W12).
     */
    private ForwardBackwardCorpusTest.Source twoUnits() {
        try {
            Path copy = MemberRewrite.copy(Fixtures.current("small.unpacked.mzpeak"), tmp);
            SpectrumIntensityUnitTest.rewritePoint(copy.resolve("spectra_data.parquet"));
            return ForwardBackwardCorpusTest.readSourceWithoutReferences(copy);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Options options(String encoding) {
        return switch (encoding) {
            case "point" -> Options.defaults();
            case "SLOF" -> Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS);
            default -> Options.defaults().withSignalEncoding(SignalEncoding.NUMPRESS)
                    .withNumpressIntensity(NumpressIntensityEncoding.PIC);
        };
    }

    /**
     * spectra_data and spectra_peaks of {@code out} have the intensity column of MS:1000131, primary, and that of
     * MS:1000132, secondary, in the point layout or as the Numpress transform columns of {@code encoding}.
     */
    private static void assertTwoIntensityColumns(Path out, String encoding) {
        String suffix = switch (encoding) {
            case "point" -> "";
            case "SLOF" -> "_numpress_slof_bytes";
            default -> "_numpress_pic_bytes";
        };
        assertThat(intensityEntries(out)).hasSize(2).allSatisfy(entries -> assertThat(entries)
                .extracting(Entry::field, Entry::unit, Entry::bufferPriority)
                .containsExactly(tuple("intensity" + suffix, COUNTS, "primary"),
                        tuple("intensity_MS_1000132" + suffix, PERCENT, "secondary")));
    }

    /**
     * Every spectrum of {@code out} reads with the m/z, intensities and peaks of the one of {@code source} and its
     * intensity unit, MS:1000131 where the source has none; Numpress values are compared within the codecs'
     * precision.
     */
    private static void assertReadsAsGiven(Path out, ForwardBackwardCorpusTest.Source source, String encoding) {
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.size()).isEqualTo(source.spectra.size());
            for (int i = 0; i < reader.size(); i++) {
                Spectrum given = source.spectra.get(i);
                Spectrum read = reader.getSpectrumAt(i);
                String at = "spectrum " + i;
                assertThat(read.intensityUnit()).as(at)
                        .isEqualTo(given.intensityUnit() == null ? COUNTS : given.intensityUnit());
                assertThat(read.peakIntensityUnit()).as(at).isEqualTo(given.peakIntensityUnit());
                assertThat(read.pointCount()).as(at).isEqualTo(given.pointCount());
                if (encoding.equals("point")) {
                    assertThat(read.mz()).as(at).containsExactly(given.mz());
                    assertThat(read.intensity()).as(at).containsExactly(given.intensity());
                    assertThat(read.peaks()).as(at).isEqualTo(given.peaks());
                } else {
                    for (int k = 0; k < given.pointCount(); k++) {
                        assertThat(read.mz()[k]).as("%s m/z %d", at, k).isCloseTo(given.mz()[k], within(1e-5));
                        double expected = encoding.equals("PIC") ? Math.floor(given.intensity()[k] + 0.5)
                                : given.intensity()[k];
                        assertThat(read.intensity()[k]).as("%s intensity %d", at, k)
                                .isCloseTo(expected, within(1e-3 * (expected + 1)));
                    }
                }
            }
            Spectrum one = reader.getSpectrumAt(1);
            assertThat(one.intensityUnit()).isEqualTo(PERCENT);
            assertThat(one.intensity()[0]).isCloseTo(25.0, within(0.05));
            assertThat(one.intensity()[1]).isCloseTo(100.0, within(0.2));
            assertThat(reader.getSpectrumAt(7).intensityUnit()).as("spectrum 7, written in MS:1000131")
                    .isEqualTo(COUNTS);
        }
    }

    /** The intensity entries (array type MS:1000515) of the array index of each spectrum signal member. */
    private static List<List<Entry>> intensityEntries(Path out) {
        List<List<Entry>> all = new ArrayList<>();
        for (ArrayIndex index : BufferPriorityTest.indexes(out)) {
            if (index.entity() == SignalEntity.SPECTRUM) {
                all.add(index.entries().stream().filter(e -> INTENSITY.equals(e.arrayType())).toList());
            }
        }
        return all;
    }

    /** {@code s} written as {@code index}, without precursors, which could name a spectrum not written. */
    private static Spectrum withIndex(Spectrum s, long index) {
        SpectrumDescription d = s.description();
        return new Spectrum(new SpectrumDescription(index, d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), List.of(),
                d.parameters()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(), s.intensityUnit(),
                s.peakIntensityUnit());
    }

    /** {@code s} with the intensity units {@code intensityUnit} and {@code peakIntensityUnit}. */
    private static Spectrum withUnits(Spectrum s, String intensityUnit, String peakIntensityUnit) {
        return new Spectrum(s.description(), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                intensityUnit, peakIntensityUnit);
    }
}
