package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Numpress byte list with a null byte fails only its own spectrum (U2): {@link ChunkCodecs#decodeNumpress} reads the
 * byte list inside its try block, so the null byte surfaces as the {@link MzPeakException} "Failed to Numpress-decode
 * ..." that {@link SpectrumArrayStore} keeps for that spectrum, and the other spectra of the row group read. Before,
 * the raw RuntimeException of the byte list escaped the store's load and failed every spectrum of the member.
 *
 * <p>Fixture: small.numpress (in memory), whose {@code spectra_data.parquet} is rewritten in one row group with the
 * elements of {@code mz_numpress_linear_bytes} made optional and the third byte of spectrum 1's first chunk row null.
 * Spectrum 0, in the same row group, reads as the fixture does, and so does spectrum 7 after it. (The fixture has no
 * signal rows for spectra 4 and 5, which are centroids in {@code spectra_peaks.parquet}.)
 */
class NumpressNullByteTest {

    private static final String DATA = "spectra_data.parquet";
    private static final String LINEAR = "mz_numpress_linear_bytes";

    @TempDir
    Path tmp;

    @Test
    void aNullNumpressByteFailsOnlyItsSpectrum() throws IOException {
        Path fixture = Fixtures.current("small.numpress.mzpeak");
        Path member = tmp.resolve(DATA);
        Files.write(member, TestSources.memory(fixture).member(DATA));
        MemberRewrite.Member m = MemberRewrite.read(member);
        String required = "required int32 item (INTEGER(8,false));";
        String schema = m.schema.toString();
        assertThat(schema.indexOf(LINEAR)).isLessThan(schema.indexOf(required));
        m.schema = MessageTypeParser.parseMessageType(schema.replaceFirst(
                Pattern.quote(required), "optional int32 item (INTEGER(8,false));"));
        List<Group> rows = new ArrayList<>();
        boolean nulled = false;
        for (Group row : m.rows) {
            Group chunk = row.getGroup("chunk", 0);
            Group out = new SimpleGroupFactory(m.schema).newGroup();
            Group outChunk = out.addGroup("chunk");
            boolean corrupt = !nulled && chunk.getLong("spectrum_index", 0) == 1;
            MemberRewrite.copyInto(chunk, outChunk, corrupt ? LINEAR : null);
            if (corrupt) {
                Group bytes = chunk.getGroup(LINEAR, 0);
                Group list = outChunk.addGroup(LINEAR);
                for (int i = 0; i < bytes.getFieldRepetitionCount("list"); i++) {
                    Group element = list.addGroup("list");
                    if (i != 2) {
                        element.add("item", bytes.getGroup("list", i).getInteger("item", 0));
                    }
                }
                nulled = true;
            }
            rows.add(out);
        }
        assertThat(nulled).isTrue();
        m.rows.clear();
        m.rows.addAll(rows);
        MemberRewrite.write(m, member);
        MzPeakSource copy = MzPeakReaderEdgeCasesTest.edited(fixture, manifest -> { },
                Map.of(DATA, Files.readAllBytes(member)));

        Spectrum expected;
        try (MzPeakReader reader = MzPeakReader.open(fixture)) {
            expected = reader.getSpectrum(0).orElseThrow();
        }
        try (MzPeakReader reader = MzPeakReader.open(copy)) {
            Spectrum s0 = reader.getSpectrum(0).orElseThrow();
            assertThat(s0.mz()).hasSize(expected.mz().length).isEqualTo(expected.mz());
            assertThat(s0.intensity()).isEqualTo(expected.intensity());
            assertThatThrownBy(() -> reader.getSpectrum(1))
                    .isInstanceOf(MzPeakException.class)
                    .hasMessageContaining("Failed to Numpress-decode m/z for spectrum_index 1");
            // the spectrum after it reads too
            assertThat(reader.getSpectrum(7).orElseThrow().mz()).isNotEmpty();
        }
    }
}
