package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mzpeak.examples.ConvertToDta;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.MetadataLayout;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.SelectedIon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7 (D13-22): a selected ion without m/z is kept with {@code mz} NaN; spectra.md (l.288-316) and
 * {@code precursor_selectedion_must} of table_rules.json let the charge alone describe it. A copy of current
 * small.unpacked whose spectra_metadata_selected_ions row 0 has a null {@code selected_ion_mz} and charge 2 (the
 * fixture's row has an m/z and no charge) reads that spectrum's precursor with the one ion, m/z NaN, charge 2, its
 * intensity and params unchanged. Written back in either metadata layout, the ion reads back the same; in the split
 * layout its {@code selected_ion_mz} cell is null.
 */
class SelectedIonWithoutMzTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final String MEMBER = "spectra_metadata_selected_ions.parquet";
    private static final String MZ = "selected_ion_mz";
    private static final String CHARGE = "charge_state";

    @ParameterizedTest
    @EnumSource(MetadataLayout.class)
    void aSelectedIonWithoutMzIsKeptWithMzNaNAndWrittenBackAsNull(MetadataLayout layout, @TempDir Path tmp)
            throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp.resolve("in"));
        Path file = copy.resolve(MEMBER);
        MemberRewrite.Member m = MemberRewrite.read(file);
        Group first = m.rows.get(0);
        assertThat(ParquetGroups.optDouble(first, MZ)).as("m/z of row 0").isNotNull();
        assertThat(ParquetGroups.optInt(first, CHARGE)).as("charge of row 0").isNull();
        long sourceIndex = ParquetGroups.optLong(first, "source_index");
        m.rows.set(0, MemberRewrite.copyRow(first, m.schema, MZ, g -> g.append(CHARGE, 2)));
        MemberRewrite.write(m, file);

        SelectedIon original = onlyIon(precursorsOf(SMALL, sourceIndex));
        assertThat(original.mz()).isNotNaN();
        List<Precursor> edited = precursorsOf(copy, sourceIndex);
        SelectedIon ion = onlyIon(edited);
        assertThat(ion.mz()).isNaN();
        assertThat(ion.charge()).isEqualTo(2);
        assertThat(ion.intensity()).isEqualTo(original.intensity());
        assertThat(ion.parameters()).isEqualTo(original.parameters());

        Source source = ForwardBackwardCorpusTest.readSource(copy);
        Path out = tmp.resolve("out.mzpeak");
        MzPeakWriter.writeDirectory(out, source.spectra, source.chromatograms, source.wavelengthSpectra,
                source.scanSettings, source.fileMetadata, MzPeakWriter.Options.defaults().withMetadataLayout(layout));

        SelectedIon back = onlyIon(precursorsOf(out, sourceIndex));
        assertThat(back.mz()).isNaN();
        assertThat(back.charge()).isEqualTo(2);
        assertThat(back.intensity()).isEqualTo(original.intensity());
        if (layout == MetadataLayout.SPLIT) {
            List<Group> rows = new ArrayList<>();
            ParquetGroups.forEach(out.resolve(MEMBER), g -> {
                if (Long.valueOf(sourceIndex).equals(ParquetGroups.optLong(g, "source_index"))) {
                    rows.add(g);
                }
            });
            assertThat(rows).singleElement().satisfies(g -> {
                assertThat(g.getType().containsField(MZ)).isTrue();
                assertThat(ParquetGroups.has(g, MZ)).as("the selected_ion_mz cell is null").isFalse();
                assertThat(ParquetGroups.optInt(g, CHARGE)).isEqualTo(2);
            });
        }
    }

    /**
     * ConvertToDta needs the precursor m/z for the MH+ line: the edited spectrum, whose only ion has no m/z, gets no
     * .dta file (as before R7, when the ion was dropped) instead of one headed "NaN 2"; every other MS2 spectrum does.
     */
    @Test
    void convertToDtaSkipsTheSpectrumWhoseOnlyIonHasNoMz(@TempDir Path tmp) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp.resolve("in"));
        Path file = copy.resolve(MEMBER);
        MemberRewrite.Member m = MemberRewrite.read(file);
        Group first = m.rows.get(0);
        long sourceIndex = ParquetGroups.optLong(first, "source_index");
        m.rows.set(0, MemberRewrite.copyRow(first, m.schema, MZ, g -> g.append(CHARGE, 2)));
        MemberRewrite.write(m, file);
        String id;
        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            id = reader.metadata().stream().filter(d -> d.index() == sourceIndex).findFirst().orElseThrow().id();
        }
        String scan = id.substring(id.indexOf("scan=") + "scan=".length());

        Path original = tmp.resolve("original");
        Path edited = tmp.resolve("edited");
        ConvertToDta.main(new String[]{SMALL.toString(), original.toString()});
        ConvertToDta.main(new String[]{copy.toString(), edited.toString()});

        List<String> before = fileNames(original);
        String dropped = "small." + scan + "." + scan + ".2.dta";
        assertThat(before).contains(dropped);
        List<String> expected = new ArrayList<>(before);
        expected.remove(dropped);
        assertThat(fileNames(edited)).containsExactlyElementsOf(expected);
        for (String name : expected) {
            assertThat(Files.readString(edited.resolve(name))).isEqualTo(Files.readString(original.resolve(name)));
        }
    }

    private static List<String> fileNames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /** The precursors of the spectrum with index {@code index} of {@code dataset}. */
    private static List<Precursor> precursorsOf(Path dataset, long index) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            return reader.metadata().stream().filter(d -> d.index() == index).findFirst().orElseThrow().precursors();
        }
    }

    /** The one selected ion of the one precursor of {@code precursors}. */
    private static SelectedIon onlyIon(List<Precursor> precursors) {
        assertThat(precursors).hasSize(1);
        assertThat(precursors.get(0).selectedIons()).hasSize(1);
        return precursors.get(0).selectedIons().get(0);
    }
}
