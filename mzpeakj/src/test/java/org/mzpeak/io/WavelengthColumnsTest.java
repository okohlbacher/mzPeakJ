package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.WavelengthSpectrum;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W11 (D13-12): the wavelength members of a split write follow metadata-tables.md. A term written as a column is not
 * also in the row's {@code parameters} list (§Controlled Vocabulary Terms, "MUST NOT write the same parameter to
 * both", the validator rule MT-DUPPARAM), and every column the wavelength-spectra.md tables do not name carries the
 * {@code opt_} prefix (§Column naming, MT-OPTNAME): the representation is {@code opt_spectrum_representation}, and
 * the wavelength scans have no {@code ion_mobility_value}, {@code ion_mobility_type} or {@code spectrum_reference}
 * column. {@code lambda_max} (wavelength-spectra.md lists MS:1003812) and {@code ion_injection_time} (a child of
 * MS:1000503, which the scan table MAY carry by its term name) are spec names and stay.
 *
 * <p>Oracle: has_uv, the only fixture with wavelength spectra, in both sets. The checks below are the validator's two
 * rules ({@code mzpeak_validate.py} of the 2026-11-22 audit) restated over the written member and its column mapping.
 */
class WavelengthColumnsTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String WAVELENGTH = "wavelength_spectra_metadata.parquet";
    private static final String WAVELENGTH_SCANS = "wavelength_spectra_metadata_scans.parquet";

    /**
     * The column names of wavelength_spectra_metadata that wavelength-spectra.md gives: its listed columns and the
     * term names of the children of MS:1003058 and MS:1000499 it names, with {@code lambda_max} (D13-12).
     */
    private static final Set<String> SPEC_METADATA_COLUMNS = Set.of("index", "id", "time", "data_processing_id",
            "parameters", "number_of_auxiliary_arrays", "auxiliary_arrays", "spectrum_type", "number_of_data_points",
            "highest_observed_wavelength", "lowest_observed_wavelength", "lambda_max", "base_peak_intensity",
            "total_ion_current", "spectrum_title", "spectra_combination");
    /**
     * The column names of wavelength_spectra_metadata_scans that wavelength-spectra.md gives, with the term names of
     * the scan attributes it lists and {@code ion_injection_time} (D13-12).
     */
    private static final Set<String> SPEC_SCAN_COLUMNS = Set.of("source_index", "scan_index",
            "instrument_configuration_id", "scan_start_time", "parameters", "scan_windows", "scan_rate", "dwell_time",
            "filter_string", "preset_scan_configuration", "analyzer_scan_offset", "elution_time",
            "interchannel_delay", "first_column_elution_time", "second_column_elution_time", "ion_injection_time");

    /** The accessions of has_uv's column terms, in the order of their columns. */
    private static final List<String> COLUMN_ORDER = List.of("MS:1000804", "MS:1000127", "MS:1000128", "MS:1000619",
            "MS:1000618", "MS:1000505", "MS:1000285");
    private static final Set<String> MARKERS = Set.of("MS:1000804", "MS:1000127", "MS:1000128");

    @TempDir
    Path tmp;

    static Stream<Path> hasUv() {
        return Stream.of(Fixtures.current(HAS_UV), Fixtures.legacy(HAS_UV));
    }

    /**
     * MT-DUPPARAM: no row has the accession of a mapped column that holds a value in its {@code parameters} list,
     * nor, for a term-marker column, the term in its cell; so no row lists MS:1000804 (the spectrum type), MS:1000619
     * or MS:1000618, whose columns all 520 rows fill.
     */
    @ParameterizedTest
    @MethodSource("hasUv")
    void noWavelengthTermIsBothAColumnAndAParameter(Path fixture) {
        Path out = write(ForwardBackwardCorpusTest.readSource(fixture));
        List<ColumnMapping> mapping = entry(out, WAVELENGTH).columnMapping();
        List<Group> rows = rows(out.resolve(WAVELENGTH));
        assertThat(rows).hasSize(520);
        List<String> duplicates = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Group row = rows.get(i);
            Set<String> listed = new HashSet<>();
            for (Param p : ParquetGroups.readParams(row, "parameters")) {
                listed.add(p.accession());
            }
            assertThat(listed).as("row %d", i).doesNotContain("MS:1000559", "MS:1000804", "MS:1000619",
                    "MS:1000618");
            for (ColumnMapping m : mapping) {
                if (!ParquetGroups.has(row, m.path())) {
                    continue;
                }
                boolean marker = Boolean.TRUE.equals(m.termMarker());
                if (listed.contains(m.accession()) || marker && listed.contains(row.getString(m.path(), 0))) {
                    duplicates.add(i + " " + m.path());
                }
            }
            assertThat(ParquetGroups.optString(row, "spectrum_type")).as("row %d", i).isEqualTo("MS:1000804");
            assertThat(ParquetGroups.optDouble(row, "lowest_observed_wavelength")).as("row %d", i).isNotNull();
            assertThat(ParquetGroups.optDouble(row, "highest_observed_wavelength")).as("row %d", i).isNotNull();
        }
        assertThat(duplicates).isEmpty();
    }

    /**
     * MT-OPTNAME: every column of both wavelength members is a spec name or starts with {@code opt_}. The
     * representation is {@code opt_spectrum_representation}, a term marker mapped to MS:1000525; the scans have no
     * ion mobility or spectrum reference column.
     */
    @Test
    void everyWavelengthColumnIsASpecNameOrOptPrefixed() {
        Path out = write(ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV)));
        List<String> metadata = fieldNames(out, WAVELENGTH);
        List<String> scans = fieldNames(out, WAVELENGTH_SCANS);
        assertThat(metadata).filteredOn(n -> !SPEC_METADATA_COLUMNS.contains(n))
                .containsExactly("opt_spectrum_representation");
        assertThat(scans).filteredOn(n -> !SPEC_SCAN_COLUMNS.contains(n)).isEmpty();
        assertThat(metadata).doesNotContain("spectrum_representation");
        assertThat(scans).doesNotContain("ion_mobility_value", "ion_mobility_type", "spectrum_reference")
                .contains("ion_injection_time");
        assertThat(entry(out, WAVELENGTH).columnAtPath("opt_spectrum_representation")).contains(new ColumnMapping(
                "spectrum representation", "opt_spectrum_representation", "MS:1000525", null, true));
        assertThat(rows(out.resolve(WAVELENGTH))).allSatisfy(row ->
                assertThat(ParquetGroups.optString(row, "opt_spectrum_representation")).isEqualTo("MS:1000128"));
    }

    /**
     * Reading the write gives each wavelength spectrum the Params read from the fixture, now taken from the columns:
     * the title from the list, then the columns in their order. The spectrum type is a term-marker cell, which the
     * reader gives without a name, as it does the representation; every other Param reads back equal. A second write
     * and read keeps that list as it is.
     */
    @ParameterizedTest
    @MethodSource("hasUv")
    void theParamsReadBackFromTheColumns(Path fixture) {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Source first = ForwardBackwardCorpusTest.readSource(write(source));
        List<WavelengthSpectrum> back = first.wavelengthSpectra;
        assertThat(back).hasSize(520);
        for (int i = 0; i < back.size(); i++) {
            List<Param> read = source.wavelengthSpectra.get(i).parameters();
            assertThat(read).as("spectrum %d", i).extracting(Param::accession).containsExactly("MS:1000804",
                    "MS:1000619", "MS:1000618", "MS:1000796", "MS:1000128", "MS:1000505", "MS:1000285");
            assertThat(back.get(i).parameters()).as("spectrum %d", i).containsExactly(read.get(3),
                    new Param(null, "MS:1000804", null, null), read.get(4), read.get(1), read.get(2), read.get(5),
                    read.get(6)).isEqualTo(readBack(read));
        }
        Source again = ForwardBackwardCorpusTest.readSource(write(first));
        for (int i = 0; i < back.size(); i++) {
            assertThat(again.wavelengthSpectra.get(i).parameters()).as("spectrum %d", i)
                    .isEqualTo(back.get(i).parameters());
        }
    }

    /** The reader still finds the representation of a member that names it {@code spectrum_representation}. */
    @Test
    void theReaderKeepsTheOldRepresentationName() {
        assertThat(ColumnTable.byPath(Facet.WAVELENGTH_SPECTRUM, "spectrum_representation"))
                .map(ColumnTable.Column::path).contains("opt_spectrum_representation");
        assertThat(ForwardBackwardCorpusTest.readSource(Fixtures.current(HAS_UV)).wavelengthSpectra)
                .allSatisfy(w -> assertThat(w.parameters()).contains(new Param(null, "MS:1000128", null, null)));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * The Params a split write and read give a wavelength spectrum with {@code params}, each of whose column terms
     * qualifies for its column once: the others in their order, then the column terms in column order (type,
     * representation, lowest and highest observed wavelength, base peak intensity, TIC), the two term markers
     * without a name.
     */
    static List<Param> readBack(List<Param> params) {
        List<Param> out = new ArrayList<>();
        for (Param p : params) {
            if (!COLUMN_ORDER.contains(p.accession())) {
                out.add(p);
            }
        }
        for (String accession : COLUMN_ORDER) {
            for (Param p : params) {
                if (accession.equals(p.accession())) {
                    out.add(MARKERS.contains(accession) ? new Param(null, accession, null, null) : p);
                }
            }
        }
        return out;
    }

    private Path write(Source s) {
        Path out = tmp.resolve("out-" + System.nanoTime() + ".mzpeak");
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, s.wavelengthSpectra, s.scanSettings,
                s.fileMetadata, SPLIT);
        return out;
    }

    private static Entry entry(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return MzPeakManifest.fromSource(dataset).files().stream().filter(e -> e.name().equals(member))
                    .findFirst().orElseThrow();
        }
    }

    private static List<String> fieldNames(Path out, String member) {
        try (MzPeakSource dataset = MzPeakSource.open(out)) {
            return ParquetFooter.read(dataset.inputFile(member)).schema().getFields().stream().map(Type::getName)
                    .toList();
        }
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
