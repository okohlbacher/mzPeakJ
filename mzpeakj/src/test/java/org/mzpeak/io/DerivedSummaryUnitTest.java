package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.MzPeakWriter.SignalEncoding;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The base peak intensity (MS:1000505) and total ion current (MS:1000285) the writer derives from a spectrum's payload
 * are written to columns whose unit is MS:1000131 (number of detector counts, {@link ColumnTable}), so they are derived
 * only when the payload's intensity unit is MS:1000131 or not given; a payload in another unit, such as MS:1000132
 * (percent of base peak), leaves them null. The values derived from m/z (lowest and highest observed m/z, base peak
 * m/z) are written whatever the intensity unit. A profile spectrum's unit is its {@link Spectrum#intensityUnit()}, a
 * centroid spectrum's with peaks its {@link Spectrum#peakIntensityUnit()}.
 *
 * <p>Oracle: spectra built by hand, in percent, in counts and without unit, written in both metadata layouts with
 * point, Numpress SLOF and Numpress PIC signal; the values are worked out by hand.
 */
class DerivedSummaryUnitTest {

    private static final String PERCENT = "MS:1000132";
    private static final String COUNTS = "MS:1000131";
    /** The summary columns in the order of {@link SpectrumSummary.Values}, by their name in each layout. */
    private static final List<String> SPLIT_COLUMNS = List.of("lowest_observed_mz", "highest_observed_mz",
            "base_peak_mz", "base_peak_intensity", "total_ion_current");
    private static final List<String> PACKED_COLUMNS = List.of("MS_1000528_lowest_observed_mz_unit_MS_1000040",
            "MS_1000527_highest_observed_mz_unit_MS_1000040", "MS_1000504_base_peak_mz_unit_MS_1000040",
            "MS_1000505_base_peak_intensity_unit_MS_1000131", "MS_1000285_total_ion_current_unit_MS_1000131");

    static Stream<Arguments> writes() {
        List<Arguments> out = new ArrayList<>();
        for (MetadataLayout layout : MetadataLayout.values()) {
            out.add(Arguments.of(layout, "point", Options.defaults().withMetadataLayout(layout)));
            out.add(Arguments.of(layout, "SLOF", Options.defaults().withMetadataLayout(layout)
                    .withSignalEncoding(SignalEncoding.NUMPRESS)));
            out.add(Arguments.of(layout, "PIC", Options.defaults().withMetadataLayout(layout)
                    .withSignalEncoding(SignalEncoding.NUMPRESS).withNumpressIntensity(NumpressIntensityEncoding.PIC)));
        }
        return out.stream();
    }

    /**
     * A profile and a centroid spectrum in percent are written with null base peak intensity and total ion current
     * cells but with their observed m/z range and base peak m/z; the same spectra in counts and without unit with all
     * five.
     */
    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("writes")
    void onlyACountsPayloadGivesTheDerivedBasePeakIntensityAndTotalIonCurrent(MetadataLayout layout, String signal,
                                                                             Options options, @TempDir Path tmp) {
        List<Spectrum> spectra = List.of(
                profile(0, PERCENT), centroid(1, PERCENT),
                profile(2, COUNTS), centroid(3, COUNTS),
                profile(4, null), centroid(5, null));
        Path out = tmp.resolve("out.mzpeak");

        MzPeakWriter.writeDirectory(out, spectra, List.of(), List.of(), List.of(), FileMetadata.EMPTY, options);

        List<String> percent = Arrays.asList("100.0", "102.0", "101.0", null, null);
        List<String> counts = List.of("100.0", "102.0", "101.0", "50.0", "80.0");
        assertThat(summaryCells(out, layout)).containsExactly(percent, percent, counts, counts, counts, counts);
    }

    /** {@link SpectrumSummary#derive} itself: the five values of counts, the m/z ones alone of percent. */
    @Test
    void deriveLeavesTheIntensityValuesOfAnotherUnitNull() {
        SpectrumSummary.Values mzOnly = new SpectrumSummary.Values(100.0, 102.0, 101.0, null, null);
        SpectrumSummary.Values all = new SpectrumSummary.Values(100.0, 102.0, 101.0, 50.0, 80.0);
        assertThat(SpectrumSummary.derive(profile(0, PERCENT))).isEqualTo(mzOnly);
        assertThat(SpectrumSummary.derive(centroid(0, PERCENT))).isEqualTo(mzOnly);
        assertThat(SpectrumSummary.derive(profile(0, COUNTS))).isEqualTo(all);
        assertThat(SpectrumSummary.derive(profile(0, null))).isEqualTo(all);
        assertThat(SpectrumSummary.derive(centroid(0, COUNTS))).isEqualTo(all);
        assertThat(SpectrumSummary.derive(centroid(0, null))).isEqualTo(all);
        // the unit of the payload decides: a centroid spectrum's peaks, not its (empty) arrays
        Spectrum peaksInPercent = new Spectrum(description(0, SignalContinuity.CENTROID), new double[0],
                new double[0], centroid(0, null).peaks(), null, null, COUNTS, PERCENT);
        assertThat(SpectrumSummary.derive(peaksInPercent)).isEqualTo(mzOnly);
    }

    /** The five summary cells of each row of the spectra_metadata member of {@code dataset}, null when absent. */
    private static List<List<String>> summaryCells(Path dataset, MetadataLayout layout) {
        List<List<String>> out = new ArrayList<>();
        ParquetGroups.forEach(dataset.resolve("spectra_metadata.parquet"), row -> {
            Group spectrum = layout == MetadataLayout.PACKED ? row.getGroup("spectrum", 0) : row;
            List<String> cells = new ArrayList<>();
            for (String column : layout == MetadataLayout.PACKED ? PACKED_COLUMNS : SPLIT_COLUMNS) {
                Double value = ParquetGroups.optDouble(spectrum, column);
                cells.add(value == null ? null : value.toString());
            }
            out.add(cells);
        });
        return out;
    }

    /** A profile MS1 spectrum numbered {@code index} with intensities 10, 50, 20 at m/z 100-102 in {@code unit}. */
    private static Spectrum profile(long index, String unit) {
        return new Spectrum(description(index, SignalContinuity.PROFILE), new double[] {100.0, 101.0, 102.0},
                new double[] {10.0, 50.0, 20.0}, List.of(), null, null, unit, null);
    }

    /** A centroid MS1 spectrum numbered {@code index} with the same values as peaks only, in {@code unit}. */
    private static Spectrum centroid(long index, String unit) {
        return new Spectrum(description(index, SignalContinuity.CENTROID), new double[0], new double[0],
                List.of(new CentroidPeak(100.0, 10.0, 0), new CentroidPeak(101.0, 50.0, 1),
                        new CentroidPeak(102.0, 20.0, 2)), null, null, null, unit);
    }

    private static SpectrumDescription description(long index, SignalContinuity continuity) {
        return new SpectrumDescription(index, "scan=" + (index + 1), 1, index + 1.0, Polarity.POSITIVE, continuity,
                0, 0, List.of(), List.of(), List.of());
    }
}
