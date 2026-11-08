package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.MessageType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The split metadata layout written by {@link SplitMetadataWriter}, for what the other split writer tests leave out: a
 * spectrum without id, polarity or scan start time, and Params that look like a column's but do not qualify for it
 * (WR-5c): a user Param among a spectrum's Params, a spectrum type with a unit, a summary value that is not a Double
 * and an imaging position that is not a Long. Each stays in its {@code parameters} list. The chromatogram precursor
 * and selected-ion members, written with the spectrum facets' fields, have every column their mapping names.
 *
 * <p>Oracles: current small.unpacked and current has_uv, read with {@link MzPeakReader} and changed where a test says
 * so, written in the split layout, and read back from the written members and with {@link MzPeakReader}.
 */
class SplitMetadataWriterTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String SCANS = "spectra_metadata_scans.parquet";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Spectrum 0 of current small without id and with an unknown polarity, its scan without start time: its
     * spectra_metadata row has neither id nor scan_polarity, its scan row no scan_start_time, and it reads back so.
     */
    @Test
    void aSpectrumWithoutIdPolarityOrScanStartTimeLeavesThoseColumnsNull(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        SpectrumDescription d = spectra.get(0).description();
        ScanEvent scan = d.scans().get(0);
        ScanEvent untimed = new ScanEvent(Double.NaN, scan.injectionTime(), scan.filterString(),
                scan.presetScanConfiguration(), scan.instrumentConfigurationRef(), scan.spectrumReference(),
                scan.scanWindows(), scan.parameters());
        spectra.set(0, with(spectra.get(0), new SpectrumDescription(d.index(), null, d.msLevel(), d.retentionTime(),
                Polarity.UNKNOWN, d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), List.of(untimed),
                d.precursors(), d.parameters())));

        Path out = write(tmp, small, spectra);

        Map<Long, Group> rows = byKey(rows(out.resolve(SPECTRA)), "index");
        assertThat(ParquetGroups.has(rows.get(0L), "id")).isFalse();
        assertThat(ParquetGroups.has(rows.get(0L), "scan_polarity")).isFalse();
        assertThat(ParquetGroups.optString(rows.get(1L), "id")).isEqualTo(spectra.get(1).description().id());
        assertThat(ParquetGroups.optInt(rows.get(1L), "scan_polarity")).isEqualTo(1);
        Map<Long, Group> scans = byKey(rows(out.resolve(SCANS)), "source_index");
        assertThat(ParquetGroups.has(scans.get(0L), "scan_start_time")).isFalse();
        assertThat(ParquetGroups.optString(scans.get(0L), "filter_string")).isEqualTo(scan.filterString());
        assertThat(ParquetGroups.optDouble(scans.get(1L), "scan_start_time"))
                .isEqualTo(spectra.get(1).description().scans().get(0).startTime());

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("current small without id, polarity and scan start time of spectrum 0",
                descriptions(spectra), descriptions(back.spectra));
        assertThat(back.spectra.get(0).description().id()).isNull();
        assertThat(back.spectra.get(0).description().polarity()).isEqualTo(Polarity.UNKNOWN);
        assertThat(back.spectra.get(0).description().scans().get(0).startTime()).isNaN();
    }

    /**
     * Params that do not qualify for their column stay in their {@code parameters} list and leave the column null:
     * on an MS1 spectrum a user Param (no accession) next to a total ion current with a Long value, which still
     * keeps the summary values from being derived; on another a spectrum type with a unit, which still keeps the
     * spectrum type from being derived; and on a scan a position x with a Double value, so that no position column
     * is written. The spectra read back equal.
     */
    @Test
    void paramsThatDoNotQualifyForTheirColumnStayParams(@TempDir Path tmp) {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        Param user = new Param("operator note", null, "rerun", null);
        Param longTic = new Param("total ion current", "MS:1000285", 5L, "MS:1000131");
        spectra.set(0, withParams(spectra.get(0), List.of(user, longTic)));
        Param typeWithUnit = new Param("MS1 spectrum", "MS:1000579", null, "UO:0000010");
        spectra.set(1, withParams(spectra.get(1), List.of(typeWithUnit)));
        Param doubleX = new Param("position x", "IMS:1000050", 4.0, null);
        spectra.set(2, withScanParams(spectra.get(2), List.of(doubleX)));

        Path out = write(tmp, small, spectra);

        Map<Long, Group> rows = byKey(rows(out.resolve(SPECTRA)), "index");
        Group first = rows.get(0L);
        assertThat(ParquetGroups.readParams(first, "parameters")).containsExactly(user, longTic);
        for (String summary : List.of("lowest_observed_mz", "highest_observed_mz", "base_peak_mz",
                "base_peak_intensity", "total_ion_current")) {
            assertThat(ParquetGroups.has(first, summary)).as(summary).isFalse();
        }
        assertThat(ParquetGroups.optString(first, "spectrum_type")).isEqualTo("MS:1000579");
        Group second = rows.get(1L);
        assertThat(ParquetGroups.readParams(second, "parameters")).containsExactly(typeWithUnit);
        assertThat(ParquetGroups.has(second, "spectrum_type")).isFalse();
        assertThat(ParquetGroups.has(second, "total_ion_current")).isTrue();
        List<Group> scans = rows(out.resolve(SCANS));
        assertThat(scans.get(0).getType().containsField("opt_IMS_1000050_position_x")).isFalse();
        Group scan = byKey(scans, "source_index").get(2L);
        assertThat(ParquetGroups.readParams(scan, "parameters")).contains(doubleX);

        Source back = ForwardBackwardCorpusTest.readSource(out);
        DescriptionAssert.assertEqual("current small with Params that do not qualify", descriptions(spectra),
                descriptions(back.spectra));
        assertThat(back.spectra.get(0).description().parameters()).contains(user, longTic);
        assertThat(back.spectra.get(2).description().scans().get(0).parameters()).contains(doubleX);
    }

    /**
     * chromatograms_metadata_precursors and chromatograms_metadata_selected_ions are written with the fields of the
     * spectrum facets (design §11.8): their column mappings name the paths the spectrum facets' mappings name, and
     * each of those paths is a column of the member. Oracle: current has_uv.
     */
    @Test
    void theChromatogramFacetMembersHaveEveryColumnTheirMappingNames(@TempDir Path tmp) throws IOException {
        Source hasUv = ForwardBackwardCorpusTest.readSource(Fixtures.current("has_uv.mzpeak"));
        Path out = write(tmp, hasUv, hasUv.spectra);

        JsonNode manifest = MAPPER.readTree(Files.readAllBytes(out.resolve("mzpeak_index.json")));
        Map<String, String> facets = Map.of(
                "chromatograms_metadata_precursors.parquet", "spectra_metadata_precursors.parquet",
                "chromatograms_metadata_selected_ions.parquet", "spectra_metadata_selected_ions.parquet");
        for (Map.Entry<String, String> facet : facets.entrySet()) {
            List<String> paths = mappedPaths(manifest, facet.getKey());
            assertThat(paths).as("mapped paths of %s", facet.getKey()).isNotEmpty()
                    .isEqualTo(mappedPaths(manifest, facet.getValue()));
            MessageType schema;
            try (MzPeakSource dataset = MzPeakSource.open(out)) {
                schema = ParquetFooter.read(dataset.inputFile(facet.getKey())).schema();
            }
            for (String path : paths) {
                assertThat(schema.containsPath(path.split("\\."))).as("%s has %s", facet.getKey(), path).isTrue();
            }
        }
    }

    /** The paths of the column mapping of the manifest entry named {@code name}, in mapping order. */
    private static List<String> mappedPaths(JsonNode manifest, String name) {
        for (JsonNode entry : manifest.path("files")) {
            if (name.equals(entry.path("name").asText())) {
                List<String> out = new ArrayList<>();
                entry.path("column_mapping").forEach(row -> out.add(row.path("path").asText()));
                return out;
            }
        }
        throw new AssertionError("no manifest entry " + name);
    }

    private static Path write(Path tmp, Source source, List<Spectrum> spectra) {
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);
        return out;
    }

    private static Spectrum withParams(Spectrum s, List<Param> params) {
        SpectrumDescription d = s.description();
        return with(s, new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), d.precursors(), params));
    }

    /** {@code s} with {@code extra} appended to the Params of its first scan. */
    private static Spectrum withScanParams(Spectrum s, List<Param> extra) {
        SpectrumDescription d = s.description();
        ScanEvent e = d.scans().get(0);
        List<Param> params = new ArrayList<>(e.parameters());
        params.addAll(extra);
        List<ScanEvent> scans = new ArrayList<>(d.scans());
        scans.set(0, new ScanEvent(e.startTime(), e.injectionTime(), e.filterString(), e.presetScanConfiguration(),
                e.instrumentConfigurationRef(), e.spectrumReference(), e.scanWindows(), params));
        return with(s, new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans, d.precursors(),
                d.parameters()));
    }

    private static Spectrum with(Spectrum s, SpectrumDescription d) {
        return new Spectrum(d, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
    }

    private static List<SpectrumDescription> descriptions(List<Spectrum> spectra) {
        List<SpectrumDescription> out = new ArrayList<>(spectra.size());
        for (Spectrum s : spectra) {
            out.add(s.description());
        }
        return out;
    }

    private static Map<Long, Group> byKey(List<Group> rows, String key) {
        Map<Long, Group> out = new HashMap<>();
        for (Group row : rows) {
            out.putIfAbsent(row.getLong(key, 0), row);
        }
        return out;
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
