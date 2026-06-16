package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that per-spectrum/scan/selected-ion parameters round-trip through the writer.
 */
class ParamsRoundTripTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void spectrumAndScanParamsRoundTrip(@TempDir Path tmp) {
        // Read the fixture, capturing original params
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        List<Param> originalSpectrumParams;
        List<Param> originalScanParams;
        SelectedIon originalIon;

        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) spectra.add(s);
            chromatograms = new ArrayList<>(r.chromatograms());

            SpectrumDescription d2 = r.getMetadata(2).orElseThrow();
            originalSpectrumParams = d2.parameters();
            originalScanParams = d2.scans().isEmpty() ? List.of() : d2.scans().get(0).parameters();
            originalIon = d2.primaryPrecursor().primaryIon();
        }

        // Write and re-read
        Path out = tmp.resolve("params.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, chromatograms);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            SpectrumDescription d2 = r.getMetadata(2).orElseThrow();

            // Spectrum-level params preserved
            assertThat(d2.parameters()).hasSize(originalSpectrumParams.size());
            for (int i = 0; i < originalSpectrumParams.size(); i++) {
                assertThat(d2.parameters().get(i).name()).isEqualTo(originalSpectrumParams.get(i).name());
                assertThat(d2.parameters().get(i).accession()).isEqualTo(originalSpectrumParams.get(i).accession());
            }

            // Scan params preserved
            if (!originalScanParams.isEmpty() && !d2.scans().isEmpty()) {
                assertThat(d2.scans().get(0).parameters()).hasSize(originalScanParams.size());
            }

            // Selected-ion params preserved
            SelectedIon ion = d2.primaryPrecursor().primaryIon();
            assertThat(ion).isNotNull();
            assertThat(ion.mz()).isCloseTo(originalIon.mz(), org.assertj.core.data.Offset.offset(1e-6));
            assertThat(ion.parameters()).hasSize(originalIon.parameters().size());
        }
    }

    @Test
    void paramFieldsRoundTripFully(@TempDir Path tmp) {
        // Use the fixture's actual activation params (name, accession, float value, unit)
        List<Spectrum> spectra = new ArrayList<>();
        List<Chromatogram> chromatograms;
        Param originalCollisionEnergy;

        try (MzPeakReader r = MzPeakReader.open(FIXTURE)) {
            for (Spectrum s : r) spectra.add(s);
            chromatograms = new ArrayList<>(r.chromatograms());
            originalCollisionEnergy = r.getMetadata(2).orElseThrow()
                    .primaryPrecursor().activation().parameters().stream()
                    .filter(p -> "MS:1000045".equals(p.accession()))
                    .findFirst().orElse(null);
        }

        Path out = tmp.resolve("full.mzpeak");
        MzPeakWriter.writeDirectory(out, spectra, chromatograms);

        try (MzPeakReader r = MzPeakReader.open(out)) {
            assertThat(r.size()).isEqualTo(48);
            // Activation params (dissociation method + collision energy) are now written to the
            // precursor.activation facet, so the collision energy must round-trip.
            var activation = r.getMetadata(2).orElseThrow().primaryPrecursor().activation();
            assertThat(activation).isNotNull();
            if (originalCollisionEnergy != null) {
                assertThat(activation.parameters())
                        .as("collision energy (MS:1000045) round-trips via activation")
                        .anyMatch(p -> "MS:1000045".equals(p.accession()));
            }
        }
    }

    @Test
    void booleanAndTypedParamsRoundTrip(@TempDir Path tmp) {
        // A synthetic centroid spectrum carrying params of every value-union arm, including booleans,
        // which previously decoded through the INT32 path and could not survive a BOOLEAN column.
        List<Param> params = List.of(
                new Param("flag on", "MS:9999990", Boolean.TRUE, null),
                new Param("flag off", "MS:9999991", Boolean.FALSE, null),
                new Param("count", "MS:9999992", 42L, null),
                new Param("ratio", "MS:9999993", 1.5, null),
                new Param("label", "MS:9999994", "abc", null));
        SpectrumDescription d = new SpectrumDescription(0L, "scan=1", 1, 1.0,
                Polarity.POSITIVE, SignalContinuity.CENTROID, 1, 0,
                List.of(), List.of(), params);
        Spectrum s = new Spectrum(d, new double[] {100.0}, new double[] {5.0}, List.of());

        Path out = tmp.resolve("bool.mzpeak");
        MzPeakWriter.writeDirectory(out, List.of(s), List.of());

        try (MzPeakReader r = MzPeakReader.open(out)) {
            List<Param> got = r.getMetadata(0).orElseThrow().parameters();
            assertThat(value(got, "MS:9999990")).isEqualTo(Boolean.TRUE);
            assertThat(value(got, "MS:9999991")).isEqualTo(Boolean.FALSE);
            assertThat(value(got, "MS:9999992")).isEqualTo(42L);
            assertThat(value(got, "MS:9999993")).isEqualTo(1.5);
            assertThat(value(got, "MS:9999994")).isEqualTo("abc");
        }
    }

    private static Object value(List<Param> params, String accession) {
        return params.stream()
                .filter(p -> accession.equals(p.accession()))
                .map(Param::value)
                .findFirst().orElse(null);
    }
}
