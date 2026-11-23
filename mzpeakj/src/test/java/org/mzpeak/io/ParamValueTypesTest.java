package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.FileMetadata.Software;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W2 (design D13-16; spec metadata-tables.md §The parameters list, schema/param.json): a Param value of every Java
 * type the model admits is written. {@code Long}, {@code Integer}, {@code Short} and {@code Byte} go to the integer
 * slot, {@code Double} and {@code Float} to the float slot, a {@code Collection} or Java array becomes its compact JSON
 * text in a Parquet {@code parameters} list and a JSON array in the JSON metadata (read back as its compact JSON text,
 * the C7 rule); any other type is refused before anything is written, naming the entity and the param.
 *
 * <p>Oracle: current small.unpacked, with params added where a test says so; the JSON texts are written out by hand.
 */
class ParamValueTypesTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REFUSED = ", which mzPeak cannot write: a parameter value is an integer, a float, a"
            + " string, a boolean, or a collection or array of these";

    /**
     * Spectrum and scan params {@code Integer 3}, {@code Short 4}, {@code Byte 5}, {@code Float 1.5f},
     * {@code List.of(1, 2)}, an {@code int[]} and a nested collection read back as {@code 3L}, {@code 4L}, {@code 5L},
     * {@code 1.5} and their JSON texts, in both metadata layouts.
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void spectrumParamValuesOfEveryTypeAreWritten(MetadataLayout layout, @TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Param> params = List.of(
                param("w2 integer", 3),
                param("w2 short", (short) 4),
                param("w2 byte", (byte) 5),
                param("w2 float", 1.5f),
                param("w2 list", List.of(1, 2)),
                param("w2 int array", new int[] {1, 2}),
                param("w2 nested", List.of(List.of("a", true), new double[] {0.5},
                        Arrays.asList(null, 2L, (short) 3, (byte) 4, 2.5f, Double.NaN))));
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        spectra.set(0, withParams(spectra.get(0), params));
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, small.chromatograms, small.wavelengthSpectra, small.scanSettings,
                small.fileMetadata, Options.defaults().withMetadataLayout(layout));

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            SpectrumDescription back = reader.getSpectrumAt(0).description();
            Object[][] expected = {
                    {"w2 integer", 3L}, {"w2 short", 4L}, {"w2 byte", 5L}, {"w2 float", 1.5},
                    {"w2 list", "[1,2]"}, {"w2 int array", "[1,2]"},
                    {"w2 nested", "[[\"a\",true],[0.5],[null,2,3,4,2.5,\"NaN\"]]"}};
            assertThat(back.parameters()).filteredOn(p -> p.name() != null && p.name().startsWith("w2 "))
                    .extracting(Param::name, Param::value)
                    .containsExactly(Arrays.stream(expected).map(e -> tuple(e[0], e[1])).toArray(
                            org.assertj.core.groups.Tuple[]::new));
            assertThat(back.scans().get(0).parameters()).filteredOn(p -> "w2 scan list".equals(p.name()))
                    .extracting(Param::value).containsExactly("[\"x\",1.5]");
        }
    }

    /**
     * A footer software param {@code List.of("a", "b")} is written as the JSON array {@code ["a","b"]} and reads back
     * as {@code "[\"a\",\"b\"]"}; {@code Short}, {@code Byte} and {@code Float} values are JSON numbers and read
     * back as {@code Long} and {@code Double}. A scan settings param holding a list is a JSON array in the manifest
     * and reads back as its JSON text.
     */
    @Test
    void listValuesAreJsonArraysInTheJsonMetadata(@TempDir Path tmp) throws IOException {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Software> software = new ArrayList<>(small.fileMetadata.software());
        software.add(new Software("w2", "1", List.of(param("w2 list", List.of("a", "b")),
                param("w2 short", (short) 4), param("w2 byte", (byte) 5), param("w2 float", 1.5f))));
        FileMetadata meta = withSoftware(small.fileMetadata, software);
        List<ScanSettings> scanSettings = List.of(new ScanSettings("w2 settings",
                List.of(param("w2 array", new String[] {"x", "y"}))));

        Map<String, String> kv = FooterMetadataWriter.serialize(meta);
        JsonNode written = MAPPER.readTree(kv.get("software_list"));
        assertThat(written.get(written.size() - 1).path("parameters").toString()).isEqualTo(
                "[{\"name\":\"w2 list\",\"value\":[\"a\",\"b\"]},{\"name\":\"w2 short\",\"value\":4},"
                        + "{\"name\":\"w2 byte\",\"value\":5},{\"name\":\"w2 float\",\"value\":1.5}]");

        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, small.spectra, small.chromatograms, small.wavelengthSpectra, scanSettings,
                meta);
        JsonNode manifest = MAPPER.readTree(Files.readAllBytes(out.resolve("mzpeak_index.json"))).path("metadata");
        JsonNode manifestSoftware = manifest.path("software_list");
        assertThat(manifestSoftware.get(manifestSoftware.size() - 1).path("parameters").get(0).path("value")
                .toString()).isEqualTo("[\"a\",\"b\"]");
        assertThat(manifest.path("scan_settings_list").get(0).path("parameters").get(0).path("value").toString())
                .isEqualTo("[\"x\",\"y\"]");
        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<Software> back = reader.fileMetadata().software();
            assertThat(back.get(back.size() - 1).parameters()).extracting(Param::name, Param::value).containsExactly(
                    tuple("w2 list", "[\"a\",\"b\"]"), tuple("w2 short", 4L), tuple("w2 byte", 5L),
                    tuple("w2 float", 1.5));
            assertThat(reader.scanSettings()).singleElement().satisfies(s -> assertThat(s.parameters())
                    .extracting(Param::value).containsExactly("[\"x\",\"y\"]"));
        }
    }

    static Stream<Arguments> refusals() {
        Instant at = Instant.parse("2026-11-22T00:00:00Z");
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        Spectrum s0 = small.spectra.get(0);
        String owner = "spectrum 0 (" + s0.description().id() + ")";
        List<Software> software = new ArrayList<>(small.fileMetadata.software());
        software.add(new Software("w2", "1", List.of(new Param(null, "MS:1000000", at, null))));
        return Stream.of(
                Arguments.of("spectrum", spectra(small, withParams(s0, List.of(param("w2 time", at)))),
                        small.chromatograms, small.scanSettings, small.fileMetadata,
                        owner + ": the value of the parameter \"w2 time\" is a java.time.Instant"),
                Arguments.of("list element", spectra(small, withParams(s0, List.of(param("w2 times",
                                List.of(1L, List.of(at)))))), small.chromatograms, small.scanSettings,
                        small.fileMetadata,
                        owner + ": the value of the parameter \"w2 times\" holds a java.time.Instant"),
                Arguments.of("char array", spectra(small, withParams(s0, List.of(param("w2 chars",
                                new char[] {'a'})))), small.chromatograms, small.scanSettings, small.fileMetadata,
                        owner + ": the value of the parameter \"w2 chars\" holds a java.lang.Character"),
                Arguments.of("chromatogram", small.spectra, chromatograms(small, at), small.scanSettings,
                        small.fileMetadata, "chromatogram 0 (" + small.chromatograms.get(0).id()
                                + "): the value of the parameter \"w2 time\" is a java.time.Instant"),
                Arguments.of("software", small.spectra, small.chromatograms, small.scanSettings,
                        withSoftware(small.fileMetadata, software),
                        "software w2: the value of the parameter MS:1000000 is a java.time.Instant"),
                Arguments.of("scan settings", small.spectra, small.chromatograms,
                        List.of(new ScanSettings("w2 settings", List.of(param("w2 time", at)))), small.fileMetadata,
                        "scan settings 0 (w2 settings): the value of the parameter \"w2 time\" is a"
                                + " java.time.Instant"));
    }

    /**
     * A value of another type, given to a spectrum, inside a list, as a {@code char[]}, to a chromatogram, to a
     * software or to scan settings, is refused before the target exists, naming the entity and the param.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void anotherValueTypeIsRefusedBeforeAnythingIsWritten(String name, List<Spectrum> spectra,
                                                          List<Chromatogram> chromatograms,
                                                          List<ScanSettings> scanSettings, FileMetadata meta,
                                                          String message, @TempDir Path tmp) {
        for (boolean archive : new boolean[] {false, true}) {
            Path out = tmp.resolve("out-" + archive + ".mzpeak");
            assertThatThrownBy(() -> {
                if (archive) {
                    MzPeakWriter.writeArchive(out, spectra, chromatograms, List.of(), scanSettings, meta);
                } else {
                    MzPeakWriter.writeDirectory(out, spectra, chromatograms, List.of(), scanSettings, meta);
                }
            }).isInstanceOf(MzPeakException.class).hasMessage(message + REFUSED);
            assertThat(out).doesNotExist();
            assertThat(tmp.toFile().list()).as("nothing written next to the target").isEmpty();
        }
    }

    /**
     * A session refuses such a value in its scan settings or file metadata when it opens, before the target exists,
     * and in a spectrum, a chromatogram or a wavelength spectrum before anything of it is written.
     */
    @Test
    void aSessionRefusesAnotherValueType(@TempDir Path tmp) {
        Instant at = Instant.EPOCH;
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        Path out = tmp.resolve("session.mzpeak");
        List<ScanSettings> badSettings = List.of(new ScanSettings(null, List.of(param("w2 time", at))));
        assertThatThrownBy(() -> MzPeakWriter.openDirectory(out, small.fileMetadata, badSettings, Set.of(),
                Options.defaults())).isInstanceOf(MzPeakException.class)
                .hasMessage("scan settings 0: the value of the parameter \"w2 time\" is a java.time.Instant"
                        + REFUSED);
        List<Software> badSoftware = new ArrayList<>(small.fileMetadata.software());
        badSoftware.add(new Software("w2", "1", List.of(param("w2 time", at))));
        FileMetadata badMeta = withSoftware(small.fileMetadata, badSoftware);
        assertThatThrownBy(() -> MzPeakWriter.openArchive(out, badMeta, List.of(), Set.of(), Options.defaults()))
                .isInstanceOf(MzPeakException.class)
                .hasMessage("software w2: the value of the parameter \"w2 time\" is a java.time.Instant" + REFUSED);
        assertThat(out).doesNotExist();

        Spectrum s0 = small.spectra.get(0);
        try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(out, small.fileMetadata, List.of(),
                s0.arrays().keySet(), Options.defaults())) {
            assertThatThrownBy(() -> session.addSpectrum(withParams(s0, List.of(param("w2 time", at)))))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum 0 (" + s0.description().id()
                            + "): the value of the parameter \"w2 time\" is a java.time.Instant" + REFUSED);
            session.addSpectrum(s0);
            session.addWavelengthSpectrum(new WavelengthSpectrum(0, "w0", 1.0, null, new double[] {200, 210},
                    new double[] {1, 2}, List.of(param("w2 time", at))));
            assertThatThrownBy(session::finish).isInstanceOf(MzPeakException.class)
                    .hasMessage("wavelength spectrum 0 (w0): the value of the parameter \"w2 time\" is a"
                            + " java.time.Instant" + REFUSED);
        }
    }

    static Param param(String name, Object value) {
        return new Param(name, null, value, null);
    }

    /**
     * {@code s} with {@code params} added to its own params and a param {@code "w2 scan list"} with the value
     * {@code List.of("x", 1.5f)} to its first scan.
     */
    static Spectrum withParams(Spectrum s, List<Param> params) {
        SpectrumDescription d = s.description();
        List<Param> all = new ArrayList<>(d.parameters());
        all.addAll(params);
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        ScanEvent scan = scans.get(0);
        List<Param> scanParams = new ArrayList<>(scan.parameters());
        scanParams.add(param("w2 scan list", List.of("x", 1.5f)));
        scans.set(0, new ScanEvent(scan.startTime(), scan.injectionTime(), scan.filterString(),
                scan.presetScanConfiguration(), scan.instrumentConfigurationRef(), scan.spectrumReference(),
                scan.scanWindows(), scanParams));
        SpectrumDescription changed = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans,
                d.precursors(), all);
        return new Spectrum(changed, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                s.intensityUnit(), s.peakIntensityUnit());
    }

    private static List<Spectrum> spectra(Source small, Spectrum first) {
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        spectra.set(0, first);
        return spectra;
    }

    private static List<Chromatogram> chromatograms(Source small, Object value) {
        List<Chromatogram> out = new ArrayList<>(small.chromatograms);
        Chromatogram c = out.get(0);
        List<Param> params = new ArrayList<>(c.parameters());
        params.add(param("w2 time", value));
        out.set(0, new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(), c.intensityUnit(),
                c.arrays(), c.polarity(), c.dataProcessingRef(), params, c.precursors(), c.timeUnit()));
        return out;
    }

    static FileMetadata withSoftware(FileMetadata m, List<Software> software) {
        return new FileMetadata(m.fileDescription(), m.instrumentConfigurations(), m.dataProcessingMethods(),
                m.samples(), software, m.run());
    }
}
