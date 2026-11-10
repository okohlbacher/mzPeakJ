package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Param;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the Rust reference reader (mzPeak-reference 2ee2589) needs from a split write, beyond the spectra it already
 * read back equal from 45 mzPeakJ-written files:
 * <ul>
 *   <li>spectra_metadata and wavelength_spectra_metadata carry {@code auxiliary_arrays} and
 *       {@code number_of_auxiliary_arrays}, typed as in the reference's has_uv, an empty list and 0 per row; the Rust
 *       reader projects both for every wavelength spectrum and panics without them;
 *   <li>no {@code parameters} item has an accession but no name (Rust unwraps the name and panics): the
 *       wavelength representation, which the reader gives as {@code Param(null, CURIE)}, goes to its
 *       {@code spectrum_representation} column, and the base peak intensity and TIC follow it as columns, so a
 *       read, write and read of has_uv keeps every Param in its order.
 * </ul>
 * A Param with an accession and no name that no column takes is written to the list unchanged; the readers' own
 * output of every fixture has none left after promotion.
 */
class SplitWriterRustReaderTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String WAVELENGTH = "wavelength_spectra_metadata.parquet";
    private static final String AUXILIARY_ARRAYS = "auxiliary_arrays";
    private static final String AUXILIARY_COUNT = "number_of_auxiliary_arrays";
    private static final String COUNTS = "MS:1000131";
    private static final Param TITLE = new Param("spectrum title", "MS:1000796", "t", null);

    @TempDir
    Path tmp;

    static Stream<Path> fixtures() {
        return Stream.concat(Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy));
    }

    // ---- auxiliary arrays ---------------------------------------------------------------------------------------

    /**
     * Both members end with {@code parameters}, {@code auxiliary_arrays} and {@code number_of_auxiliary_arrays}, the
     * last two typed exactly as in the reference's has_uv, and every row has an empty list and a count of 0.
     */
    @Test
    void bothMetadataMembersCarryEmptyAuxiliaryArraysTypedAsTheReference() {
        Path fixture = Fixtures.current(HAS_UV);
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Path out = write(source.wavelengthSpectra, source, tmp.resolve("out.mzpeak"));

        for (String member : List.of(SPECTRA, WAVELENGTH)) {
            MessageType written = schema(out, member);
            MessageType reference = schema(fixture, member);
            List<String> names = new ArrayList<>();
            for (Type t : written.getFields()) {
                names.add(t.getName());
            }
            assertThat(names.subList(names.size() - 3, names.size())).as(member)
                    .containsExactly("parameters", AUXILIARY_ARRAYS, AUXILIARY_COUNT);
            assertThat(written.getType(AUXILIARY_ARRAYS)).as(member).isEqualTo(reference.getType(AUXILIARY_ARRAYS));
            assertThat(written.getType(AUXILIARY_COUNT)).as(member).isEqualTo(reference.getType(AUXILIARY_COUNT));

            List<Group> rows = rows(out.resolve(member));
            assertThat(rows).as(member).hasSize(member.equals(SPECTRA) ? source.spectra.size()
                    : source.wavelengthSpectra.size()).isNotEmpty();
            for (Group row : rows) {
                assertThat(row.getFieldRepetitionCount(AUXILIARY_ARRAYS)).as(member).isEqualTo(1);
                assertThat(row.getGroup(AUXILIARY_ARRAYS, 0).getFieldRepetitionCount("list")).as(member).isZero();
                assertThat(ParquetGroups.optInt(row, AUXILIARY_COUNT)).as(member).isZero();
            }
        }
    }

    // ---- wavelength representation, base peak intensity and TIC -------------------------------------------------

    /**
     * Legacy and current has_uv, read, written split and read again, give every wavelength spectrum exactly the
     * Params read from the fixture, in the same order.
     */
    @ParameterizedTest
    @MethodSource("hasUv")
    void hasUvWavelengthParamsSurviveAWriteInOrder(Path fixture) {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Path out = write(source.wavelengthSpectra, source, tmp.resolve("out.mzpeak"));
        List<WavelengthSpectrum> back = ForwardBackwardCorpusTest.readSource(out).wavelengthSpectra;
        assertThat(back).hasSize(520).hasSameSizeAs(source.wavelengthSpectra);
        for (int i = 0; i < back.size(); i++) {
            List<Param> expected = source.wavelengthSpectra.get(i).parameters();
            assertThat(expected).as("spectrum %d", i).extracting(Param::accession).containsExactly("MS:1000804",
                    "MS:1000619", "MS:1000618", "MS:1000796", "MS:1000128", "MS:1000505", "MS:1000285");
            assertThat(back.get(i).parameters()).as("spectrum %d", i).isEqualTo(expected);
        }
    }

    static Stream<Path> hasUv() {
        return Stream.of(Fixtures.current(HAS_UV), Fixtures.legacy(HAS_UV));
    }

    /**
     * Constructed wavelength spectra: a nameless representation term goes to its column and leaves the list; a named
     * one too (the name is not written, as for every term-marker column); a finite base peak intensity and TIC in
     * counts go to their columns. A representation with a value, a base peak intensity without unit, a non-finite
     * TIC and a nameless spectrum type (which no column takes) stay in the list unchanged, the spectrum type without
     * its name: the writer passes such a Param on as it is. Reading back gives the list, then the columns.
     */
    @Test
    void theRepresentationAndSummaryValuesOfAWavelengthSpectrumGoToTheirColumns() {
        Param centroid = new Param(null, "MS:1000127", null, null);
        Param profile = new Param("profile spectrum", "MS:1000128", null, null);
        Param bpi = new Param("base peak intensity", "MS:1000505", 2.5, COUNTS);
        Param tic = new Param("total ion current", "MS:1000285", 7.0, COUNTS);
        Param valuedRepresentation = new Param(null, "MS:1000128", "x", null);
        Param unitlessBpi = new Param("base peak intensity", "MS:1000505", 2.5, null);
        Param nanTic = new Param("total ion current", "MS:1000285", Double.NaN, COUNTS);
        Param namelessType = new Param(null, "MS:1000804", null, null);
        List<WavelengthSpectrum> waves = List.of(
                wave(0, List.of(centroid, TITLE)),
                wave(1, List.of(tic, profile, bpi)),
                wave(2, List.of(valuedRepresentation, unitlessBpi, nanTic, namelessType)));
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Path out = write(waves, source, tmp.resolve("out.mzpeak"));

        List<Group> rows = rows(out.resolve(WAVELENGTH));
        assertThat(rows).hasSize(3);
        assertRow(rows.get(0), "MS:1000127", null, null, List.of(TITLE));
        assertRow(rows.get(1), "MS:1000128", 2.5, 7.0, List.of());
        assertRow(rows.get(2), null, null, null, List.of(valuedRepresentation, unitlessBpi, nanTic, namelessType));

        List<WavelengthSpectrum> back = ForwardBackwardCorpusTest.readSource(out).wavelengthSpectra;
        assertThat(back.get(0).parameters()).containsExactly(TITLE, centroid);
        assertThat(back.get(1).parameters()).containsExactly(new Param(null, "MS:1000128", null, null), bpi, tic);
        assertThat(back.get(2).parameters()).containsExactly(valuedRepresentation, unitlessBpi, nanTic,
                namelessType);
    }

    // ---- no nameless parameters ---------------------------------------------------------------------------------

    /**
     * Every fixture, read and written split: no Param item of any metadata member, in any {@code parameters} list
     * or struct, has an accession without a name. The fixtures' own nameless Params, the term markers the reader
     * gives as {@code Param(null, CURIE)}, all go to their columns or repeat an accession of the list, which the
     * reader drops.
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void noWrittenParamHasAnAccessionWithoutAName(Path fixture) throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Path out = write(source.wavelengthSpectra, source, tmp.resolve("out.mzpeak"));
        List<String> nameless = new ArrayList<>();
        int[] items = {0};
        try (Stream<Path> members = Files.list(out)) {
            for (Path member : members.filter(p -> p.getFileName().toString().contains("_metadata")).toList()) {
                for (Group row : rows(member)) {
                    visitParams(row, member.getFileName().toString(), nameless, items);
                }
            }
        }
        assertThat(nameless).as(fixture.toString()).isEmpty();
        assertThat(items[0]).as("Param items of %s", fixture).isPositive();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static void assertRow(Group row, String representation, Double bpi, Double tic, List<Param> params) {
        assertThat(ParquetGroups.optString(row, "spectrum_representation")).isEqualTo(representation);
        assertThat(ParquetGroups.optDouble(row, "base_peak_intensity")).isEqualTo(bpi);
        assertThat(ParquetGroups.optDouble(row, "total_ion_current")).isEqualTo(tic);
        assertThat(ParquetGroups.readParams(row, "parameters")).isEqualTo(params);
    }

    /**
     * Counts the Param items (structs with {@code value}, {@code accession} and {@code name}) below {@code g} in
     * {@code items}, and adds each that has an accession but no name to {@code nameless}.
     */
    private static void visitParams(Group g, String where, List<String> nameless, int[] items) {
        GroupType type = g.getType();
        if (type.containsField("value") && type.containsField("accession") && type.containsField("name")) {
            items[0]++;
            String accession = ParquetGroups.optString(g, "accession");
            if (accession != null && !ParquetGroups.has(g, "name")) {
                nameless.add(where + ": " + accession);
            }
        }
        for (int i = 0; i < type.getFieldCount(); i++) {
            if (!type.getType(i).isPrimitive()) {
                for (int r = 0; r < g.getFieldRepetitionCount(i); r++) {
                    visitParams(g.getGroup(i, r), where, nameless, items);
                }
            }
        }
    }

    private static WavelengthSpectrum wave(int index, List<Param> params) {
        return new WavelengthSpectrum(index, "w" + index, 0.5 + index, null, new double[] {200.0, 210.0},
                new double[] {1.0, 2.0}, params);
    }

    /** Writes the spectra and chromatograms of {@code s} with {@code waves} as a split directory. */
    private static Path write(List<WavelengthSpectrum> waves, Source s, Path out) {
        MzPeakWriter.writeDirectory(out, s.spectra, s.chromatograms, waves, s.scanSettings, s.fileMetadata, SPLIT);
        return out;
    }

    private static MessageType schema(Path dataset, String member) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return ParquetFooter.read(source.inputFile(member)).schema();
        }
    }

    private static List<Group> rows(Path file) {
        List<Group> out = new ArrayList<>();
        ParquetGroups.forEach(file, out::add);
        return out;
    }
}
