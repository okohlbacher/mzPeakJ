package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatasetWriter} writes only the members its plan names. {@link WritePlanner} always names spectra_data, if
 * need be without a row (both Rust reference readers open it unconditionally); a plan that does not name it gets no
 * spectra_data member and no manifest entry for one, and its other members are written as with it.
 *
 * <p>Oracle: the MS2 spectra of legacy small.unpacked, centroid spectra whose arrays are their peak lists, which the
 * planner sends to spectra_peaks only.
 */
class DatasetWriterTest {

    private static final Path SMALL = Fixtures.legacy("small.unpacked.mzpeak");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void spectraDataIsWrittenOnlyWhenThePlanNamesIt(@TempDir Path tmp) throws IOException {
        Source small = ForwardBackwardCorpusTest.readSource(SMALL);
        List<Spectrum> centroids = new ArrayList<>();
        for (Spectrum s : small.spectra) {
            if (s.description().msLevel() == 2) {
                centroids.add(s);
            }
        }
        WritePlan plan = WritePlanner.plan(centroids, small.chromatograms, List.of(), List.of(), small.fileMetadata,
                Options.defaults());
        assertThat(plan.spectra()).noneMatch(WritePlan.SpectrumEntry::data);
        assertThat(plan.writesSpectraData()).isTrue();
        assertThat(plan.writesSpectraPeaks()).isTrue();
        WritePlan withoutData = new WritePlan(plan.options(), plan.spectra(), plan.chromatograms(),
                plan.wavelengthSpectra(), false, plan.writesSpectraPeaks(), plan.scanSettings(), plan.footer(),
                plan.report());

        Path planned = tmp.resolve("planned.mzpeak");
        DatasetWriter.write(planned, plan);
        Path without = tmp.resolve("without.mzpeak");
        DatasetWriter.write(without, withoutData);

        assertThat(planned.resolve("spectra_data.parquet")).isRegularFile();
        assertThat(without.resolve("spectra_data.parquet")).doesNotExist();
        List<String> plannedMembers = members(planned);
        assertThat(plannedMembers).contains("spectra_data.parquet", "spectra_peaks.parquet");
        List<String> expected = new ArrayList<>(plannedMembers);
        expected.remove("spectra_data.parquet");
        assertThat(members(without)).containsExactlyElementsOf(expected);
        for (String member : expected) {
            assertThat(without.resolve(member)).as(member).hasSameBinaryContentAs(planned.resolve(member));
        }
    }

    /** The names of the manifest's members, in manifest order. */
    private static List<String> members(Path dataset) throws IOException {
        JsonNode manifest = MAPPER.readTree(Files.readAllBytes(dataset.resolve("mzpeak_index.json")));
        List<String> out = new ArrayList<>();
        manifest.path("files").forEach(f -> out.add(f.path("name").asText()));
        return out;
    }
}
