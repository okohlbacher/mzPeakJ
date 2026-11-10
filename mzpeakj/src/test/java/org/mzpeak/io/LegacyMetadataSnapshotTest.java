package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.model.Activation;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization of legacy metadata decoding (CH-1): pins what the reader decodes from the packed-layout
 * fixtures ({@link Fixtures#LEGACY}, HUPO-PSI/mzPeak 29e59b2) before RD-3, RD-1, RD-24, RD-25, RD-7, RD-8 and
 * RD-9 restructure the decoders. For each fixture, opened with {@code reconstructProfile=true}, it compares the
 * SHA-256 of the {@link Canonical} lines of {@code metadata()}, {@code chromatograms()} and
 * {@code wavelengthSpectra()}, plus their sizes. The two small.unpacked lines below show the format in full.
 *
 * <p>A unit that changes decoded metadata on purpose (AP-7, for example) updates these values and says why.
 */
class LegacyMetadataSnapshotTest {

    /** Sizes and SHA-256 digests of the canonical lines of one fixture. */
    record Snapshot(int spectra, int chromatograms, int wavelengthSpectra,
                    String metadataSha256, String chromatogramsSha256, String wavelengthSpectraSha256) {
    }

    /** SHA-256 of zero lines, for a fixture without chromatograms or wavelength spectra. */
    private static final String NONE = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    // AP-7: the line of each of the 34 MS2 spectra of small gained "|[]", the empty parameters of its isolation
    // window; nothing else changed (has_uv has no precursors). Final review: an absent string component reads U+2400
    // instead of "null" (Canonical.ABSENT), which changes every metadata digest (each scan has no spectrum reference,
    // and params without a unit or accession), and no chromatogram or wavelength digest (their ids and types are set).
    private static final String SMALL_METADATA = "80c4bbaaf57645d0ee821694153b3200df03d22516683e3d2a81322ce4d52865";
    private static final String SMALL_TIC = "8a8fa9c38b9eb11c3b0bb2465b884a21250efa89e2f08853e1a64be80e6b2111";

    private static final Map<String, Snapshot> EXPECTED = Map.of(
            "small.mzpeak", new Snapshot(48, 1, 0, SMALL_METADATA, SMALL_TIC, NONE),
            // small.chunked also declares MS:1003059 number of peaks on its profile spectra
            "small.chunked.mzpeak", new Snapshot(48, 1, 0,
                    "5793db6f251216667ed165d10223bf2c7afc68a4d252107fdfcb36d54c4f2a6b", SMALL_TIC, NONE),
            "small.numpress.mzpeak", new Snapshot(48, 1, 0, SMALL_METADATA, SMALL_TIC, NONE),
            "small.unpacked.mzpeak", new Snapshot(48, 1, 0, SMALL_METADATA, SMALL_TIC, NONE),
            // MZ-4: each of the 520 wavelength lines gained "|[...]", the 4 Params of its packed struct's parameters
            // list (spectrum type, lowest and highest observed wavelength, spectrum title), which the reader used to
            // drop; header fields and lengths are unchanged (e8f510ac... before). No other digest changed.
            // LIB-1: each wavelength line gained 3 more Params, the leftovers of its packed struct's scalar columns
            // (representation MS:1000128, base peak intensity, TIC), as current has_uv gives them (83b63f36...
            // before). No other digest changed.
            "has_uv.mzpeak", new Snapshot(212, 2, 520,
                    "35518229d5314fb17f486361d16934af5dd9234c51183d363a7036aecc80c8a8",
                    "907aeb63c7f4823af2bd626503cc185c55f8a9df07ba2aa8442fcdb56c44d099",
                    "80e459e37d589ce848e546dc937b3872fb401e7ec07521fa63602eb7e62dad3e"));

    static Stream<String> legacyFixtures() {
        return Fixtures.LEGACY_NAMES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFixtures")
    void decodedMetadataMatchesSnapshot(String fixture) {
        Snapshot expected = EXPECTED.get(fixture);
        assertThat(expected).as("snapshot for %s", fixture).isNotNull();
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy(fixture), true)) {
            Snapshot actual = new Snapshot(
                    reader.size(),
                    reader.chromatograms().size(),
                    reader.wavelengthSpectra().size(),
                    Canonical.sha256(Canonical.lines(reader.metadata(), Canonical::describe)),
                    Canonical.sha256(Canonical.lines(reader.chromatograms(), Canonical::describe)),
                    Canonical.sha256(Canonical.lines(reader.wavelengthSpectra(), Canonical::describe)));
            assertThat(actual).as("decoded metadata of %s", fixture).isEqualTo(expected);
        }
    }

    @Test
    void smallUnpackedAnchorLines() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("small.unpacked.mzpeak"), true)) {
            List<SpectrumDescription> metadata = reader.metadata();
            assertThat(metadata).hasSize(48);
            assertThat(Canonical.describe(metadata.get(0))).isEqualTo(
                    "0|controllerType=0 controllerNumber=1 scan=1|1|0.004935|POSITIVE|PROFILE|13589|0"
                            + "|[(0.004935000091791153|68.22748565673828|FTMS + p ESI Full ms [200.00-2000.00]|1|0|␀"
                            + "|[(200.0|2000.0)]"
                            + "|[(mass resolving power|MS:1000800|Long:100000|␀)])]"
                            + "|[]"
                            + "|[]");
            assertThat(Canonical.describe(metadata.get(5))).isEqualTo(
                    "5|controllerType=0 controllerNumber=1 scan=6|2|0.04862|POSITIVE|CENTROID|0|650"
                            + "|[(0.04862000048160553|66.4786376953125"
                            + "|ITMS + c ESI d Full ms2 558.87@cid35.00 [140.00-1130.00]|6|1|␀"
                            + "|[(140.0|1130.0)]"
                            + "|[([Thermo Trailer Extra]Monoisotopic M/Z:|␀|Long:0|␀)])]"
                            + "|[(1|controllerType=0 controllerNumber=1 scan=2"
                            + "|(558.8689575195312|1.0|1.0|[])"
                            + "|[(558.868957519531|null|202177.84375|[])]"
                            + "|([(collision-induced dissociation|MS:1000133|null|␀)"
                            + ";(collision energy|MS:1000045|Double:35.0|UO:0000266)]))]"
                            + "|[]");
        }
    }

    @Test
    void paramLineCarriesTheValueClass() {
        assertThat(Canonical.param(new Param("n", "MS:1", 5L, null))).isEqualTo("(n|MS:1|Long:5|␀)");
        assertThat(Canonical.param(new Param("n", "MS:1", 5, null))).isEqualTo("(n|MS:1|Integer:5|␀)");
        assertThat(Canonical.param(new Param("n", "MS:1", 5.0, "UO:1"))).isEqualTo("(n|MS:1|Double:5.0|UO:1)");
        assertThat(Canonical.param(new Param("n", null, "5", null))).isEqualTo("(n|␀|String:5|␀)");
        assertThat(Canonical.param(new Param("n", null, null, null))).isEqualTo("(n|␀|null|␀)");
        // An absent string and the string "null" read differently; an absent value still reads null.
        assertThat(Canonical.param(new Param("null", "null", "null", "null")))
                .isEqualTo("(null|null|String:null|null)");
        assertThat(Canonical.param(new Param(null, null, null, null))).isEqualTo("(␀|␀|null|␀)");
    }

    @Test
    void numIsTheShortestExactDecimalOnEveryJdk() {
        assertThat(Canonical.num(0.1)).isEqualTo("0.1");
        assertThat(Canonical.num(200.0)).isEqualTo("200.0");
        assertThat(Canonical.num(-1130.0)).isEqualTo("-1130.0");
        assertThat(Canonical.num(1.0 / 3)).isEqualTo("0.3333333333333333");
        assertThat(Canonical.num(1e-5)).isEqualTo("1.0E-5");
        assertThat(Canonical.num(1.25e7)).isEqualTo("1.25E7");
        assertThat(Canonical.num(2.82879384806159E17)).isEqualTo("2.82879384806159E17");
        assertThat(Canonical.num(0.0)).isEqualTo("0.0");
        assertThat(Canonical.num(-0.0)).isEqualTo("-0.0");
        assertThat(Canonical.num(Double.NaN)).isEqualTo("NaN");
        assertThat(Canonical.num((Double) null)).isEqualTo("null");

        Random random = new Random(20261107L);
        for (int i = 0; i < 10_000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            if (Double.isFinite(d)) {
                assertThat(Double.parseDouble(Canonical.num(d))).as("num(%s)", d).isEqualTo(d);
            }
        }
    }

    /** Canonical writes components by name; this fails when a model record gains one it does not write. */
    @Test
    void canonicalFormCoversEveryRecordComponent() {
        assertThat(components(SpectrumDescription.class)).containsExactly("index", "id", "msLevel", "retentionTime",
                "polarity", "signalContinuity", "numberOfDataPoints", "numberOfPeaks", "scans", "precursors",
                "parameters");
        assertThat(components(ScanEvent.class)).containsExactly("startTime", "injectionTime", "filterString",
                "presetScanConfiguration", "instrumentConfigurationRef", "spectrumReference", "scanWindows",
                "parameters");
        assertThat(components(ScanWindow.class)).containsExactly("lowerLimit", "upperLimit");
        assertThat(components(Precursor.class)).containsExactly("precursorIndex", "precursorId", "isolationWindow",
                "selectedIons", "activation");
        assertThat(components(IsolationWindow.class)).containsExactly("targetMz", "lowerOffset", "upperOffset",
                "parameters");
        assertThat(components(SelectedIon.class)).containsExactly("mz", "charge", "intensity", "parameters");
        assertThat(components(Activation.class)).containsExactly("parameters");
        assertThat(components(Param.class)).containsExactly("name", "accession", "value", "unit");
        // The wavelength line writes the arrays as their length.
        assertThat(components(WavelengthSpectrum.class)).containsExactly("index", "id", "time", "lambdaMax",
                "wavelength", "intensity", "parameters");
    }

    private static List<String> components(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
