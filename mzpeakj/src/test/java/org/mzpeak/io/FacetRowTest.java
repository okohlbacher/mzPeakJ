package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.Entry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link FacetRow#key()} of rows built from constructed groups, including rows whose key is null, which
 * {@link MetadataTable} never passes on (it rejects them in the split layout and skips them in the packed one).
 */
class FacetRowTest {

    /**
     * The key is the first key column of the facet, {@code spectrum_index} in place of a missing
     * {@code source_index}; a row without it throws, naming the member, the facet and the key column.
     */
    @Test
    void aRowWithoutItsKeyThrowsNamingTheMemberTheFacetAndTheKey() {
        for (String key : new String[] {"source_index", "spectrum_index"}) {
            MessageType scans = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                    + "  optional int64 " + key + " (INTEGER(64,false));\n"
                    + "  optional double scan_start_time;\n"
                    + "}");
            ColumnResolver columns = ColumnResolver.split(scans, new Entry("constructed_scans.parquet", "spectrum",
                    "scans"), Facet.SPECTRUM_SCAN);
            SimpleGroupFactory factory = new SimpleGroupFactory(scans);
            FacetRow keyed = new FacetRow(Facet.SPECTRUM_SCAN,
                    factory.newGroup().append(key, 7L).append("scan_start_time", 1.5), columns);
            assertThat(keyed.key()).isEqualTo(7L);

            FacetRow keyless = new FacetRow(Facet.SPECTRUM_SCAN, factory.newGroup().append("scan_start_time", 1.5),
                    columns);
            assertThat(keyless.optKey(key)).isNull();
            assertThatThrownBy(keyless::key)
                    .isInstanceOf(MzPeakException.class)
                    .hasMessage("constructed_scans.parquet: SPECTRUM_SCAN row has no " + key);
        }

        MessageType packed = MessageTypeParser.parseMessageType("message arrow_schema {\n"
                + "  optional group spectrum {\n"
                + "    optional int64 index (INTEGER(64,false));\n"
                + "    optional int32 MS_1000511_ms_level;\n"
                + "  }\n"
                + "}");
        Group row = new SimpleGroupFactory(packed).newGroup();
        row.addGroup("spectrum").append("MS_1000511_ms_level", 2);
        FacetRow spectrum = new FacetRow(Facet.SPECTRUM, row.getGroup("spectrum", 0),
                ColumnResolver.packed(packed.getType("spectrum").asGroupType(), Facet.SPECTRUM,
                        "packed_spectra.parquet"));
        assertThatThrownBy(spectrum::key)
                .isInstanceOf(MzPeakException.class)
                .hasMessage("packed_spectra.parquet, packed struct 'spectrum': SPECTRUM row has no index");
    }
}
