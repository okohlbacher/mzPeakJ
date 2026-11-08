package org.mzpeak.io;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SpectrumDescription;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scan start time units of {@link ScanRowDecoder} that {@link ScanStartTimeTest} does not read: millisecond and
 * hour are converted to minutes, and a column mapping without a unit gives the cell as it is. Each case reads current
 * small.mzpeak from memory with the unit of the MS:1000016 column mapping of its scans member edited; the oracle is
 * the start time read from the unedited fixture, whose mapping says minutes.
 */
class ScanRowDecoderTest {

    private static final String SMALL = "small.mzpeak";
    private static final String SCANS = "spectra_metadata_scans.parquet";

    @Test
    void aStartTimeInMillisecondsIsReadInMinutes() {
        assertStartTimes(mapping -> mapping.put("unit", "UO:0000028"), minutes -> minutes / 60_000);
    }

    @Test
    void aStartTimeInHoursIsReadInMinutes() {
        assertStartTimes(mapping -> mapping.put("unit", "UO:0000032"), minutes -> minutes * 60);
    }

    @Test
    void aStartTimeWithoutAUnitIsReadAsItIs() {
        assertStartTimes(mapping -> mapping.remove("unit"), minutes -> minutes);
    }

    /**
     * Reads small.mzpeak with {@code edit} applied to the start time mapping and checks that every scan's start time
     * is {@code expected} of the unedited one, while each spectrum keeps its own retention time.
     */
    private static void assertStartTimes(Consumer<ObjectNode> edit, DoubleUnaryOperator expected) {
        MzPeakSource source = MzPeakReaderEdgeCasesTest.edited(Fixtures.current(SMALL),
                index -> editStartTimeMapping(index, edit), Map.of());
        int checked = 0;
        try (MzPeakReader original = MzPeakReader.open(Fixtures.current(SMALL));
             MzPeakReader reader = MzPeakReader.open(source)) {
            assertThat(reader.size()).isEqualTo(original.size()).isEqualTo(48);
            for (int i = 0; i < reader.size(); i++) {
                SpectrumDescription before = original.metadataAt(i);
                SpectrumDescription after = reader.metadataAt(i);
                assertThat(after.index()).isEqualTo(before.index());
                assertThat(after.retentionTime()).as("retention time of %d", after.index())
                        .isEqualTo(before.retentionTime());
                List<ScanEvent> scans = after.scans();
                assertThat(scans).as("scans of %d", after.index()).hasSameSizeAs(before.scans()).isNotEmpty();
                for (int k = 0; k < scans.size(); k++) {
                    double minutes = before.scans().get(k).startTime();
                    assertThat(minutes).isFinite();
                    assertThat(scans.get(k).startTime()).as("start time of %d", after.index())
                            .isEqualTo(expected.applyAsDouble(minutes));
                    assertThat(scans.get(k).filterString()).isEqualTo(before.scans().get(k).filterString());
                    checked++;
                }
            }
        }
        assertThat(checked).isEqualTo(48);
    }

    /** Applies {@code edit} to the one column_mapping item of the scans member whose accession is MS:1000016. */
    private static void editStartTimeMapping(ObjectNode index, Consumer<ObjectNode> edit) {
        int edited = 0;
        for (JsonNode file : index.path("files")) {
            for (JsonNode m : file.path("column_mapping")) {
                if (SCANS.equals(file.path("name").asText()) && "MS:1000016".equals(m.path("accession").asText())) {
                    assertThat(m.path("unit").asText()).isEqualTo("UO:0000031");
                    edit.accept((ObjectNode) m);
                    edited++;
                }
            }
        }
        assertThat(edited).isEqualTo(1);
    }
}
