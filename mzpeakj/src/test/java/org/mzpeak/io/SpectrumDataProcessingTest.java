package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6 (D13-21): the spectrum {@code data_processing_id} of spectra.md, the {@code id} of the data processing that
 * governs a spectrum when it differs from the run's default, is read into
 * {@link SpectrumDescription#dataProcessingRef()} and written back by the split layout, in a {@code data_processing_id}
 * column that is there only when some spectrum has a reference. The packed layout, whose schema is frozen, does not
 * write it and warns.
 *
 * <p>Fixture: a copy of current small.unpacked whose {@code spectra_metadata} has, in its {@code data_processing_id}
 * column (all null in the fixture), {@link #DATA_PROCESSING} for spectrum {@link #SPECTRUM}; {@code
 * mzpeak_conversion1} is one of the fixture's declared data processings. Every other cell is the fixture's.
 */
class SpectrumDataProcessingTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String SPECTRA = "spectra_metadata.parquet";
    private static final String COLUMN = "data_processing_id";
    private static final String DATA_PROCESSING = "mzpeak_conversion1";
    private static final long SPECTRUM = 3;
    private static final Options SPLIT = Options.defaults().withMetadataLayout(MetadataLayout.SPLIT);
    private static final Options PACKED = Options.defaults().withMetadataLayout(MetadataLayout.PACKED);
    private static final String PACKED_WARNING = "data processing reference of a spectrum";

    @TempDir
    Path tmp;

    /** The fixture itself, whose column is all null, reads no reference. */
    @Test
    void theFixtureReadsNoReference() {
        try (MzPeakReader reader = MzPeakReader.open(SMALL)) {
            assertThat(reader.metadata()).hasSize(48).extracting(SpectrumDescription::dataProcessingRef)
                    .containsOnlyNulls();
        }
    }

    /** Spectrum 3 reads its reference, every other spectrum none; the column gives no Param. */
    @Test
    void theReferenceIsRead() throws IOException {
        assertReadsTheReference(withReference(COLUMN));
    }

    /** A {@code data_processing_ref} column, the spelling 0.12.0 wrote for chromatograms, reads as well. */
    @Test
    void aDataProcessingRefColumnIsReadToo() throws IOException {
        assertReadsTheReference(withReference("data_processing_ref"));
    }

    /**
     * The split writer writes the reference of spectrum 3 back into the {@code data_processing_id} column, before
     * the {@code parameters} list, null for the other spectra, and it reads back.
     */
    @Test
    void theSplitWriterWritesTheReferenceBack() throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(withReference(COLUMN));
        Path out = tmp.resolve("written.mzpeak");
        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out, source.spectra,
                source.chromatograms, source.wavelengthSpectra, source.scanSettings, source.fileMetadata, SPLIT));
        assertThat(warnings).isEmpty();

        List<Group> rows = rows(out.resolve(SPECTRA));
        assertThat(rows).hasSize(48);
        List<String> fields = rows.get(0).getType().getFields().stream().map(Type::getName).toList();
        assertThat(fields.indexOf(COLUMN)).isPositive().isEqualTo(fields.indexOf("parameters") - 1);
        for (Group row : rows) {
            long index = ParquetGroups.optLong(row, "index");
            assertThat(ParquetGroups.has(row, COLUMN)).as("spectrum %d", index).isEqualTo(index == SPECTRUM);
        }
        assertThat(rows.get((int) SPECTRUM).getString(COLUMN, 0)).isEqualTo(DATA_PROCESSING);
        assertReadsTheReference(out);
    }

    /**
     * The reference survives the writer's copy of a spectrum: written without spectra 0 to 2, spectrum 3 is renumbered
     * 0 (its precursor's index with it) and keeps its reference.
     */
    @Test
    void theReferenceSurvivesRenumbering() throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(withReference(COLUMN));
        List<Spectrum> later = source.spectra.subList((int) SPECTRUM, source.spectra.size());
        Path out = tmp.resolve("renumbered.mzpeak");
        MzPeakWriter.writeDirectory(out, later, List.of(), List.of(), source.scanSettings, source.fileMetadata, SPLIT);

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            List<SpectrumDescription> back = reader.metadata();
            assertThat(back).hasSize(45);
            assertThat(back.get(0).id()).isEqualTo(source.spectra.get((int) SPECTRUM).description().id());
            assertThat(back.get(0).dataProcessingRef()).isEqualTo(DATA_PROCESSING);
            assertThat(back.subList(1, back.size())).extracting(SpectrumDescription::dataProcessingRef)
                    .containsOnlyNulls();
        }
    }

    /** Without a reference the split writer writes no {@code data_processing_id} column, as before R6. */
    @Test
    void noReferenceMeansNoColumn() {
        Source source = ForwardBackwardCorpusTest.readSource(SMALL);
        Path out = tmp.resolve("plain.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, SPLIT);

        assertThat(rows(out.resolve(SPECTRA)).get(0).getType().containsField(COLUMN)).isFalse();
    }

    /** The packed layout writes no reference and says so, once, counting the spectra. */
    @Test
    void thePackedLayoutWarnsAboutTheReferences() throws IOException {
        Source source = ForwardBackwardCorpusTest.readSource(withReference(COLUMN));
        List<Spectrum> spectra = new ArrayList<>(source.spectra);
        spectra.set(5, withReference(spectra.get(5), "pwiz_Reader_Thermo_conversion"));
        Path out = tmp.resolve("packed.mzpeak");
        List<String> warnings = warnings(() -> MzPeakWriter.writeDirectory(out, spectra, source.chromatograms,
                source.wavelengthSpectra, source.scanSettings, source.fileMetadata, PACKED));
        assertThat(warnings).containsExactly("the packed metadata layout writes no data processing reference of a"
                + " spectrum: 2 spectra lose their references");

        try (MzPeakReader reader = MzPeakReader.open(out)) {
            assertThat(reader.metadata()).extracting(SpectrumDescription::dataProcessingRef).containsOnlyNulls();
        }
        List<String> one = warnings(() -> MzPeakWriter.writeDirectory(tmp.resolve("packed-one.mzpeak"),
                source.spectra, List.of(), List.of(), source.scanSettings, source.fileMetadata, PACKED));
        assertThat(one).containsExactly("the packed metadata layout writes no data processing reference of a"
                + " spectrum: 1 spectrum loses its reference");
    }

    /** The old constructor gives no reference; the new one keeps it, and it takes part in equality. */
    @Test
    void theOldConstructorGivesNoReference() {
        SpectrumDescription old = new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null);
        SpectrumDescription withRef = new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null,
                "dp");
        assertThat(old.dataProcessingRef()).isNull();
        assertThat(withRef.dataProcessingRef()).isEqualTo("dp");
        assertThat(old).isEqualTo(new SpectrumDescription(0, "s", 1, 1.0, null, null, 0, 0, null, null, null, null))
                .isNotEqualTo(withRef);
    }

    private static void assertReadsTheReference(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset); MzPeakReader fixture = MzPeakReader.open(SMALL)) {
            List<SpectrumDescription> metadata = reader.metadata();
            List<SpectrumDescription> expected = fixture.metadata();
            assertThat(metadata).hasSize(48);
            for (int i = 0; i < metadata.size(); i++) {
                SpectrumDescription d = metadata.get(i);
                assertThat(d.dataProcessingRef()).as("spectrum %d", i).isEqualTo(i == SPECTRUM ? DATA_PROCESSING
                        : null);
                assertThat(d.parameters()).as("spectrum %d", i).isEqualTo(expected.get(i).parameters());
            }
        }
    }

    private static Spectrum withReference(Spectrum s, String ref) {
        SpectrumDescription d = s.description();
        SpectrumDescription description = new SpectrumDescription(d.index(), d.id(), d.msLevel(), d.retentionTime(),
                d.polarity(), d.signalContinuity(), d.numberOfDataPoints(), d.numberOfPeaks(), d.scans(),
                d.precursors(), d.parameters(), ref);
        return new Spectrum(description, s.mz(), s.intensity(), s.peaks(), s.arrays(), s.peakArrays(),
                s.intensityUnit(), s.peakIntensityUnit());
    }

    /**
     * A copy of small.unpacked (in a new directory under {@link #tmp}) whose spectra member names its data processing
     * column {@code column} and has {@link #DATA_PROCESSING} in it for spectrum {@link #SPECTRUM}.
     */
    private Path withReference(String column) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp.resolve("source-" + column));
        Path file = copy.resolve(SPECTRA);
        MemberRewrite.Member m = MemberRewrite.read(file);
        assertThat(m.rows).hasSize(48).noneMatch(row -> ParquetGroups.has(row, COLUMN));
        List<Type> fields = new ArrayList<>();
        for (Type t : m.schema.getFields()) {
            PrimitiveType p = t.getName().equals(COLUMN) ? t.asPrimitiveType() : null;
            fields.add(p == null ? t : Types.primitive(p.getPrimitiveTypeName(), p.getRepetition())
                    .as(p.getLogicalTypeAnnotation()).named(column));
        }
        MessageType schema = new MessageType(m.schema.getName(), fields);
        List<Group> rows = new ArrayList<>();
        for (Group row : m.rows) {
            rows.add(MemberRewrite.copyRow(row, schema, COLUMN, g -> {
                if (ParquetGroups.optLong(row, "index") == SPECTRUM) {
                    g.add(column, DATA_PROCESSING);
                }
                return g;
            }));
        }
        m.schema = schema;
        m.rows.clear();
        m.rows.addAll(rows);
        MemberRewrite.write(m, file);
        return copy;
    }

    /** The writer's warnings about spectrum data processing references during {@code write}. */
    private static List<String> warnings(Runnable write) {
        return ArrayIndexTest.captureLog(MzPeakWriter.class, write).stream()
                .filter(r -> r.getLevel() == Level.WARNING).map(LogRecord::getMessage)
                .filter(m -> m.contains(PACKED_WARNING)).toList();
    }

    private static List<Group> rows(Path member) {
        List<Group> rows = new ArrayList<>();
        ParquetGroups.forEach(member, rows::add);
        return rows;
    }
}
