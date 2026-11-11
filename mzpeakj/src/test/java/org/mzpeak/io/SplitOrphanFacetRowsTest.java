package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.model.SpectrumDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-6 (design §3.13): facet rows of the split layout whose {@code source_index} names no spectrum are dropped. A copy
 * of current small.unpacked whose spectra_metadata_scans, _precursors and _selected_ions each get one extra row with
 * {@code source_index} 1000, a copy of the member's row 0 otherwise, opens with the 48 spectra and the descriptions of
 * the original, without an exception and without a WARNING. The packed layout is pinned by
 * {@link PackedSpectrumMetadataCharacterizationTest#facetsOfAnAbsentSpectrumAreDropped()}.
 */
class SplitOrphanFacetRowsTest {

    private static final Path SMALL = Fixtures.current("small.unpacked.mzpeak");
    private static final List<String> FACETS = List.of("spectra_metadata_scans.parquet",
            "spectra_metadata_precursors.parquet", "spectra_metadata_selected_ions.parquet");
    private static final long ORPHAN = 1000;

    @Test
    void facetRowsOfAnAbsentSpectrumAreDroppedSilently(@TempDir Path tmp) throws IOException {
        Path copy = MemberRewrite.copy(SMALL, tmp);
        for (String facet : FACETS) {
            Path file = copy.resolve(facet);
            MemberRewrite.Member m = MemberRewrite.read(file);
            int rows = m.rows.size();
            Group first = m.rows.get(0);
            assertThat(ParquetGroups.optLong(first, "source_index")).as("source_index of row 0 of %s", facet)
                    .isNotEqualTo(ORPHAN);
            m.rows.add(MemberRewrite.copyRow(first, m.schema, "source_index",
                    g -> g.append("source_index", ORPHAN)));
            MemberRewrite.write(m, file);
            assertThat(MemberRewrite.read(file).rows).as("rows of the edited %s", facet).hasSize(rows + 1)
                    .last().satisfies(g -> assertThat(ParquetGroups.optLong(g, "source_index")).isEqualTo(ORPHAN));
        }

        List<String> original = describe(SMALL);
        List<String> edited = new ArrayList<>();
        List<LogRecord> warnings = MemberRewrite.warnings(() -> edited.addAll(describe(copy)));

        assertThat(original).hasSize(48);
        assertThat(edited).isEqualTo(original);
        assertThat(warnings).extracting(LogRecord::getMessage).isEmpty();
    }

    /** The {@link Canonical} lines of the spectrum descriptions of {@code dataset}. */
    private static List<String> describe(Path dataset) {
        try (MzPeakReader reader = MzPeakReader.open(dataset)) {
            assertThat(reader.size()).isEqualTo(48);
            List<SpectrumDescription> metadata = reader.metadata();
            return Canonical.lines(metadata, Canonical::describe);
        }
    }
}
