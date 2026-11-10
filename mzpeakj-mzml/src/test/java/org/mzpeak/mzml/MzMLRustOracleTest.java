package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * JZ-9: the Rust converter as oracle (mzML design 12.4, 'Against the vendored mzPeak made from the same mzML'). The
 * vendored mzPeak fixtures small.mzpeak, has_uv.mzpeak and diaPASEF.ref.mzpeak were made by the Rust reference
 * converter from the vendored small.mzML, TOFsulfas and diaPASEF.mzML; neither shares code with jmzML, so they check
 * both directions of {@link MzMLConversion} independently of the converter's own reader and writer.
 * <ul>
 *   <li>small.mzML to our mzPeak vs small.mzpeak: ids, MS levels, polarity and continuity equal; retention time,
 *       scan start and injection time, isolation window and selected ion within relative 1e-7 (the fixture stores
 *       float32); intensities exact, m/z exact where stored and within 5e-5 at the null-filled zero flanks, after the
 *       zero-run stripping of upstream {@code find_where_not_zero_run}; summary params not compared (the fixture
 *       recomputes them).
 *   <li>TOFsulfas to our mzPeak vs has_uv.mzpeak: the 212 MS spectra bit-exact in arrays and the fields above (the
 *       injection time, which the mzML does not give, is 0.0 in has_uv and absent in ours); the 520 UV spectra
 *       bit-exact in id, time and both arrays, our params a superset of the source's; has_uv's lambda_max and its UV
 *       params MS:1000505 and MS:1000285, derived from the signal by the Rust writer, are not compared, and ours has
 *       neither (lambdaMax null); the TIC and DAD chromatograms equal, in MS:1000131 and UO:0000269.
 *   <li>diaPASEF.mzML to our mzPeak vs diaPASEF.ref.mzpeak: 9 spectra whose sorted rows agree within m/z 1.8e-12
 *       relative, ion mobility 4.4e-16 (2^-51, two ulp at 1, the bound the measured difference reaches) and intensity
 *       1.0 absolute.
 *   <li>Reverse: small.mzpeak to mzML vs small.mzML, matched by id, with the tolerances of the first case.
 * </ul>
 */
class MzMLRustOracleTest {

    private static final double FLOAT32 = 1e-7;
    private static final double EXACT = 0;
    private static final String ION_MOBILITY = "MS:1003006";

    @TempDir
    Path dir;

    // ---- small.mzML -> mzPeak vs small.mzpeak ---------------------------------------------------------------------

    @Test
    void smallMzMLToMzPeakAgreesWithTheRustConverter() throws IOException {
        Path ours = toMzPeak(MzMLFixtures.SMALL);
        List<Spectrum> written = spectra(ours);
        List<Spectrum> reference = spectra(Fixtures.current("small.mzpeak"));
        assertThat(written).hasSize(48);
        assertThat(ids(written)).isEqualTo(ids(reference));
        for (int i = 0; i < 48; i++) {
            String what = "spectrum " + i;
            describedAlike(what, reference.get(i), written.get(i), FLOAT32);
            strippedAlike(what, written.get(i), reference.get(i));
        }
        assertThat(written).filteredOn(s -> s.description().signalContinuity() == SignalContinuity.PROFILE)
                .hasSize(14);
    }

    // ---- TOFsulfas -> mzPeak vs has_uv.mzpeak ---------------------------------------------------------------------

    @Test
    void tofSulfasToMzPeakAgreesWithTheRustConverter() throws IOException {
        Path source = MzMLFixtures.extract(MzMLFixtures.TOFSULFAS, Files.createDirectories(dir.resolve("source")));
        Path ours = dir.resolve("ours.mzpeak");
        MzMLConversion.toMzPeak(source, ours, false, MzPeakWriter.Options.defaults());
        Model given = MzMLFixpointTest.mzmlModel(source);

        List<Spectrum> written = spectra(ours);
        List<WavelengthSpectrum> writtenUv;
        List<Chromatogram> writtenChromatograms;
        try (MzPeakReader reader = MzPeakReader.open(ours)) {
            writtenUv = reader.wavelengthSpectra();
            writtenChromatograms = reader.chromatograms();
        }
        Path hasUv = Fixtures.current("has_uv.mzpeak");
        List<Spectrum> reference = spectra(hasUv);
        List<WavelengthSpectrum> referenceUv;
        List<Chromatogram> referenceChromatograms;
        try (MzPeakReader reader = MzPeakReader.open(hasUv)) {
            referenceUv = reader.wavelengthSpectra();
            referenceChromatograms = reader.chromatograms();
        }

        assertThat(written).hasSize(212);
        assertThat(ids(written)).isEqualTo(ids(reference));
        for (int i = 0; i < 212; i++) {
            String what = "spectrum " + i;
            describedAlike(what, reference.get(i), written.get(i), EXACT, true);
            assertThat(bits(written.get(i).mz())).as(what + " m/z").isEqualTo(bits(reference.get(i).mz()));
            assertThat(bits(written.get(i).intensity())).as(what + " intensity")
                    .isEqualTo(bits(reference.get(i).intensity()));
        }

        assertThat(writtenUv).hasSize(520);
        assertThat(referenceUv).hasSize(520);
        // derived from the signal by the Rust writer, not compared: lambda_max and the UV summary params
        assertThat(referenceUv.get(0).lambdaMax()).isEqualTo(374.0);
        assertThat(referenceUv.get(0).parameters()).extracting(Param::accession).contains("MS:1000505", "MS:1000285");
        List<WavelengthSpectrum> givenUv = given.wavelengthSpectra();
        for (int i = 0; i < 520; i++) {
            String what = "UV spectrum " + i;
            WavelengthSpectrum ours1 = writtenUv.get(i);
            WavelengthSpectrum theirs = referenceUv.get(i);
            assertThat(ours1.id()).as(what).isEqualTo(theirs.id());
            assertThat(Double.doubleToRawLongBits(ours1.time())).as(what + " time")
                    .isEqualTo(Double.doubleToRawLongBits(theirs.time()));
            assertThat(bits(ours1.wavelength())).as(what + " wavelength").isEqualTo(bits(theirs.wavelength()));
            assertThat(bits(ours1.intensity())).as(what + " intensity").isEqualTo(bits(theirs.intensity()));
            // the representation goes to its term-marker column, as in has_uv, and comes back without its name
            assertThat(ours1.parameters()).as(what).containsAll(givenUv.get(i).parameters().stream()
                    .map(p -> "MS:1000128".equals(p.accession()) ? new Param(null, p.accession(), p.value(), p.unit())
                            : p)
                    .toList());
            assertThat(ours1.parameters()).as(what).extracting(Param::accession)
                    .contains("MS:1000804", "MS:1000128", "MS:1000619", "MS:1000618", "MS:1000796")
                    .doesNotContain("MS:1000505", "MS:1000285");
            assertThat(ours1.lambdaMax()).as(what).isNull();
        }
        // the source params of the first UV spectrum, merged=212 row=0 (design 12.1)
        assertThat(writtenUv.get(0).parameters()).extracting(Param::accession, Param::value, Param::unit)
                .contains(tuple("MS:1000804", null, null), tuple("MS:1000128", null, null),
                        tuple("MS:1000619", 210.0, "UO:0000018"), tuple("MS:1000618", 400.0, "UO:0000018"),
                        tuple("MS:1000796", "TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.213.213. File:\""
                                + "TOFsulfasMS4GHzDualMode+DADSpectra+UVSignal272-NoProfile.d\", NativeID:\"merged=212"
                                + " row=0\"", null));

        assertThat(writtenChromatograms).hasSize(2);
        assertThat(referenceChromatograms).hasSize(2);
        List<String> units = List.of("MS:1000131", "UO:0000269");
        for (int i = 0; i < 2; i++) {
            Chromatogram ours1 = writtenChromatograms.get(i);
            Chromatogram theirs = referenceChromatograms.get(i);
            assertThat(ours1.id()).isEqualTo(theirs.id());
            assertThat(ours1.typeCurie()).isEqualTo(theirs.typeCurie());
            assertThat(bits(ours1.time())).as(ours1.id() + " time").isEqualTo(bits(theirs.time()));
            assertThat(bits(ours1.intensity())).as(ours1.id() + " intensity").isEqualTo(bits(theirs.intensity()));
            assertThat(ours1.intensityUnit()).isEqualTo(theirs.intensityUnit()).isEqualTo(units.get(i));
        }
        assertThat(writtenChromatograms).extracting(Chromatogram::typeCurie).containsExactly("MS:1000235",
                "MS:1000812");
    }

    // ---- diaPASEF.mzML -> mzPeak vs diaPASEF.ref.mzpeak -----------------------------------------------------------

    @Test
    void diaPasefToMzPeakAgreesWithTheRustConverter() throws IOException {
        List<Spectrum> written = spectra(toMzPeak(MzMLFixtures.DIAPASEF));
        List<Spectrum> reference = spectra(Fixtures.current("diaPASEF.ref.mzpeak"));
        assertThat(written).hasSize(9);
        assertThat(reference).hasSize(9);
        for (int i = 0; i < 9; i++) {
            double[][] ours = sortedRows(written.get(i));
            double[][] theirs = sortedRows(reference.get(i));
            assertThat(theirs.length).as("spectrum %d", i).isEqualTo(ours.length).isPositive();
            double mz = 0;
            double intensity = 0;
            double im = 0;
            for (int k = 0; k < ours.length; k++) {
                mz = Math.max(mz, Math.abs(theirs[k][0] - ours[k][0]) / Math.abs(theirs[k][0]));
                intensity = Math.max(intensity, Math.abs(theirs[k][1] - ours[k][1]));
                im = Math.max(im, Math.abs(theirs[k][2] - ours[k][2]));
            }
            // Math.max lets NaN through, so a non-finite difference would pass the bounds without isFinite
            assertThat(mz).as("spectrum %d m/z, relative", i).isFinite().isLessThanOrEqualTo(1.8e-12);
            assertThat(im).as("spectrum %d ion mobility", i).isFinite()
                    .isLessThanOrEqualTo(0x1p-51); // 4.4e-16, 2 ulp at 1
            assertThat(intensity).as("spectrum %d intensity", i).isFinite().isLessThanOrEqualTo(1.0);
        }
    }

    // ---- reverse: small.mzpeak -> mzML vs small.mzML --------------------------------------------------------------

    @Test
    void smallMzPeakToMzMLAgreesWithTheSourceMzML() throws IOException {
        Path source = MzMLFixtures.extract(MzMLFixtures.SMALL, Files.createDirectories(dir.resolve("source")));
        Path mzml = dir.resolve("small.mzML");
        MzMLConversion.toMzML(Fixtures.current("small.mzpeak"), mzml, true);
        MzMLAssert.valid(mzml);
        Map<String, Spectrum> given = byId(MzMLFixpointTest.mzmlModel(source).spectrumList());
        Map<String, Spectrum> written = byId(MzMLFixpointTest.mzmlModel(mzml).spectrumList());
        assertThat(written).hasSize(48);
        assertThat(written.keySet()).containsExactlyInAnyOrderElementsOf(given.keySet());
        for (Map.Entry<String, Spectrum> e : given.entrySet()) {
            String what = "spectrum " + e.getKey();
            Spectrum ours = written.get(e.getKey());
            describedAlike(what, e.getValue(), ours, FLOAT32);
            strippedAlike(what, e.getValue(), ours);
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** The vendored mzML {@code name} converted to an mzPeak archive in {@link #dir} (defaults). */
    private Path toMzPeak(String name) throws IOException {
        Path source = MzMLFixtures.extract(name, Files.createDirectories(dir.resolve("source")));
        Path target = dir.resolve("ours.mzpeak");
        MzMLConversion.toMzPeak(source, target, false, MzPeakWriter.Options.defaults());
        return target;
    }

    private static List<Spectrum> spectra(Path mzpeak) {
        List<Spectrum> spectra = new ArrayList<>();
        try (MzPeakReader reader = MzPeakReader.open(mzpeak)) {
            reader.forEach(spectra::add);
        }
        return spectra;
    }

    private static List<String> ids(List<Spectrum> spectra) {
        return spectra.stream().map(s -> s.description().id()).toList();
    }

    private static Map<String, Spectrum> byId(List<Spectrum> spectra) {
        return spectra.stream().collect(Collectors.toMap(s -> s.description().id(), Function.identity(),
                (a, b) -> {
                    throw new AssertionError("duplicate id " + a.description().id());
                }, LinkedHashMap::new));
    }

    /**
     * The compared description fields of {@code actual} and {@code expected}: id, MS level, polarity and continuity
     * equal; retention time, scan start and injection times, isolation windows and selected ions (m/z and intensity)
     * within {@code relative} ({@code 0}: bit-equal).
     */
    private static void describedAlike(String what, Spectrum expected, Spectrum actual, double relative) {
        describedAlike(what, expected, actual, relative, false);
    }

    /**
     * As {@link #describedAlike(String, Spectrum, Spectrum, double)}; with {@code zeroForAbsentInjectionTime} every
     * scan of {@code expected} has injection time 0.0 where {@code actual}'s has none: the Rust converter stores 0.0
     * for an injection time the mzML does not give (has_uv from TOFsulfas, whose mzML has no ion injection time).
     */
    private static void describedAlike(String what, Spectrum expected, Spectrum actual, double relative,
                                       boolean zeroForAbsentInjectionTime) {
        SpectrumDescription e = expected.description();
        SpectrumDescription a = actual.description();
        assertThat(Arrays.asList(a.id(), a.msLevel(), a.polarity(), a.signalContinuity())).as(what)
                .isEqualTo(Arrays.asList(e.id(), e.msLevel(), e.polarity(), e.signalContinuity()));
        close(what + " retention time", e.retentionTime(), a.retentionTime(), relative);
        assertThat(a.scans()).as(what + " scans").hasSameSizeAs(e.scans());
        for (int s = 0; s < e.scans().size(); s++) {
            ScanEvent es = e.scans().get(s);
            ScanEvent as = a.scans().get(s);
            close(what + " scan start time", es.startTime(), as.startTime(), relative);
            if (zeroForAbsentInjectionTime) {
                assertThat(es.injectionTime()).as(what + " injection time (Rust default)").isEqualTo(0.0);
                assertThat(as.injectionTime()).as(what + " injection time").isNull();
            } else {
                close(what + " injection time", es.injectionTime(), as.injectionTime(), relative);
            }
        }
        assertThat(a.precursors()).as(what + " precursors").hasSameSizeAs(e.precursors());
        for (int p = 0; p < e.precursors().size(); p++) {
            Precursor ep = e.precursors().get(p);
            Precursor ap = a.precursors().get(p);
            IsolationWindow ew = ep.isolationWindow();
            IsolationWindow aw = ap.isolationWindow();
            assertThat(aw == null).as(what + " isolation window").isEqualTo(ew == null);
            if (ew != null) {
                close(what + " isolation target", ew.targetMz(), aw.targetMz(), relative);
                close(what + " isolation lower offset", ew.lowerOffset(), aw.lowerOffset(), relative);
                close(what + " isolation upper offset", ew.upperOffset(), aw.upperOffset(), relative);
            }
            assertThat(ap.selectedIons()).as(what + " selected ions").hasSameSizeAs(ep.selectedIons());
            for (int k = 0; k < ep.selectedIons().size(); k++) {
                SelectedIon ei = ep.selectedIons().get(k);
                SelectedIon ai = ap.selectedIons().get(k);
                close(what + " selected ion m/z", ei.mz(), ai.mz(), relative);
                close(what + " selected ion intensity", ei.intensity(), ai.intensity(), relative);
            }
        }
    }

    /** {@code actual} within {@code relative} of {@code expected}, both {@code null} or both present. */
    private static void close(String what, Double expected, Double actual, double relative) {
        assertThat(actual == null).as(what + " present").isEqualTo(expected == null);
        if (expected == null) {
            return;
        }
        if (relative == 0) {
            assertThat(Double.doubleToRawLongBits(actual)).as(what).isEqualTo(Double.doubleToRawLongBits(expected));
        } else {
            assertThat(Math.abs(actual - expected)).as(what + ": " + actual + " vs " + expected)
                    .isLessThanOrEqualTo(relative * Math.abs(expected));
        }
    }

    /**
     * {@code stored} holds the points of {@code full} that upstream {@code find_where_not_zero_run} keeps
     * ({@link #notInZeroRun}): intensities exact, m/z exact where the intensity is not zero and within 5e-5 where it
     * is (the zero flanks the Rust converter stores with a null m/z, which the mzPeak read fills in).
     */
    private static void strippedAlike(String what, Spectrum full, Spectrum stored) {
        int[] kept = notInZeroRun(full);
        assertThat(stored.mz()).as(what + " points").hasSize(kept.length);
        for (int k = 0; k < kept.length; k++) {
            int j = kept[k];
            assertThat(stored.intensity()[k]).as(what + " intensity " + k).isEqualTo(full.intensity()[j]);
            if (full.intensity()[j] != 0) {
                assertThat(stored.mz()[k]).as(what + " m/z " + k).isEqualTo(full.mz()[j]);
            } else {
                assertThat(Math.abs(stored.mz()[k] - full.mz()[j])).as(what + " m/z " + k + " (zero flank)")
                        .isLessThanOrEqualTo(5e-5);
            }
        }
    }

    /**
     * The positions upstream {@code find_where_not_zero_run} keeps of a profile spectrum: every non-zero point, and
     * of each run of zeros the first (unless the run starts the spectrum) and the last (unless the run ends it);
     * every position of a centroid spectrum.
     */
    static int[] notInZeroRun(Spectrum s) {
        double[] y = s.intensity();
        if (s.description().signalContinuity() == SignalContinuity.CENTROID) {
            return java.util.stream.IntStream.range(0, y.length).toArray();
        }
        List<Integer> kept = new ArrayList<>();
        boolean wasZero = false;
        for (int i = 0; i < y.length; i++) {
            if (y[i] != 0) {
                kept.add(i);
                wasZero = false;
            } else {
                if (!((wasZero || kept.isEmpty()) && (i == y.length - 1 || y[i + 1] == 0))) {
                    kept.add(i);
                }
                wasZero = true;
            }
        }
        return kept.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The rows (m/z, intensity, ion mobility) of {@code s}, sorted lexicographically. */
    private static double[][] sortedRows(Spectrum s) {
        double[] im = s.arrays().get(ION_MOBILITY);
        assertThat(im).as(s.description().id() + " ion mobility").isNotNull();
        double[][] rows = new double[s.mz().length][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new double[] {s.mz()[i], s.intensity()[i], im[i]};
        }
        Arrays.sort(rows, Arrays::compare);
        return rows;
    }

    /** The IEEE-754 bits of each value, so that a comparison is bit-exact. */
    private static long[] bits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToRawLongBits(values[i]);
        }
        return bits;
    }
}
