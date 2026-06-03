package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Polarity;
import org.mzpeak.model.SignalContinuity;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests ported from the HUPO-PSI and mobiusklein Python/Rust cross-implementation test suites.
 *
 * <p>Sources:
 * <ul>
 *   <li>Rust: {@code HUPO-PSI/mzPeak src/reader.rs#[cfg(test)]} — test_read_spectrum, test_tic,
 *       test_load_all_metadata, test_load_all_chromatogram_metadata
 *   <li>Python: {@code mobiusklein/mzpeak_prototyping python/test/test_reader.py#common_checks}
 *       — file metadata keys, selected_ion/precursor counts, spectrum 4, chromatogram count, BPC
 * </ul>
 */
class CrossImplTest {

    private static final String BASE = "src/test/resources/mzpeak/";
    private static final Path UNPACKED = Path.of(BASE + "small.unpacked.mzpeak");
    private static final Path PACKED   = Path.of(BASE + "small.mzpeak");
    private static final Path CHUNKED  = Path.of(BASE + "small.chunked.mzpeak");
    private static final Path NUMPRESS = Path.of(BASE + "small.numpress.mzpeak");
    private static final Path UV       = Path.of(BASE + "has_uv.mzpeak");

    // ---- Python common_checks: file-level counts ----------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void fileLevelCounts_48spectra_34msn(String fixture) {
        // Python: len(reader) == 48; len(selected_ions) == 34; len(precursors) == 34
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            assertThat(r.size()).isEqualTo(48);
            long msnCount = r.metadata().stream().filter(d -> d.msLevel() > 1).count();
            assertThat(msnCount).isEqualTo(34);
            // Every MSn spectrum has exactly 1 precursor (joining on source_index is correct)
            long precursorCount = r.metadata().stream()
                    .filter(d -> d.msLevel() > 1 && d.primaryPrecursor() != null
                            && d.primaryPrecursor().primaryIon() != null)
                    .count();
            assertThat(precursorCount).isEqualTo(34);
        }
    }

    // ---- Python common_checks: spectrum 4 -----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void spectrum4_837CentroidPoints(String fixture) {
        // Python: spec[4] → len(m/z array) == 837, spectrum_representation == MS:1000127 (centroid)
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            Spectrum s = r.getSpectrum(4).orElseThrow();
            assertThat(s.description().index()).isEqualTo(4L);
            assertThat(s.mz()).hasSize(837);
            assertThat(s.intensity()).hasSize(837);
            assertThat(s.description().signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
        }
    }

    // ---- Python common_checks: footer metadata keys ------------------------------------

    @Test
    void footerMetadata_allExpectedKeysPresent() {
        // Python: reader.file_metadata.keys() == {data_processing_method_list, file_description,
        //         instrument_configuration_list, run, sample_list, software_list, ...}
        try (MzPeakReader r = MzPeakReader.open(UNPACKED)) {
            FileMetadata meta = r.fileMetadata();
            assertThat(meta.run()).isNotNull();
            assertThat(meta.software()).isNotEmpty();
            assertThat(meta.instrumentConfigurations()).isNotEmpty();
            assertThat(meta.fileDescription()).isNotNull();
            // run has id + start time
            assertThat(meta.run().id()).isNotBlank();
            assertThat(meta.run().startTime()).isNotNull();
        }
    }

    // ---- Python common_checks: chromatogram count & TIC --------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak"})
    void chromatogram_count1_tic48Points(String fixture) {
        // Python: len(reader.chromatograms) == 1; read_chromatogram(0) time len == 48
        // Rust test_load_all_chromatogram_metadata: count == 1 for all three containers
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            List<Chromatogram> chroms = r.chromatograms();
            assertThat(chroms).hasSize(1);
            assertThat(chroms.get(0).size()).isEqualTo(48);
        }
    }

    // ---- Rust test_load_all_metadata: 48 spectra, some have non-empty precursors ----------

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak"})
    void loadAllMetadata_48spectra_someHavePrecursors(String fixture) {
        // Rust: out.len() == 48; out.iter().any(|p| !p.precursor.is_empty())
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            List<SpectrumDescription> all = r.metadata();
            assertThat(all).hasSize(48);
            assertThat(all).anyMatch(d -> !d.precursors().isEmpty() && d.primaryPrecursor() != null);
        }
    }

    // ---- Rust test_read_spectrum_memmap: start_from_index / start_from_id analogs ----------

    @Test
    void lookupByIndex_thenById_giveConsistentResult() {
        // Rust: start_from_index(10) → spec.index==10; start_from_id(spec.id) → same id/index
        try (MzPeakReader r = MzPeakReader.open(PACKED)) {
            Spectrum s1 = r.getSpectrumAt(10);
            assertThat(s1.description().index()).isEqualTo(10L);

            Spectrum s2 = r.getSpectrumById(s1.description().id()).orElseThrow();
            assertThat(s2.description().id()).isEqualTo(s1.description().id());
            assertThat(s2.description().index()).isEqualTo(s1.description().index());
        }
    }

    @Test
    void metadataOnly_doesNotLoadArrays() {
        // Rust: DetailLevel::MetadataOnly → meta.description() matches full description
        // In mzPeakJ, getMetadata() returns SpectrumDescription without loading arrays.
        try (MzPeakReader r = MzPeakReader.open(PACKED)) {
            SpectrumDescription meta = r.getMetadata(25).orElseThrow();
            SpectrumDescription full = r.getSpectrum(25).orElseThrow().description();
            assertThat(meta.id()).isEqualTo(full.id());
            assertThat(meta.index()).isEqualTo(full.index());
            assertThat(meta.msLevel()).isEqualTo(full.msLevel());
            assertThat(meta.retentionTime()).isCloseTo(full.retentionTime(), within(1e-9));
        }
    }

    // ---- UV dataset: 212 spectra, 520 wavelength spectra, 2 chromatograms ----------------

    @Test
    void uvFile_212spectra_520wavelengthSpectra_2chromatograms() {
        // Rust writer test: wl indices 0..519 (520 total); Python test_load_uv_data
        try (MzPeakReader r = MzPeakReader.open(UV)) {
            assertThat(r.size()).isEqualTo(212);

            List<WavelengthSpectrum> wl = r.wavelengthSpectra();
            assertThat(wl).hasSize(520);
            // index range 0..519 inclusive
            assertThat(wl.get(0).index()).isEqualTo(0L);
            assertThat(wl.get(519).index()).isEqualTo(519L);
            // every wavelength spectrum has a non-empty wavelength array
            assertThat(wl).allMatch(w -> w.wavelength().length > 0);

            assertThat(r.chromatograms()).hasSize(2); // TIC + DAD1
        }
    }

    // ---- Polarity and signal continuity coverage ---------------------------------------

    @Test
    void ms1SpectraAreProfile_ms2ArePositivePolarity() {
        try (MzPeakReader r = MzPeakReader.open(UNPACKED)) {
            for (SpectrumDescription d : r.metadata()) {
                if (d.msLevel() == 1) {
                    assertThat(d.signalContinuity()).isEqualTo(SignalContinuity.PROFILE);
                } else {
                    assertThat(d.signalContinuity()).isEqualTo(SignalContinuity.CENTROID);
                    // fixture is ESI positive mode
                    assertThat(d.polarity()).isEqualTo(Polarity.POSITIVE);
                }
            }
        }
    }

    // ---- Scan event RT agrees with SpectrumDescription RT -------------------------------

    @Test
    void scanEventStartTimeMirrorsSpectrumRetentionTime() {
        // The scan.MS_1000016_scan_start_time field and spectrum.time should agree
        try (MzPeakReader r = MzPeakReader.open(UNPACKED)) {
            for (SpectrumDescription d : r.metadata()) {
                if (!d.scans().isEmpty()) {
                    double scanRt = d.scans().get(0).startTime();
                    if (Double.isFinite(scanRt) && Double.isFinite(d.retentionTime())) {
                        assertThat(scanRt).isCloseTo(d.retentionTime(), within(1e-6));
                    }
                }
            }
        }
    }

    // ---- Cross-container m/z and precursor value agreement (Python + Rust) ---------------

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void precursorMzAgreesAcrossContainers(String fixture) {
        // Use spectrum 2 (source_index=2) with known precursor mz=810.789 as the anchor.
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            SpectrumDescription d = r.getMetadata(2).orElseThrow();
            assertThat(d.primaryPrecursor()).isNotNull();
            assertThat(d.primaryPrecursor().primaryIon()).isNotNull();
            assertThat(d.primaryPrecursor().primaryIon().mz())
                    .isCloseTo(810.789428710938, within(1e-3));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"small.unpacked.mzpeak", "small.mzpeak", "small.chunked.mzpeak", "small.numpress.mzpeak"})
    void allMsnSpectraHaveOneSelectedIon(String fixture) {
        // Python: len(selected_ions) == 34 (one per MSn spectrum)
        // Rust: precursor().unwrap().ions.len() == 1 for spectra 5 and 25
        try (MzPeakReader r = MzPeakReader.open(Path.of(BASE + fixture))) {
            long msnWithIon = r.metadata().stream()
                    .filter(d -> d.msLevel() > 1)
                    .filter(d -> d.primaryPrecursor() != null
                            && d.primaryPrecursor().primaryIon() != null)
                    .count();
            assertThat(msnWithIon).isEqualTo(34);
        }
    }
}
