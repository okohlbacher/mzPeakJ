package org.mzpeak.io;

import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.Type;
import org.mzpeak.io.parquet.ParquetGroups;
import org.mzpeak.io.parquet.ZstdCompressionCodecFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Test helper that edits a member of an unpacked dataset copy: reads its rows and footer key-values with parquet-java,
 * lets the caller change the schema, the rows and the key-values, and writes the member again with parquet-java's
 * {@link Group} writer in one row group. The {@code ARROW:schema} key-value is dropped, since it describes the
 * original schema; mzPeakJ does not read it.
 */
final class MemberRewrite {

    /** The rows and footer key-values of a member, and its schema. */
    static final class Member {
        MessageType schema;
        final List<Group> rows = new ArrayList<>();
        final Map<String, String> keyValues = new LinkedHashMap<>();
    }

    private MemberRewrite() {
    }

    /** Copies the unpacked dataset {@code dataset} into a new directory under {@code tmp}. */
    static Path copy(Path dataset, Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(dataset.getFileName().toString()));
        try (Stream<Path> files = Files.list(dataset)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                Files.copy(f, dir.resolve(f.getFileName().toString()));
            }
        }
        return dir;
    }

    /** Reads {@code file}. */
    static Member read(Path file) throws IOException {
        Member m = new Member();
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file),
                ParquetGroups.readOptions())) {
            m.schema = reader.getFooter().getFileMetaData().getSchema();
            m.keyValues.putAll(reader.getFooter().getFileMetaData().getKeyValueMetaData());
        }
        m.keyValues.remove(ArrowSchemaHint.KEY);
        ParquetGroups.forEach(file, m.rows::add);
        return m;
    }

    /** Writes {@code m} over {@code file}. */
    static void write(Member m, Path file) {
        try (ParquetWriter<Group> writer = ExampleParquetWriter.builder(new LocalOutputFile(file))
                .withConf(new PlainParquetConfiguration())
                .withType(m.schema)
                .withExtraMetaData(m.keyValues)
                .withCodecFactory(new ZstdCompressionCodecFactory())
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                .build()) {
            for (Group row : m.rows) {
                writer.write(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A copy of {@code row} as a row of {@code schema}, which holds every field of the row's schema at the same path
     * (and maybe more): each field whose path is not {@code skip} is copied, and {@code fill} may then add values.
     */
    static Group copyRow(Group row, MessageType schema, String skip, UnaryOperator<Group> fill) {
        Group out = new SimpleGroupFactory(schema).newGroup();
        copyInto(row, out, skip);
        return fill.apply(out);
    }

    private static void copyInto(Group from, Group to, String skip) {
        GroupType type = from.getType();
        for (int f = 0; f < type.getFieldCount(); f++) {
            Type field = type.getType(f);
            String name = field.getName();
            if (name.equals(skip)) {
                continue;
            }
            for (int r = 0; r < from.getFieldRepetitionCount(f); r++) {
                if (!field.isPrimitive()) {
                    copyInto(from.getGroup(f, r), to.addGroup(name), null);
                    continue;
                }
                switch (field.asPrimitiveType().getPrimitiveTypeName()) {
                    case INT64 -> to.add(name, from.getLong(f, r));
                    case INT32 -> to.add(name, from.getInteger(f, r));
                    case DOUBLE -> to.add(name, from.getDouble(f, r));
                    case FLOAT -> to.add(name, from.getFloat(f, r));
                    case BOOLEAN -> to.add(name, from.getBoolean(f, r));
                    case BINARY -> to.add(name, from.getBinary(f, r));
                    default -> throw new IllegalStateException("cannot copy " + field);
                }
            }
        }
    }

    /** Runs {@code action} and returns the records of WARNING or above logged under {@code org.mzpeak}. */
    static List<LogRecord> warnings(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Logger log = Logger.getLogger("org.mzpeak");
        Level level = log.getLevel();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                    records.add(r);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        log.setLevel(Level.ALL);
        log.addHandler(capture);
        try {
            action.run();
        } finally {
            log.removeHandler(capture);
            log.setLevel(level);
        }
        return records;
    }
}
