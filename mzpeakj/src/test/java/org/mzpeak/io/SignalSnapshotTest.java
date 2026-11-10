package org.mzpeak.io;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bits of every decoded signal array on both fixture sets, so that the signal-layer changes (RD-12, RD-26,
 * RD-13, RD-22, RD-14, RD-20, AP-2, AP-9) can show they leave existing arrays unchanged. Golden sums cannot prove
 * bit-identity; a SHA-256 over the raw IEEE-754 bits can.
 *
 * <ul>
 *   <li><b>Legacy set</b> ({@link Fixtures#LEGACY}) through the public API,
 *       {@link MzPeakReader#open(java.nio.file.Path, boolean)}, for both {@code reconstructProfile} values: per
 *       spectrum its m/z, intensity, centroid m/z and centroid intensity (in {@link Spectrum#peaks()} order); per
 *       chromatogram its time and intensity; per wavelength spectrum its wavelength and intensity. A separate digest
 *       per fixture holds each peak's {@link CentroidPeak#index()} as a double.
 *   <li><b>Current set</b> ({@link Fixtures#CURRENT}) without diaPASEF.grid, whose grid chunks RD-12 and RD-20 change
 *       on purpose. The reader cannot open this layout yet, so the spectrum data and peaks members go straight through
 *       {@link SpectrumArrayStore#load} with an empty delta-model map, for both {@code reconstructProfile} values.
 *       Members are found by file name in {@link MzPeakManifest#files()}, accepting either spelling of the data role
 *       ('data arrays' or 'data_arrays'), so the test runs on the base code. Spectrum indices run from 0 to the
 *       spectra_metadata row count minus 1, read from that member's footer: the peaks footer's spectrum_count is a
 *       per-file count (34 in current small, whose peak indices run 2..47) and cannot bound the loop. An index the
 *       member does not hold is recorded as a marker.
 * </ul>
 *
 * <p>Digest input, all little-endian int64: per entry, in order, the entity index, then either -1 (a marker for an
 * absent index) or the number of arrays followed by each array as its length and the raw bits
 * ({@link Double#doubleToRawLongBits}) of its values. Each snapshot also records the entry count, the absent indices
 * and the total number of values, and for a current member its Parquet row count, so that a mismatch points at what
 * changed.
 *
 * <p>Exception: the intensities of small.numpress ({@link #SLOF_FIXTURES}) are hashed rounded to float32
 * ({@link SignalDigest#float32}). Its spectrum and chromatogram intensities are MS-Numpress SLOF, which the vendored
 * codec decodes with {@link Math#exp}, whose last bit depends on the platform: on x86_64 HotSpot's intrinsic differs
 * from fdlibm (and from aarch64) in about one decoded value in ten. Rounded to float32 the values agree (no
 * difference among 37 million SLOF decodes, over fixed points for maxima from 1 to 1e12, on x86_64 against fdlibm);
 * the rounding is still far finer than a SLOF quantization step, so a changed decode still changes the digest
 * ({@link #float32HidesTheLastBitOfASlofValueButNotOneQuantizationStep}). Its peak intensities are float32 columns,
 * which the rounding keeps exactly; its m/z and times are exact and hashed raw.
 */
class SignalSnapshotTest {

    private static final String GRID_FIXTURE = "diaPASEF.grid.mzpeak";
    private static final String METADATA = "spectra_metadata.parquet";
    private static final String DATA = "spectra_data.parquet";
    private static final String PEAKS = "spectra_peaks.parquet";
    private static final Set<String> DATA_ROLES = Set.of("data arrays", "data_arrays");
    /** The fixtures whose intensities are MS-Numpress SLOF, hashed rounded to float32 (see the class comment). */
    static final Set<String> SLOF_FIXTURES = Set.of("small.numpress.mzpeak");

    /** Snapshot key to {@code <summary> sha256=<hex>}, recorded on the base code (agent-team/upstream-p0 5956240). */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            snap("legacy small.mzpeak reconstructProfile=false spectra",
                    "entries=48 absent=none values=456860",
                    "afa2b6b6f2195f0b3a58525acf292733e1e12ea8aea099c9a7eea075f6af3d09"),
            snap("legacy small.mzpeak reconstructProfile=false chromatograms",
                    "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            snap("legacy small.mzpeak reconstructProfile=false wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            snap("legacy small.mzpeak reconstructProfile=true spectra",
                    "entries=48 absent=none values=536796",
                    "6103dca83f94f601cb0fce07426b1d363498572b06d1145c8c47166bb62d18c0"),
            snap("legacy small.mzpeak reconstructProfile=true chromatograms",
                    "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            snap("legacy small.mzpeak reconstructProfile=true wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),

            snap("legacy small.chunked.mzpeak reconstructProfile=false spectra",
                    "entries=48 absent=none values=506666",
                    "3436bd1d80111dc716c2c48b906cd360362b7b2df549f9c3f1c85eca65ad2c9f"),
            snap("legacy small.chunked.mzpeak reconstructProfile=false chromatograms",
                    "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            snap("legacy small.chunked.mzpeak reconstructProfile=false wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            snap("legacy small.chunked.mzpeak reconstructProfile=true spectra",
                    "entries=48 absent=none values=586602",
                    "fea88b3969c9ccee14afb8d1fd8e4b9edf15822959fdb095b3317c72e6bba958"),
            snap("legacy small.chunked.mzpeak reconstructProfile=true chromatograms",
                    "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            snap("legacy small.chunked.mzpeak reconstructProfile=true wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),

            snap("legacy small.numpress.mzpeak reconstructProfile=false spectra",
                    "entries=48 absent=none values=536796",
                    "dacf5ee29806b4afb4e728c64d618c0a89dd3f6597b2bc9bc8f4cc51e484f4ca"),
            snap("legacy small.numpress.mzpeak reconstructProfile=false chromatograms",
                    "entries=1 absent=none values=96",
                    "55721ab9cc234c7dbf6395bbb949befbd2374448b6ac80e7e9ecc815a6945703"),
            snap("legacy small.numpress.mzpeak reconstructProfile=false wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            snap("legacy small.numpress.mzpeak reconstructProfile=true spectra",
                    "entries=48 absent=none values=536796",
                    "dacf5ee29806b4afb4e728c64d618c0a89dd3f6597b2bc9bc8f4cc51e484f4ca"),
            snap("legacy small.numpress.mzpeak reconstructProfile=true chromatograms",
                    "entries=1 absent=none values=96",
                    "55721ab9cc234c7dbf6395bbb949befbd2374448b6ac80e7e9ecc815a6945703"),
            snap("legacy small.numpress.mzpeak reconstructProfile=true wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),

            snap("legacy small.unpacked.mzpeak reconstructProfile=false spectra",
                    "entries=48 absent=none values=456860",
                    "afa2b6b6f2195f0b3a58525acf292733e1e12ea8aea099c9a7eea075f6af3d09"),
            snap("legacy small.unpacked.mzpeak reconstructProfile=false chromatograms",
                    "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            snap("legacy small.unpacked.mzpeak reconstructProfile=false wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            snap("legacy small.unpacked.mzpeak reconstructProfile=true spectra",
                    "entries=48 absent=none values=536796",
                    "6103dca83f94f601cb0fce07426b1d363498572b06d1145c8c47166bb62d18c0"),
            snap("legacy small.unpacked.mzpeak reconstructProfile=true chromatograms",
                    "entries=1 absent=none values=96",
                    "316eab9cb4e2028177069ca8bade8660ed0b66fba3ccde716c924eab8baf98ee"),
            snap("legacy small.unpacked.mzpeak reconstructProfile=true wavelength_spectra",
                    "entries=0 absent=none values=0",
                    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),

            snap("legacy has_uv.mzpeak reconstructProfile=false spectra",
                    "entries=212 absent=none values=97240",
                    "4d03af6b025b5066d7e1a16d71c41ec321a6b8fd41190ed695e5c153de123c45"),
            snap("legacy has_uv.mzpeak reconstructProfile=false chromatograms",
                    "entries=2 absent=none values=1476",
                    "7b80a16b9a131321b46839e48286b01ac0db3bff257228b20164d58629505bda"),
            snap("legacy has_uv.mzpeak reconstructProfile=false wavelength_spectra",
                    "entries=520 absent=none values=99840",
                    "9dc0c387ba84510932cc6426bb666b2f28b61b2a18cdd2e878902611f6471795"),
            snap("legacy has_uv.mzpeak reconstructProfile=true spectra",
                    "entries=212 absent=none values=97240",
                    "4d03af6b025b5066d7e1a16d71c41ec321a6b8fd41190ed695e5c153de123c45"),
            snap("legacy has_uv.mzpeak reconstructProfile=true chromatograms",
                    "entries=2 absent=none values=1476",
                    "7b80a16b9a131321b46839e48286b01ac0db3bff257228b20164d58629505bda"),
            snap("legacy has_uv.mzpeak reconstructProfile=true wavelength_spectra",
                    "entries=520 absent=none values=99840",
                    "9dc0c387ba84510932cc6426bb666b2f28b61b2a18cdd2e878902611f6471795"),

            snap("current small.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=217710 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=355484",
                    "39e01eed7ccc3b26cf4d4eb5ee06777f699f99f84aabf6323a7b662d809cf705"),
            snap("current small.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=25344 entries=48 absent=0-1,7-8,14-15,21-22,28-29,34-35,41-42 values=50688",
                    "433b1d0b778db88f556f94f77f153eb2b575a9fe1c39ec1dd2a0836b8fbb0393"),
            snap("current small.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=217710 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=435420",
                    "6d8c817cae96cc9b92fe0bb8519b13b2391d4473e0d0fb33fc855e7250c48621"),
            snap("current small.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=25344 entries=48 absent=0-1,7-8,14-15,21-22,28-29,34-35,41-42 values=50688",
                    "433b1d0b778db88f556f94f77f153eb2b575a9fe1c39ec1dd2a0836b8fbb0393"),

            snap("current small.chunked.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=488 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=355484",
                    "39e01eed7ccc3b26cf4d4eb5ee06777f699f99f84aabf6323a7b662d809cf705"),
            snap("current small.chunked.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=50247 entries=48 absent=none values=100494",
                    "995920c40e818d84e0a8e5f70f3205cfcb4c136ac2565208fec1d3206d109efe"),
            snap("current small.chunked.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=488 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=435420",
                    "6d8c817cae96cc9b92fe0bb8519b13b2391d4473e0d0fb33fc855e7250c48621"),
            snap("current small.chunked.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=50247 entries=48 absent=none values=100494",
                    "995920c40e818d84e0a8e5f70f3205cfcb4c136ac2565208fec1d3206d109efe"),

            snap("current small.numpress.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=488 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=435420",
                    "8445b3ad39a8b52fbbde6e54c4876a4a5ad075e98495487fecd4c7846463d119"),
            snap("current small.numpress.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=25344 entries=48 absent=0-1,7-8,14-15,21-22,28-29,34-35,41-42 values=50688",
                    "433b1d0b778db88f556f94f77f153eb2b575a9fe1c39ec1dd2a0836b8fbb0393"),
            snap("current small.numpress.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=488 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=435420",
                    "8445b3ad39a8b52fbbde6e54c4876a4a5ad075e98495487fecd4c7846463d119"),
            snap("current small.numpress.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=25344 entries=48 absent=0-1,7-8,14-15,21-22,28-29,34-35,41-42 values=50688",
                    "433b1d0b778db88f556f94f77f153eb2b575a9fe1c39ec1dd2a0836b8fbb0393"),

            snap("current small.unpacked.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=217710 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=355484",
                    "39e01eed7ccc3b26cf4d4eb5ee06777f699f99f84aabf6323a7b662d809cf705"),
            snap("current small.unpacked.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=25344 entries=48 absent=0-1,7-8,14-15,21-22,28-29,34-35,41-42 values=50688",
                    "433b1d0b778db88f556f94f77f153eb2b575a9fe1c39ec1dd2a0836b8fbb0393"),
            snap("current small.unpacked.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=217710 entries=48 absent=2-6,9-13,16-20,23-27,30-33,36-40,43-47 values=435420",
                    "6d8c817cae96cc9b92fe0bb8519b13b2391d4473e0d0fb33fc855e7250c48621"),
            snap("current small.unpacked.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=25344 entries=48 absent=0-1,7-8,14-15,21-22,28-29,34-35,41-42 values=50688",
                    "433b1d0b778db88f556f94f77f153eb2b575a9fe1c39ec1dd2a0836b8fbb0393"),

            snap("current has_uv.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=0 entries=212 absent=0-211 values=0",
                    "f4ee616532511c6edf9fbbb1a5bf6b4555986f9610152440f85c6234aa4a5772"),
            snap("current has_uv.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=24310 entries=212 absent=none values=48620",
                    "3e331567cb0b51bfa111e188f4c07291f86cb115eb058ee381552ffbc95ecaa7"),
            snap("current has_uv.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=0 entries=212 absent=0-211 values=0",
                    "f4ee616532511c6edf9fbbb1a5bf6b4555986f9610152440f85c6234aa4a5772"),
            snap("current has_uv.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=24310 entries=212 absent=none values=48620",
                    "3e331567cb0b51bfa111e188f4c07291f86cb115eb058ee381552ffbc95ecaa7"),

            snap("current diaPASEF.ref.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=0 entries=9 absent=0-8 values=0",
                    "6d977585e8775ed96c4dd2e80bd485868cfe0baaa1ffa137f9673a4967d502f0"),
            snap("current diaPASEF.ref.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=291453 entries=9 absent=none values=582906",
                    "5baea39edca776e71b539cc6f40a9775ac866359a30d294335093a6a8e0cbc2b"),
            snap("current diaPASEF.ref.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=0 entries=9 absent=0-8 values=0",
                    "6d977585e8775ed96c4dd2e80bd485868cfe0baaa1ffa137f9673a4967d502f0"),
            snap("current diaPASEF.ref.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=291453 entries=9 absent=none values=582906",
                    "5baea39edca776e71b539cc6f40a9775ac866359a30d294335093a6a8e0cbc2b"),

            snap("current Example_Processed.img.mzpeak reconstructProfile=false spectra_data.parquet",
                    "member_rows=36856 entries=9 absent=none values=47140",
                    "eb6a54a1ef5dd7a13d5931a8d01df11d6a7d6fa38580b3c625eb295258afe5bd"),
            snap("current Example_Processed.img.mzpeak reconstructProfile=false spectra_peaks.parquet",
                    "member_rows=0 entries=9 absent=0-8 values=0",
                    "6d977585e8775ed96c4dd2e80bd485868cfe0baaa1ffa137f9673a4967d502f0"),
            snap("current Example_Processed.img.mzpeak reconstructProfile=true spectra_data.parquet",
                    "member_rows=36856 entries=9 absent=none values=73712",
                    "0198d25e4abb5dd1e96b1117c448608f7a9a7f38a241b005c3175ba20e4a3a23"),
            snap("current Example_Processed.img.mzpeak reconstructProfile=true spectra_peaks.parquet",
                    "member_rows=0 entries=9 absent=0-8 values=0",
                    "6d977585e8775ed96c4dd2e80bd485868cfe0baaa1ffa137f9673a4967d502f0"));

    /**
     * Key {@code legacy <fixture> peak_indices} to the digest of every peak's {@link CentroidPeak#index()} through the
     * public API, the same in both reconstructProfile modes; recorded on base 978ef13. Kept apart from
     * {@link #EXPECTED}, whose digests stay as recorded.
     */
    private static final Map<String, String> PEAK_INDICES = Map.ofEntries(
            snap("legacy small.mzpeak peak_indices",
                    "entries=48 absent=none values=25344",
                    "7820963326e658bb58f148b84d92ddd51065eea61c7a7ef2a84200c955c28201"),
            snap("legacy small.chunked.mzpeak peak_indices",
                    "entries=48 absent=none values=50247",
                    "eec1a8103a0fdf7bbb022bc6509978fc34aa3f5e66375a3e61af3cd1c08d4fae"),
            snap("legacy small.numpress.mzpeak peak_indices",
                    "entries=48 absent=none values=25344",
                    "7820963326e658bb58f148b84d92ddd51065eea61c7a7ef2a84200c955c28201"),
            snap("legacy small.unpacked.mzpeak peak_indices",
                    "entries=48 absent=none values=25344",
                    "7820963326e658bb58f148b84d92ddd51065eea61c7a7ef2a84200c955c28201"),
            snap("legacy has_uv.mzpeak peak_indices",
                    "entries=212 absent=none values=24310",
                    "3fdb61181f762af7a3a0de781980070539b1f373312e5348ecc3324fa2f7cf75"));

    static Stream<Arguments> legacyCases() {
        return Fixtures.LEGACY_NAMES.stream()
                .flatMap(name -> Stream.of(Arguments.of(name, false), Arguments.of(name, true)));
    }

    static Stream<Arguments> currentCases() {
        return Fixtures.CURRENT_NAMES.stream().filter(name -> !name.equals(GRID_FIXTURE))
                .flatMap(name -> Stream.of(Arguments.of(name, false), Arguments.of(name, true)));
    }

    @ParameterizedTest(name = "{0}, reconstructProfile={1}")
    @MethodSource("legacyCases")
    void legacyArraysThroughThePublicApi(String fixture, boolean reconstructProfile) {
        String prefix = "legacy " + fixture + " reconstructProfile=" + reconstructProfile + " ";
        assertThat(legacySnapshot(fixture, reconstructProfile)).containsExactlyInAnyOrderEntriesOf(expected(prefix));
    }

    /** Each peak's {@link CentroidPeak#index()}, its position in the stored peak list, is pinned like the arrays. */
    @ParameterizedTest(name = "{0}, reconstructProfile={1}")
    @MethodSource("legacyCases")
    void legacyPeakIndicesThroughThePublicApi(String fixture, boolean reconstructProfile) {
        SignalDigest digest = new SignalDigest("");
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy(fixture), reconstructProfile)) {
            for (Spectrum s : reader) {
                List<CentroidPeak> peaks = s.peaks();
                double[] indices = new double[peaks.size()];
                for (int i = 0; i < peaks.size(); i++) {
                    indices[i] = peaks.get(i).index();
                }
                digest.entry(s.index(), indices);
            }
        }
        String key = "legacy " + fixture + " peak_indices";
        assertThat(PEAK_INDICES).containsKey(key);
        assertThat(digest.summary()).isEqualTo(PEAK_INDICES.get(key));
    }

    @ParameterizedTest(name = "{0}, reconstructProfile={1}")
    @MethodSource("currentCases")
    void currentArraysThroughTheSpectrumStore(String fixture, boolean reconstructProfile) {
        String prefix = "current " + fixture + " reconstructProfile=" + reconstructProfile + " ";
        Map<String, String> actual = new LinkedHashMap<>();
        currentDigests(fixture, reconstructProfile).forEach((member, d) -> actual.put(prefix + member, d.summary()));
        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected(prefix));
    }

    @Test
    void currentSmallPeaksLoopRunsToTheLastMetadataRow() {
        // The peaks footer counts 34 spectra; their indices run 2..47, so only the metadata row count reaches 47.
        SignalDigest peaks = currentDigests("small.mzpeak", false).get(PEAKS);
        assertThat(peaks.entries()).isEqualTo(48);
        assertThat(peaks.absent()).hasSize(48 - 34).doesNotContain(2L, 47L);
    }

    @Test
    void oneFlippedBitChangesTheDigest() {
        double[] mz;
        double[] intensity;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("small.mzpeak"), false)) {
            Spectrum spectrum = reader.getSpectrum(0).orElseThrow();
            mz = spectrum.mz();
            intensity = spectrum.intensity();
        }
        String original = digest(0, mz, intensity);
        assertThat(digest(0, mz.clone(), intensity.clone())).isEqualTo(original);

        assertThat(digest(0, flipped(mz, mz.length / 2), intensity)).isNotEqualTo(original);
        // A flip in the first and in the last buffer window of a stream that spans several windows: the digest
        // covers every window, the final partial one included. Stream: index, array count, then length and values.
        long lastPosition = 3L + mz.length + 1 + intensity.length - 1;
        assertThat(lastPosition / SignalDigest.BUFFER_LONGS).as("last buffer window").isGreaterThanOrEqualTo(2);
        assertThat(digest(0, flipped(mz, 0), intensity)).isNotEqualTo(original);
        assertThat(digest(0, mz, flipped(intensity, intensity.length - 1))).isNotEqualTo(original);
        // The length prefix tells arrays apart, the raw bits tell -0.0 from 0.0, and a marker is not an empty entry.
        assertThat(digest(0, new double[] {1, 2}, new double[] {3}))
                .isNotEqualTo(digest(0, new double[] {1}, new double[] {2, 3}));
        assertThat(digest(0, new double[] {0.0})).isNotEqualTo(digest(0, new double[] {-0.0}));
        SignalDigest marker = new SignalDigest("");
        marker.marker(0);
        assertThat(sha256(marker)).isNotEqualTo(digest(0));
    }

    /**
     * The float32 rounding of SLOF intensities hides what differs between platforms, the last bit of a decoded double,
     * but not a change of one SLOF quantization step: decoded = exp(x / fixedPoint) - 1 for an integer x, so a step
     * changes value + 1 by the factor exp(1 / fixedPoint), at least about 1 + 1.06e-5 for the 16-bit range (fixed point
     * 0xFFFF / ln(max + 1) at most 65535 / ln 2), far above a float32 ulp (6e-8).
     */
    @Test
    void float32HidesTheLastBitOfASlofValueButNotOneQuantizationStep() {
        double[] intensity;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy("small.numpress.mzpeak"), false)) {
            intensity = reader.getSpectrum(0).orElseThrow().intensity();
        }
        int i = intensity.length / 2;
        assertThat(intensity[i]).isPositive();
        String original = digest(0, SignalDigest.float32(intensity));
        assertThat(digest(0, SignalDigest.float32(flipped(intensity, i)))).isEqualTo(original);
        assertThat(digest(0, flipped(intensity, i))).isNotEqualTo(digest(0, intensity));

        double[] stepped = intensity.clone();
        stepped[i] = (intensity[i] + 1) * Math.exp(1.0 / (0xFFFF / Math.log(2))) - 1;
        assertThat(digest(0, SignalDigest.float32(stepped))).isNotEqualTo(original);
    }

    /** A copy of {@code values} with the lowest bit of element {@code i} flipped. */
    private static double[] flipped(double[] values, int i) {
        double[] out = values.clone();
        out[i] = Double.longBitsToDouble(Double.doubleToRawLongBits(out[i]) ^ 1L);
        return out;
    }

    private static String digest(long index, double[]... arrays) {
        SignalDigest digest = new SignalDigest("");
        digest.entry(index, arrays);
        return sha256(digest);
    }

    private static String sha256(SignalDigest digest) {
        String summary = digest.summary();
        return summary.substring(summary.indexOf(" sha256=") + " sha256=".length());
    }

    /** The legacy snapshot lines of one fixture and mode, keyed like {@link #EXPECTED}. */
    static Map<String, String> legacySnapshot(String fixture, boolean reconstructProfile) {
        SignalDigest spectra = new SignalDigest("");
        SignalDigest chromatograms = new SignalDigest("");
        SignalDigest wavelength = new SignalDigest("");
        UnaryOperator<double[]> intensity = SLOF_FIXTURES.contains(fixture) ? SignalDigest::float32 : v -> v;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.legacy(fixture), reconstructProfile)) {
            for (Spectrum s : reader) {
                List<CentroidPeak> peaks = s.peaks();
                double[] peakMz = new double[peaks.size()];
                double[] peakIntensity = new double[peaks.size()];
                for (int i = 0; i < peaks.size(); i++) {
                    peakMz[i] = peaks.get(i).mz();
                    peakIntensity[i] = peaks.get(i).intensity();
                }
                spectra.entry(s.index(), s.mz(), intensity.apply(s.intensity()), peakMz,
                        intensity.apply(peakIntensity));
            }
            for (Chromatogram c : reader.chromatograms()) {
                chromatograms.entry(c.index(), c.time(), intensity.apply(c.intensity()));
            }
            for (WavelengthSpectrum w : reader.wavelengthSpectra()) {
                wavelength.entry(w.index(), w.wavelength(), w.intensity());
            }
        }
        String prefix = "legacy " + fixture + " reconstructProfile=" + reconstructProfile + " ";
        Map<String, String> out = new LinkedHashMap<>();
        out.put(prefix + "spectra", spectra.summary());
        out.put(prefix + "chromatograms", chromatograms.summary());
        out.put(prefix + "wavelength_spectra", wavelength.summary());
        return out;
    }

    /**
     * The digests of a current fixture's spectrum data and peaks members, keyed by member name, over spectrum indices
     * 0 to the spectra_metadata row count minus 1.
     */
    static Map<String, SignalDigest> currentDigests(String fixture, boolean reconstructProfile) {
        Map<String, SignalDigest> out = new LinkedHashMap<>();
        UnaryOperator<double[]> intensity = SLOF_FIXTURES.contains(fixture) ? SignalDigest::float32 : v -> v;
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current(fixture))) {
            MzPeakManifest manifest = MzPeakManifest.fromSource(source);
            long spectra = rowCount(source, member(manifest, METADATA, Set.of("metadata")));
            for (String name : List.of(member(manifest, DATA, DATA_ROLES), member(manifest, PEAKS, Set.of("peaks")))) {
                SignalDigest digest = new SignalDigest("member_rows=" + rowCount(source, name) + " ");
                try (SpectrumArrayStore store =
                             SpectrumArrayStore.load(source.inputFile(name), reconstructProfile, Map.of())) {
                    for (long index = 0; index < spectra; index++) {
                        SpectrumArrayStore.Arrays arrays = store.get(index);
                        if (arrays == null) {
                            digest.marker(index);
                        } else {
                            digest.entry(index, arrays.mz(), intensity.apply(arrays.intensity()));
                        }
                    }
                }
                out.put(name, digest);
            }
        }
        return out;
    }

    /** The manifest entry named {@code fileName}, checked to be a spectrum member with one of {@code roles}. */
    private static String member(MzPeakManifest manifest, String fileName, Set<String> roles) {
        MzPeakManifest.Entry entry = manifest.files().stream().filter(e -> fileName.equals(e.name())).findFirst()
                .orElseThrow(() -> new AssertionError("manifest lists no " + fileName));
        assertThat(entry.entityType()).as("entity type of %s", fileName).isEqualTo("spectrum");
        assertThat(entry.dataKind()).as("data kind of %s", fileName).isIn(roles);
        return entry.name();
    }

    private static long rowCount(MzPeakSource source, String member) {
        try (ParquetFileReader reader =
                     ParquetFileReader.open(source.inputFile(member), ParquetGroups.readOptions())) {
            return reader.getRecordCount();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed reading the footer of " + member, e);
        }
    }

    private static Map<String, String> expected(String prefix) {
        return EXPECTED.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, TreeMap::new));
    }

    private static Map.Entry<String, String> snap(String key, String summary, String sha256) {
        return Map.entry(key, summary + " sha256=" + sha256);
    }

    /**
     * One snapshot: a SHA-256 over the entries' raw bits (layout in the class comment), the entry count, the absent
     * indices as ranges and the total number of values.
     */
    static final class SignalDigest {
        /** The int64 values the buffer holds before it is passed to the digest (a 32 KB window). */
        static final int BUFFER_LONGS = 4096;

        private final String prefix;
        private final MessageDigest sha = sha256();
        private final ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES * BUFFER_LONGS)
                .order(ByteOrder.LITTLE_ENDIAN);
        private final List<Long> absent = new ArrayList<>();
        private long entries;
        private long values;
        private String summary;

        SignalDigest(String prefix) {
            this.prefix = prefix;
        }

        /** Records that the member holds no arrays for {@code index}. */
        void marker(long index) {
            putLong(index);
            putLong(-1);
            absent.add(index);
            entries++;
        }

        void entry(long index, double[]... arrays) {
            putLong(index);
            putLong(arrays.length);
            for (double[] array : arrays) {
                putLong(array.length);
                for (double v : array) {
                    putLong(Double.doubleToRawLongBits(v));
                }
                values += array.length;
            }
            entries++;
        }

        long entries() {
            return entries;
        }

        /**
         * A copy of {@code values}, each rounded to float32 and widened back: for values decoded from MS-Numpress
         * SLOF, whose last bits depend on the platform's {@link Math#exp} (see the class comment). Residual risk: a
         * decoded value within a double ulp of a float32 rounding boundary could round differently on two platforms;
         * none did among 37 million SLOF decodes compared on x86_64 and aarch64.
         */
        static double[] float32(double[] values) {
            double[] out = new double[values.length];
            for (int i = 0; i < values.length; i++) {
                out[i] = (float) values[i];
            }
            return out;
        }

        List<Long> absent() {
            return absent;
        }

        /** {@code <prefix>entries=.. absent=.. values=.. sha256=..}; finishes the digest on first use. */
        String summary() {
            if (summary == null) {
                buffer.flip();
                sha.update(buffer);
                summary = prefix + "entries=" + entries + " absent=" + ranges(absent) + " values=" + values
                        + " sha256=" + HexFormat.of().formatHex(sha.digest());
            }
            return summary;
        }

        private void putLong(long v) {
            if (summary != null) {
                throw new IllegalStateException("digest already finished");
            }
            if (!buffer.hasRemaining()) {
                buffer.flip();
                sha.update(buffer);
                buffer.clear();
            }
            buffer.putLong(v);
        }

        /** Ascending indices as {@code a-b} ranges joined by commas, or {@code none}. */
        private static String ranges(List<Long> indices) {
            if (indices.isEmpty()) {
                return "none";
            }
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < indices.size()) {
                int j = i;
                while (j + 1 < indices.size() && indices.get(j + 1) == indices.get(j) + 1) {
                    j++;
                }
                out.append(out.length() == 0 ? "" : ",").append(indices.get(i));
                if (j > i) {
                    out.append('-').append(indices.get(j));
                }
                i = j + 1;
            }
            return out.toString();
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
