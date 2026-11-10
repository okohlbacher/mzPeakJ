package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the Rust reference reader (mzPeak-reference 2ee2589) needs from a split write, beyond the spectra it already
 * read back equal from 45 mzPeakJ-written files:
 * <ul>
 *   <li>spectra_metadata and wavelength_spectra_metadata carry {@code auxiliary_arrays} and
 *       {@code number_of_auxiliary_arrays}, typed as in the reference's has_uv, an empty list and 0 per row, and an
 *       {@code ARROW:schema} footer entry that gives the Arrow reader the reference's 64-bit-offset types for them
 *       (ArrowSchemaHintTest); the Rust reader projects both for every wavelength spectrum and panics without them;
 *   <li>the wavelength columns of the reference: the representation, base peak intensity and TIC move to their
 *       columns, the spectrum type and the observed wavelength range are copied to theirs and stay in the list,
 *       where the Rust reader takes them from, so a read, write and read of has_uv keeps every Param in its order;
 *   <li>no {@code parameters} item lacks a name (Rust unwraps every name and panics): a nameless Param that stays in
 *       a list is written with its term's name, or its accession where the writer does not know the term, and a
 *       Param with neither name nor accession is refused, in both layouts, before anything is written.
 * </ul>
 */
class SplitWriterRustReaderTest {

    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final String HAS_UV = "has_uv.mzpeak";
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String WAVELENGTH = "wavelength_spectra_metadata.parquet";
    private static final String AUXILIARY_ARRAYS = "auxiliary_arrays";
    private static final String AUXILIARY_COUNT = "number_of_auxiliary_arrays";
    private static final String COUNTS = "MS:1000131";
    private static final String NANOMETRE = "UO:0000018";
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
            assertThat(footer(out, member)).as(member).containsEntry(ArrowSchemaHint.KEY,
                    ArrowSchemaHint.encode(written));

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
     * Constructed wavelength spectra. A nameless or named representation moves to its column (the name is not
     * written, as for every term-marker column); a finite base peak intensity and TIC in counts move to theirs. A
     * spectrum type and a wavelength range in nanometres are copied to their columns and stay in the list. A
     * representation with a value, a base peak intensity without unit, a non-finite TIC and a wavelength in another
     * unit stay in the list only. Every nameless Param left in a list is written with a name: its term's (spectrum
     * type, representation, dissociation method), else its accession. Reading back gives the list, then the columns
     * whose accession the list does not repeat.
     */
    @Test
    void theWavelengthColumnsTakeWhatTheReferenceWritesThereAndNoListItemIsNameless() throws IOException {
        Param centroid = new Param(null, "MS:1000127", null, null);
        Param profile = new Param("profile spectrum", "MS:1000128", null, null);
        Param bpi = new Param("base peak intensity", "MS:1000505", 2.5, COUNTS);
        Param tic = new Param("total ion current", "MS:1000285", 7.0, COUNTS);
        Param valuedRepresentation = new Param(null, "MS:1000128", "x", null);
        Param unitlessBpi = new Param("base peak intensity", "MS:1000505", 2.5, null);
        Param nanTic = new Param("total ion current", "MS:1000285", Double.NaN, COUNTS);
        Param namelessType = new Param(null, "MS:1000804", null, null);
        Param lowest = new Param("lowest observed wavelength", "MS:1000619", 190.0, NANOMETRE);
        Param highest = new Param("highest observed wavelength", "MS:1000618", 400.0, NANOMETRE);
        Param lowestInMetres = new Param("lowest observed wavelength", "MS:1000619", 1.9e-7, "UO:0000008");
        Param unknownTerm = new Param(null, "MS:4999999", 1L, null);
        Param namelessCid = new Param(null, "MS:1000133", null, null);
        List<WavelengthSpectrum> waves = List.of(
                wave(0, List.of(centroid, TITLE)),
                wave(1, List.of(tic, profile, bpi)),
                wave(2, List.of(valuedRepresentation, unitlessBpi, nanTic, namelessType)),
                wave(3, List.of(unknownTerm, lowest, namelessCid, highest)),
                wave(4, List.of(lowestInMetres)));
        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Path out = write(waves, source, tmp.resolve("out.mzpeak"));

        Param namedType = new Param("electromagnetic radiation spectrum", "MS:1000804", null, null);
        Param namedValuedRepresentation = new Param("profile spectrum", "MS:1000128", "x", null);
        Param namedUnknown = new Param("MS:4999999", "MS:4999999", 1L, null);
        Param namedCid = new Param("collision-induced dissociation", "MS:1000133", null, null);
        List<Group> rows = rows(out.resolve(WAVELENGTH));
        assertThat(rows).hasSize(5);
        assertRow(rows.get(0), null, "MS:1000127", null, null, null, null, List.of(TITLE));
        assertRow(rows.get(1), null, "MS:1000128", null, null, 2.5, 7.0, List.of());
        assertRow(rows.get(2), "MS:1000804", null, null, null, null, null,
                List.of(namedValuedRepresentation, unitlessBpi, nanTic, namedType));
        assertRow(rows.get(3), null, null, 190.0, 400.0, null, null, List.of(namedUnknown, lowest, namedCid, highest));
        assertRow(rows.get(4), null, null, null, null, null, null, List.of(lowestInMetres));
        assertThat(nameless(out)).isEmpty();

        List<WavelengthSpectrum> back = ForwardBackwardCorpusTest.readSource(out).wavelengthSpectra;
        assertThat(back.get(0).parameters()).containsExactly(TITLE, centroid);
        assertThat(back.get(1).parameters()).containsExactly(new Param(null, "MS:1000128", null, null), bpi, tic);
        assertThat(back.get(2).parameters()).containsExactly(namedValuedRepresentation, unitlessBpi, nanTic,
                namedType);
        assertThat(back.get(3).parameters()).containsExactly(namedUnknown, lowest, namedCid, highest);
        assertThat(back.get(4).parameters()).containsExactly(lowestInMetres);
    }

    /**
     * Read, write and read where the spectrum type is only a column: the reader gives it as
     * {@code Param(null, MS:1000804)} after the list; the writer copies it to the spectrum type column and writes it,
     * named, to the list, where it reads back; a second write gives the same row.
     */
    @Test
    void aTypeThatIsOnlyAColumnReadsBackNamedFromTheList() throws IOException {
        MessageType schema = Types.buildMessage()
                .required(PrimitiveTypeName.INT64).as(LogicalTypeAnnotation.intType(64, false)).named("index")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("id")
                .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("spectrum_type")
                .addField(ParquetOutput.paramListField())
                .named("wavelength_spectra_metadata");
        Group row = new SimpleGroupFactory(schema).newGroup().append("index", 0L).append("id", "w0")
                .append("spectrum_type", "MS:1000804");
        ParquetOutput.writeParams(row, List.of(TITLE));
        Path member = Files.createDirectories(tmp.resolve("in")).resolve(WAVELENGTH);
        try (ParquetWriter<Group> writer = ParquetOutput.openWriter(member, schema, SPLIT)) {
            writer.write(row);
        }
        Entry entry = new Entry(WAVELENGTH, "wavelength_spectrum", "metadata",
                List.of(new ColumnMapping("spectrum type", "spectrum_type", "MS:1000559", null, true)), List.of(),
                null);
        List<Param> read;
        try (MzPeakSource in = MzPeakSource.open(tmp.resolve("in"))) {
            read = WavelengthMetadataDecoder.decode(in.inputFile(WAVELENGTH), entry).get(0).parameters();
        }
        assertThat(read).containsExactly(TITLE, new Param(null, "MS:1000804", null, null));

        Source source = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Path first = write(List.of(wave(0, read)), source, tmp.resolve("first.mzpeak"));
        Param named = new Param("electromagnetic radiation spectrum", "MS:1000804", null, null);
        Group written = rows(first.resolve(WAVELENGTH)).get(0);
        assertThat(ParquetGroups.optString(written, "spectrum_type")).isEqualTo("MS:1000804");
        assertThat(ParquetGroups.readParams(written, "parameters")).containsExactly(TITLE, named);
        assertThat(nameless(first)).isEmpty();
        List<Param> back = ForwardBackwardCorpusTest.readSource(first).wavelengthSpectra.get(0).parameters();
        assertThat(back).containsExactly(TITLE, named);

        Path second = write(List.of(wave(0, back)), source, tmp.resolve("second.mzpeak"));
        assertThat(ParquetGroups.readParams(rows(second.resolve(WAVELENGTH)).get(0), "parameters"))
                .containsExactly(TITLE, named);
        assertThat(ForwardBackwardCorpusTest.readSource(second).wavelengthSpectra.get(0).parameters())
                .isEqualTo(back);
    }

    /**
     * A Param with neither name nor accession names nothing, and the Rust reader cannot read a list holding it: the
     * write is refused before anything is written, in both layouts, with the entity named, whether the Param belongs
     * to a spectrum (here a selected ion of its precursor), a chromatogram or a wavelength spectrum (without id).
     */
    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aParamWithNeitherNameNorAccessionIsRefused(MetadataLayout layout) {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Options options = Options.defaults().withMetadataLayout(layout);
        Param bare = new Param(null, null, "bare", null);
        String expected = ": a parameter needs a name or an accession: " + bare;

        List<Spectrum> spectra = new ArrayList<>(small.spectra);
        int msn = 0;
        while (spectra.get(msn).description().precursors().isEmpty()) {
            msn++;
        }
        Spectrum s = spectra.get(msn);
        spectra.set(msn, withIonParam(s, bare));
        Path spectrumOut = tmp.resolve("spectrum.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(spectrumOut, spectra, small.chromatograms, List.of(),
                small.scanSettings, small.fileMetadata, options)).isInstanceOf(MzPeakException.class)
                .hasMessage("spectrum " + s.index() + " (" + s.description().id() + ")" + expected);
        assertThat(spectrumOut).doesNotExist();

        Chromatogram c = small.chromatograms.get(0);
        Chromatogram bad = new Chromatogram(c.index(), c.id(), c.typeCurie(), c.time(), c.intensity(),
                c.intensityUnit(), c.arrays(), c.polarity(), c.dataProcessingRef(), List.of(bare), c.precursors());
        Path chromatogramOut = tmp.resolve("chromatogram.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(chromatogramOut, small.spectra, List.of(bad), List.of(),
                small.scanSettings, small.fileMetadata, options)).isInstanceOf(MzPeakException.class)
                .hasMessage("chromatogram " + c.index() + " (" + c.id() + ")" + expected);
        assertThat(chromatogramOut).doesNotExist();

        WavelengthSpectrum w = new WavelengthSpectrum(7, null, 0.5, null, new double[] {200.0}, new double[] {1.0},
                List.of(TITLE, bare));
        Path wavelengthOut = tmp.resolve("wavelength.mzpeak");
        assertThatThrownBy(() -> MzPeakWriter.writeDirectory(wavelengthOut, small.spectra, small.chromatograms,
                List.of(w), small.scanSettings, small.fileMetadata, options)).isInstanceOf(MzPeakException.class)
                .hasMessage("wavelength spectrum 7" + expected);
        assertThat(wavelengthOut).doesNotExist();
    }

    /** A session refuses such a spectrum when it is added, before any of it is written. */
    @Test
    void aSessionRefusesASpectrumWithAParamWithNeitherNameNorAccession() {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.current("small.unpacked.mzpeak"));
        Spectrum s = small.spectra.get(0);
        Param bare = new Param(null, null, 1L, null);
        SpectrumDescription d = s.description();
        Spectrum bad = new Spectrum(new SpectrumDescription(d.index(), null, d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(),
                d.precursors(), List.of(bare)), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
        Set<String> keys = new LinkedHashSet<>(s.arrays().keySet());
        keys.addAll(s.peakArrays().keySet());
        try (MzPeakWriter.Session session = MzPeakWriter.openDirectory(tmp.resolve("session.mzpeak"),
                small.fileMetadata, small.scanSettings, keys, SPLIT)) {
            assertThatThrownBy(() -> session.addSpectrum(bad)).isInstanceOf(MzPeakException.class)
                    .hasMessage("spectrum " + d.index() + ": a parameter needs a name or an accession: " + bare);
        }
    }

    // ---- no nameless parameters ---------------------------------------------------------------------------------

    /**
     * Every fixture, read and written split: every Param item of every metadata member, in any {@code parameters}
     * list or struct, has a name.
     */
    @ParameterizedTest
    @MethodSource("fixtures")
    void noWrittenParamHasAnAccessionWithoutAName(Path fixture) throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(fixture);
        Path out = write(source.wavelengthSpectra, source, tmp.resolve("out.mzpeak"));
        int[] items = {0};
        assertThat(nameless(out, items)).as(fixture.toString()).isEmpty();
        assertThat(items[0]).as("Param items of %s", fixture).isPositive();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private static void assertRow(Group row, String type, String representation, Double lowest, Double highest,
                                  Double bpi, Double tic, List<Param> params) {
        assertThat(ParquetGroups.optString(row, "spectrum_type")).isEqualTo(type);
        assertThat(ParquetGroups.optString(row, "spectrum_representation")).isEqualTo(representation);
        assertThat(ParquetGroups.optDouble(row, "lowest_observed_wavelength")).isEqualTo(lowest);
        assertThat(ParquetGroups.optDouble(row, "highest_observed_wavelength")).isEqualTo(highest);
        assertThat(ParquetGroups.optDouble(row, "base_peak_intensity")).isEqualTo(bpi);
        assertThat(ParquetGroups.optDouble(row, "total_ion_current")).isEqualTo(tic);
        assertThat(ParquetGroups.readParams(row, "parameters")).isEqualTo(params);
    }

    /**
     * Counts the Param items (structs with {@code value}, {@code accession} and {@code name}) below {@code g} in
     * {@code items}, and adds each without a name, with or without an accession, to {@code nameless}.
     */
    private static void visitParams(Group g, String where, List<String> nameless, int[] items) {
        GroupType type = g.getType();
        if (type.containsField("value") && type.containsField("accession") && type.containsField("name")) {
            items[0]++;
            if (!ParquetGroups.has(g, "name")) {
                nameless.add(where + ": " + ParquetGroups.optString(g, "accession"));
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

    /** The Param items without a name in the metadata members of the split dataset {@code out}. */
    private static List<String> nameless(Path out) throws IOException {
        return nameless(out, new int[1]);
    }

    /** {@link #nameless(Path)}, counting the Param items visited in {@code items[0]}. */
    private static List<String> nameless(Path out, int[] items) throws IOException {
        List<String> nameless = new ArrayList<>();
        try (Stream<Path> members = Files.list(out)) {
            for (Path member : members.filter(p -> p.getFileName().toString().contains("_metadata")).toList()) {
                for (Group row : rows(member)) {
                    visitParams(row, member.getFileName().toString(), nameless, items);
                }
            }
        }
        return nameless;
    }

    private static java.util.Map<String, String> footer(Path dataset, String member) {
        try (MzPeakSource source = MzPeakSource.open(dataset)) {
            return ParquetFooter.read(source.inputFile(member)).keyValue();
        }
    }

    /** {@code s} with {@code extra} appended to the Params of the first selected ion of its first precursor. */
    private static Spectrum withIonParam(Spectrum s, Param extra) {
        SpectrumDescription d = s.description();
        Precursor p = d.precursors().get(0);
        SelectedIon ion = p.selectedIons().get(0);
        List<Param> params = new ArrayList<>(ion.parameters());
        params.add(extra);
        List<SelectedIon> ions = new ArrayList<>(p.selectedIons());
        ions.set(0, new SelectedIon(ion.mz(), ion.charge(), ion.intensity(), params));
        List<Precursor> precursors = new ArrayList<>(d.precursors());
        precursors.set(0, new Precursor(p.precursorIndex(), p.precursorId(), p.isolationWindow(), ions,
                p.activation()));
        return new Spectrum(new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(), d.polarity(),
                d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(), precursors,
                d.parameters()), s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays());
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
