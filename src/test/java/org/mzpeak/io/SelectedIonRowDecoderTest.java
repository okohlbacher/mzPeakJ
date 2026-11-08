package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroup;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.Entry;
import org.mzpeak.model.SelectedIon;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A selected-ion table without an intensity column, which every fixture has: {@link SelectedIonRowDecoder} reads the
 * ion without an intensity and with the same m/z, charge and Params. The row is the first selected-ion row with an
 * intensity of current small.mzpeak, copied without its {@code peak_intensity} column.
 */
class SelectedIonRowDecoderTest {

    private static final String INTENSITY = "MS:1000042";

    @Test
    void anIonOfATableWithoutAnIntensityColumnHasNoIntensity() {
        try (MzPeakSource source = MzPeakSource.open(Fixtures.current("small.mzpeak"))) {
            Entry entry = Facet.SPECTRUM_SELECTED_ION.entry(MzPeakManifest.fromSource(source)).orElseThrow();
            List<FacetRow> rows = new ArrayList<>();
            MetadataTable.forEachRow(source.inputFile(entry.name()), entry, Facet.SPECTRUM_SELECTED_ION, rows::add);
            FacetRow first = rows.stream()
                    .filter(r -> {
                        SelectedIon ion = SelectedIonRowDecoder.decode(r);
                        return ion != null && ion.intensity() != null;
                    })
                    .findFirst().orElseThrow();
            SelectedIon full = SelectedIonRowDecoder.decode(first);

            String intensityField = first.columns().resolve(INTENSITY);
            assertThat(intensityField).isEqualTo("peak_intensity");
            GroupType type = first.group().getType();
            List<Type> fields = new ArrayList<>(type.getFields());
            assertThat(fields.removeIf(t -> t.getName().equals(intensityField))).isTrue();
            MessageType reduced = new MessageType(type.getName(), fields);
            Group copy = new SimpleGroup(reduced);
            copy(first.group(), copy, intensityField);
            ColumnResolver columns = ColumnResolver.split(reduced, entry, Facet.SPECTRUM_SELECTED_ION);
            assertThat(columns.resolve(INTENSITY)).isNull();

            SelectedIon ion = SelectedIonRowDecoder.decode(new FacetRow(Facet.SPECTRUM_SELECTED_ION, copy, columns));

            assertThat(ion).isEqualTo(new SelectedIon(full.mz(), full.charge(), null, full.parameters()));
        }
    }

    /** Copies every value of {@code src} but the top-level field {@code omit} into {@code dst}, recursively. */
    private static void copy(Group src, Group dst, String omit) {
        GroupType type = src.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            if (name.equals(omit)) {
                continue;
            }
            for (int i = 0; i < src.getFieldRepetitionCount(f); i++) {
                if (!field.isPrimitive()) {
                    copy(src.getGroup(f, i), dst.addGroup(name), null);
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
