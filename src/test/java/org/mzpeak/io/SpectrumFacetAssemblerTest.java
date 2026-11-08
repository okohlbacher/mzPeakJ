package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpectrumFacetAssembler} paths the fixtures do not take: the retention time of a spectrum without a
 * {@code time} cell when it has several scans, and a row of a facet that is not a spectrum metadata facet.
 */
class SpectrumFacetAssemblerTest {

    /**
     * A spectrum without a retention time, written with four scans whose start times are 5, none, 3 and 4 minutes,
     * reads back with the smallest finite start time, 3, as its retention time, and with its scans in order.
     */
    @Test
    void withoutATimeTheRetentionTimeIsTheEarliestFiniteScanStartTime(@TempDir Path tmp) {
        Spectrum spectrum;
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current("small.mzpeak"))) {
            spectrum = reader.getSpectrumAt(0);
        }
        SpectrumDescription d = spectrum.description();
        assertThat(d.scans()).hasSize(1);
        ScanEvent scan = d.scans().get(0);
        List<ScanEvent> scans = List.of(at(scan, 5.0), at(scan, Double.NaN), at(scan, 3.0), at(scan, 4.0));
        SpectrumDescription timeless = new SpectrumDescription(d.index(), d.id(), d.msLevel(), Double.NaN,
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), scans, d.precursors(),
                d.parameters());
        Path out = tmp.resolve("timeless.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(new Spectrum(timeless, spectrum.mz(), spectrum.intensity(),
                spectrum.peaks())));

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            SpectrumDescription back = reader.metadataAt(0);
            assertThat(back.retentionTime()).isEqualTo(3.0);
            assertThat(back.scans()).extracting(ScanEvent::startTime).containsExactly(5.0, Double.NaN, 3.0, 4.0);
            assertThat(reader.observedTimeRange()).contains(new org.mzpeak.model.DoubleRange(3.0, 3.0));
        }
    }

    @Test
    void aRowOfAnotherFacetIsRejected() {
        MessageType schema = MessageTypeParser.parseMessageType("""
                message chromatogram {
                  optional int64 index;
                  optional binary id (STRING);
                }""");
        Group group = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("id", "TIC");
        FacetRow row = new FacetRow(Facet.CHROMATOGRAM, group, ColumnResolver.packed(schema, Facet.CHROMATOGRAM));
        SpectrumFacetAssembler assembler = new SpectrumFacetAssembler();

        assertThatThrownBy(() -> assembler.accept(0, row))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("not a spectrum metadata facet: CHROMATOGRAM");
        assertThat(assembler.build().descriptions()).isEmpty();
    }

    /** {@code scan} with the start time {@code startTime}. */
    private static ScanEvent at(ScanEvent scan, double startTime) {
        return new ScanEvent(startTime, scan.injectionTime(), scan.filterString(), scan.presetScanConfiguration(),
                scan.instrumentConfigurationRef(), scan.spectrumReference(), scan.scanWindows(), scan.parameters());
    }
}
