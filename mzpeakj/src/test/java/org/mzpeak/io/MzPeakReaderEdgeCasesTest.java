package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.InputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakReader.Options;
import org.mzpeak.io.MzPeakReader.SignalMode;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.SignalSlice;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MzPeakReader} paths the other reader tests do not take: lookups that find nothing, the nearest-time lookup at
 * and between the ends of the time axis, the iterator past its end, the manifest's imaging flag, a dataset without a
 * spectrum data arrays member, a spectrum metadata member without rows, a peaks member with an extra array that is not
 * an ion mobility array, and a source whose close fails on its own.
 *
 * <p>The lookups run on current small.mzpeak. The other cases read it, or has_uv, from memory
 * ({@link TestSources#memory(String, Map)}) with an edited manifest or a member written with parquet-java into a
 * {@link TempDir}, since no fixture has such a manifest or member.
 */
class MzPeakReaderEdgeCasesTest {

    private static final String SMALL = "small.mzpeak";
    private static final String METADATA = "spectra_metadata.parquet";
    private static final String DATA = "spectra_data.parquet";
    private static final String PEAKS = "spectra_peaks.parquet";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Consumer<ObjectNode> NO_EDIT = index -> { };

    @TempDir
    Path tmp;

    // ---- lookups ----------------------------------------------------------------------------------------------

    @Test
    void lookupsOfAnAbsentIndexIdOrScanNumberAreEmpty() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(SMALL))) {
            SpectrumDescription last = reader.metadataAt(reader.size() - 1);
            assertThat(reader.size()).isEqualTo(48);
            assertThat(reader.getMetadata(last.index())).contains(last);
            assertThat(reader.getMetadata(48)).isEmpty();
            assertThat(reader.getMetadata(-1)).isEmpty();

            assertThat(reader.getSpectrumById(last.id())).map(Spectrum::index).contains(last.index());
            assertThat(reader.getSpectrumById("controllerType=0 controllerNumber=1 scan=999")).isEmpty();

            int scan = org.mzpeak.model.NativeId.scanNumber(last.id()).orElseThrow();
            assertThat(reader.getSpectrumByScanNumber(scan)).map(Spectrum::index).contains(last.index());
            assertThat(reader.getSpectrumByScanNumber(999)).isEmpty();
        }
    }

    /**
     * The nearest-time lookup: before the first and after the last retention time it gives the first and last
     * spectrum, between two it gives the nearer one, and a non-finite time finds nothing.
     */
    @Test
    void theNearestTimeLookupClampsAtTheEndsAndPicksTheNearerNeighbour() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(SMALL))) {
            List<SpectrumDescription> byTime = reader.metadata().stream()
                    .sorted(Comparator.comparingDouble(SpectrumDescription::retentionTime)).toList();
            SpectrumDescription first = byTime.get(0);
            SpectrumDescription second = byTime.get(1);
            SpectrumDescription last = byTime.get(byTime.size() - 1);
            double gap = second.retentionTime() - first.retentionTime();
            assertThat(gap).isPositive();

            assertThat(reader.getSpectrumByTime(first.retentionTime() - 1)).map(Spectrum::index)
                    .contains(first.index());
            assertThat(reader.getSpectrumByTime(last.retentionTime() + 1)).map(Spectrum::index)
                    .contains(last.index());
            assertThat(reader.getSpectrumByTime(first.retentionTime() + gap / 4)).map(Spectrum::index)
                    .contains(first.index());
            assertThat(reader.getSpectrumByTime(second.retentionTime() - gap / 4)).map(Spectrum::index)
                    .contains(second.index());
            assertThat(reader.getSpectrumByTime(Double.NaN)).isEmpty();
            assertThat(reader.getSpectrumByTime(Double.POSITIVE_INFINITY)).isEmpty();
        }
    }

    @Test
    void theIteratorThrowsPastItsLastSpectrum() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(SMALL))) {
            Iterator<Spectrum> it = reader.iterator();
            long count = 0;
            while (it.hasNext()) {
                assertThat(it.next().index()).isEqualTo(reader.metadataAt((int) count).index());
                count++;
            }
            assertThat(count).isEqualTo(48);
            assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
        }
    }

    // ---- edited manifests -------------------------------------------------------------------------------------

    /** The manifest's imaging flag alone makes a dataset an imaging one, without any scan settings. */
    @Test
    void theManifestImagingFlagMarksADatasetWithoutScanSettingsAsImaging() {
        try (MzPeakReader plain = MzPeakReader.open(Fixtures.current(SMALL))) {
            assertThat(plain.isImaging()).isFalse();
        }
        MzPeakSource flagged = edited(Fixtures.current(SMALL), index ->
                ((ObjectNode) index.path("metadata")).putObject("imaging").put("is_imaging", true), Map.of());
        try (MzPeakReader reader = MzPeakReader.open(flagged)) {
            assertThat(reader.manifest().isImaging()).isTrue();
            assertThat(reader.isImaging()).isTrue();
            assertThat(reader.scanSettings()).isEmpty();
        }
    }

    /**
     * Without a spectrum data arrays member, a spectrum read from both members holds its centroids in its m/z and
     * intensity, as {@link SignalMode#CENTROID} gives them, and {@link MzPeakReader#extractSignal} gives empty
     * slices.
     */
    @Test
    void withoutADataArraysMemberSpectraHoldTheirCentroidsAndExtractSignalIsEmpty() {
        MzPeakSource peaksOnly = edited(Fixtures.current(SMALL), withoutMember(DATA), Map.of());
        try (MzPeakReader reader = MzPeakReader.open(peaksOnly);
             MzPeakReader centroids = MzPeakReader.open(Fixtures.current(SMALL),
                     Options.defaults().withSignalMode(SignalMode.CENTROID))) {
            assertThat(reader.manifest().find(MzPeakManifest.EntityType.SPECTRUM,
                    MzPeakManifest.DataKind.DATA_ARRAYS)).isEmpty();
            assertThat(reader.size()).isEqualTo(48);
            int withPeaks = 0;
            for (int i = 0; i < reader.size(); i++) {
                Spectrum s = reader.getSpectrumAt(i);
                Spectrum expected = centroids.getSpectrumAt(i);
                assertThat(s.mz()).as("m/z of %d", i).containsExactly(expected.mz());
                assertThat(s.intensity()).as("intensity of %d", i).containsExactly(expected.intensity());
                assertThat(s.peaks()).as("peaks of %d", i).isEqualTo(expected.peaks());
                assertThat(s.mz()).as("m/z of %d are its peaks", i)
                        .containsExactly(s.peaks().stream().mapToDouble(CentroidPeak::mz).toArray());
                if (!s.peaks().isEmpty()) {
                    withPeaks++;
                }
            }
            assertThat(withPeaks).isPositive();

            List<SignalSlice> slices = reader.extractSignal(SignalQuery.all()).toList();
            assertThat(slices).extracting(SignalSlice::index)
                    .containsExactlyElementsOf(reader.metadata().stream().map(SpectrumDescription::index).toList());
            assertThat(slices).allSatisfy(slice -> {
                assertThat(slice.size()).isZero();
                assertThat(slice.ionMobility()).isNull();
            });
        }
    }

    /**
     * A spectrum metadata member with no rows and neither the packed nor the split shape is a legal empty member: the
     * reader has no spectra, no time range, and its lookups by time and its iterator find nothing.
     */
    @Test
    void aSpectrumMetadataMemberWithoutRowsGivesAReaderWithoutSpectra() {
        MessageType schema = MessageTypeParser.parseMessageType("message m { optional int64 note; }");
        MzPeakSource empty = edited(Fixtures.current(SMALL), NO_EDIT,
                Map.of(METADATA, parquet(schema, Map.of())));
        try (MzPeakReader reader = MzPeakReader.open(empty)) {
            assertThat(reader.size()).isZero();
            assertThat(reader.metadata()).isEmpty();
            assertThat(reader.observedTimeRange()).isEmpty();
            assertThat(reader.getSpectrumByTime(0.0)).isEmpty();
            assertThat(reader.iterator().hasNext()).isFalse();
            assertThatThrownBy(() -> reader.iterator().next()).isInstanceOf(NoSuchElementException.class);
        }
    }

    // ---- range queries ----------------------------------------------------------------------------------------

    private static final MessageType PEAKS_WITH_EXTRAS = MessageTypeParser.parseMessageType("""
            message m {
              optional group point {
                optional int64 spectrum_index (INTEGER(64,false));
                optional double mz;
                optional float intensity;
                optional double charge;
                optional double im;
              }
            }""");

    /**
     * A peaks member whose first extra array is a charge array (MS:1000516) and whose second is an ion mobility array
     * (MS:1003006): the ion mobility filter of {@link MzPeakReader#queryPeaks} reads the second, and a spectrum's
     * slice carries it as its ion mobility.
     */
    @Test
    void theIonMobilityOfAPeakSliceIsTheIonMobilityExtraAfterAnotherExtra() {
        String index = "{\"prefix\": \"point\", \"entries\": ["
                + entry("point.spectrum_index", "index", "MS:1003870", 0) + ", "
                + entry("point.mz", "point", "MS:1000514", 1) + ", "
                + entry("point.intensity", "point", "MS:1000515", null) + ", "
                + entry("point.charge", "point", "MS:1000516", null) + ", "
                + entry("point.im", "point", "MS:1003006", null) + "]}";
        SimpleGroupFactory rows = new SimpleGroupFactory(PEAKS_WITH_EXTRAS);
        Group[] peaks = {
                peak(rows, 0, 100.0, 10f, 1.0, 0.8),
                peak(rows, 0, 200.0, 20f, 2.0, 1.2),
                peak(rows, 0, 300.0, 30f, 3.0, 0.9)};
        MzPeakSource source = edited(Fixtures.current(SMALL), NO_EDIT,
                Map.of(PEAKS, parquet(PEAKS_WITH_EXTRAS, Map.of(SignalEntity.SPECTRUM.arrayIndexKey(), index),
                        peaks)));
        try (MzPeakReader reader = MzPeakReader.open(source)) {
            Spectrum s = reader.getSpectrum(0).orElseThrow();
            assertThat(s.peakArrays().keySet()).containsExactly("MS:1000516", "MS:1003006");

            SignalSlice whole = reader.queryPeaks(SignalQuery.indexRange(0, 0)).toList().get(0);
            assertThat(whole.mz()).containsExactly(100.0, 200.0, 300.0);
            assertThat(whole.intensity()).containsExactly(10.0, 20.0, 30.0);
            assertThat(whole.ionMobility()).containsExactly(0.8, 1.2, 0.9);

            List<SignalSlice> filtered =
                    reader.queryPeaks(SignalQuery.indexRange(0, 0).withIonMobility(0.85, 1.0)).toList();
            assertThat(filtered).singleElement().satisfies(slice -> {
                assertThat(slice.index()).isZero();
                assertThat(slice.mz()).containsExactly(300.0);
                assertThat(slice.intensity()).containsExactly(30.0);
                assertThat(slice.ionMobility()).containsExactly(0.9);
            });
        }
    }

    // ---- close ------------------------------------------------------------------------------------------------

    /** A source close that fails while nothing else does is thrown as it is, with nothing suppressed. */
    @Test
    void aSourceCloseFailureAloneIsThrownAsIs() {
        IllegalStateException failure = new IllegalStateException("source close failed");
        TestSources.Memory memory = TestSources.memory(Fixtures.current(SMALL));
        int[] closes = {0};
        MzPeakSource failing = new MzPeakSource() {
            @Override
            public byte[] readManifestBytes() {
                return memory.readManifestBytes();
            }

            @Override
            public InputFile inputFile(String name) {
                return memory.inputFile(name);
            }

            @Override
            public String describe() {
                return memory.describe();
            }

            @Override
            public void close() {
                closes[0]++;
                memory.close();
                throw failure;
            }
        };
        MzPeakReader reader = MzPeakReader.open(failing);
        assertThat(reader.getSpectrumAt(0).mz()).isNotEmpty();

        assertThatThrownBy(reader::close).isSameAs(failure);
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(closes[0]).isEqualTo(1);
        reader.close();
        assertThat(closes[0]).isEqualTo(1);
        assertThatThrownBy(() -> reader.getSpectrumAt(0)).isInstanceOf(IllegalStateException.class)
                .hasMessage("the reader is closed");
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    /** The members of {@code dataset} in memory, with the manifest edited by {@code edit} and {@code replaced} set. */
    static MzPeakSource edited(Path dataset, Consumer<ObjectNode> edit, Map<String, byte[]> replaced) {
        TestSources.Memory original = TestSources.memory(dataset);
        Map<String, byte[]> members = new TreeMap<>();
        for (String name : original.names()) {
            members.put(name, original.member(name));
        }
        try {
            ObjectNode index = (ObjectNode) MAPPER.readTree(members.get(TestSources.MANIFEST));
            edit.accept(index);
            members.put(TestSources.MANIFEST, MAPPER.writeValueAsBytes(index));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Map.Entry<String, byte[]> member : replaced.entrySet()) {
            assertThat(members).as("member %s", member.getKey()).containsKey(member.getKey());
            members.put(member.getKey(), member.getValue());
        }
        return TestSources.memory("edited:" + dataset, members);
    }

    /** Removes the manifest entry of the member {@code name}, which must be listed once. */
    static Consumer<ObjectNode> withoutMember(String name) {
        return index -> {
            ArrayNode files = (ArrayNode) index.path("files");
            int removed = 0;
            for (int i = files.size() - 1; i >= 0; i--) {
                JsonNode file = files.get(i);
                if (name.equals(file.path("name").asText())) {
                    files.remove(i);
                    removed++;
                }
            }
            assertThat(removed).as("manifest entries of %s", name).isEqualTo(1);
        };
    }

    /** The bytes of a Parquet file of {@code rows} with {@code footer} as its key-value metadata. */
    private byte[] parquet(MessageType schema, Map<String, String> footer, Group... rows) {
        Path file = tmp.resolve("member-" + System.nanoTime() + ".parquet");
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(schema)
                .withExtraMetaData(footer)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Group peak(SimpleGroupFactory rows, long spectrum, double mz, float intensity, double charge,
                              double im) {
        Group row = rows.newGroup();
        row.addGroup("point").append("spectrum_index", spectrum).append("mz", mz).append("intensity", intensity)
                .append("charge", charge).append("im", im);
        return row;
    }

    private static String entry(String path, String format, String arrayType, Integer rank) {
        return "{\"path\": \"" + path + "\", \"buffer_format\": \"" + format + "\", \"array_type\": \"" + arrayType
                + "\", \"sorting_rank\": " + rank + ", \"transform\": null, \"buffer_priority\": null,"
                + " \"context\": \"spectrum\"}";
    }
}
