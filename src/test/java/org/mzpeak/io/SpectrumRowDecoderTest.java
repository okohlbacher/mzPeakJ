package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.Param;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A user parameter, one without an accession, in the {@code parameters} list of a split spectrum row that also has
 * leftover columns: {@link SpectrumRowDecoder} keeps it, before the leftovers, since only a list entry whose accession
 * a column holds is dropped. No fixture has such a parameter; the row is spectrum 0 of current small.mzpeak, copied
 * with the parameter appended to its list.
 */
class SpectrumRowDecoderTest {

    @Test
    void aListEntryWithoutAccessionIsKeptBeforeTheLeftovers() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.mzpeak"))) {
            Entry entry = Facet.SPECTRUM.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            List<FacetRow> rows = new ArrayList<>();
            MetadataTable.forEachRow(source.inputFile(entry.name()), entry, Facet.SPECTRUM, rows::add);
            FacetRow spectrum0 = rows.get(0);
            assertThat(spectrum0.key()).isZero();
            assertThat(spectrum0.columns().params(spectrum0.group())).as("the list of spectrum 0").isEmpty();
            List<Param> leftovers = SpectrumRowDecoder.decode(spectrum0, 0).spectrumParams;
            assertThat(leftovers).as("the leftovers of spectrum 0").isNotEmpty()
                    .allSatisfy(p -> assertThat(p.accession()).isNotNull());

            Group g = new SimpleGroup(spectrum0.group().getType());
            copy(spectrum0.group(), g);
            Group item = g.getGroup("parameters", 0).addGroup("list").addGroup("item");
            item.addGroup("value").add("string", "kept");
            item.add("name", "user note");

            SpectrumRowDecoder.SpectrumHead head =
                    SpectrumRowDecoder.decode(new FacetRow(Facet.SPECTRUM, g, spectrum0.columns()), 0);

            List<Param> expected = new ArrayList<>();
            expected.add(new Param("user note", null, "kept", null));
            expected.addAll(leftovers);
            assertThat(head.spectrumParams).containsExactlyElementsOf(expected);
            assertThat(head.index).isZero();
            assertThat(head.msLevel).isEqualTo(1);
        }
    }

    /** Copies every value of {@code src} into {@code dst}, recursively. */
    private static void copy(Group src, Group dst) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(name));
                    continue;
                }
                PrimitiveTypeName primitive = field.asPrimitiveType().getPrimitiveTypeName();
                switch (primitive) {
                    case INT32 -> dst.add(name, src.getInteger(f, i));
                    case INT64 -> dst.add(name, src.getLong(f, i));
                    case FLOAT -> dst.add(name, src.getFloat(f, i));
                    case DOUBLE -> dst.add(name, src.getDouble(f, i));
                    case BOOLEAN -> dst.add(name, src.getBoolean(f, i));
                    case BINARY -> dst.add(name, src.getBinary(f, i));
                    default -> throw new AssertionError("unexpected " + primitive + " field " + name);
                }
            }
        }
    }
}
