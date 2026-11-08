package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import ms.numpress.MSNumpress;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.mzpeak.io.ParquetOutput.UINT64;
import static org.mzpeak.io.ParquetOutput.openWriter;
import static org.mzpeak.io.ParquetOutput.signalFooter;

/**
 * Writes the MS-Numpress chunk layout of {@link MzPeakWriter} for spectra_data and spectra_peaks: chunks of up to
 * 500 points, never a single-point chunk unless the spectrum has one point (501 points are written as 499 + 2, since
 * the HEAD Python reader's pynumpress cannot decode the 12-byte linear payload of a single value), m/z encoded with
 * Numpress linear and intensity with Numpress SLOF or PIC, each as a {@code list<uint8>} byte list. A file has the
 * column of its own intensity encoding only, and every row an empty {@code mz_chunk_values} list.
 * {@code mz_chunk_start} and {@code mz_chunk_end} are the chunk's first and last m/z, both inclusive, as the
 * reference writes them. Each file's footer holds the array index of its columns and the counts
 * ({@link ParquetOutput#signalFooter}).
 */
final class NumpressSignalWriter {

    private static final String SLOF_COLUMN = "intensity_numpress_slof_bytes";
    private static final String PIC_COLUMN = "intensity_numpress_pic_bytes";

    /** Schemas of the Numpress chunk layout, one per intensity encoding: a file holds only its own transform column. */
    private static final MessageType SLOF_CHUNK_SCHEMA = chunkSchema(SLOF_COLUMN);
    private static final MessageType PIC_CHUNK_SCHEMA = chunkSchema(PIC_COLUMN);
    /** The array indexes of the schemas above. */
    private static final ArrayIndex SLOF_CHUNK_INDEX = chunkIndex(SLOF_COLUMN, MSNumpress.ACC_NUMPRESS_SLOF);
    private static final ArrayIndex PIC_CHUNK_INDEX = chunkIndex(PIC_COLUMN, MSNumpress.ACC_NUMPRESS_PIC);

    /** Maximum number of m/z points per numpress chunk. */
    private static final int NUMPRESS_CHUNK_SIZE = 500;
    /** Numpress linear fixed-point scale (near-lossless for MS m/z up to ~6000 Da at 5 sig figs). */
    private static final double NUMPRESS_LINEAR_FIXED_POINT = 100_000.0;

    /** Max value for a uint16 SLOF symbol — SLOF encodes {@code log(x+1)*fixedPoint} into 2 bytes.
     * The fixedPoint is chosen dynamically so the maximum log value maps to ≤65535.
     */
    private static final int SLOF_MAX_SYMBOL = 65535;

    private NumpressSignalWriter() {
    }

    /**
     * The chunk schema (read by the Numpress path of {@link ChunkRowDecoder}) with {@code intensityColumn} as the
     * only intensity transform. {@code mz_chunk_values} is the spec's chunk values column, present once in every
     * chunk layout; the m/z of a Numpress row are in {@code mz_numpress_linear_bytes}, so it is written empty.
     */
    private static MessageType chunkSchema(String intensityColumn) {
        return Types.buildMessage()
                .addField(Types.requiredGroup()
                        .required(PrimitiveTypeName.INT64).as(UINT64).named("spectrum_index")
                        .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_start")
                        .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_end")
                        .addField(Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                                .repeatedGroup()
                                .optional(PrimitiveTypeName.DOUBLE).named("item")
                                .named("list")
                                .named("mz_chunk_values"))
                        .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType())
                        .named("chunk_encoding")
                        .addField(numpressByteListField("mz_numpress_linear_bytes"))
                        .addField(numpressByteListField(intensityColumn))
                        .named("chunk"))
                .named("chunk_data");
    }

    /**
     * The array index of {@link #chunkSchema}, one entry per column in schema order: the spectrum index; the m/z
     * axis columns, chunk start, end, values and encoding, and the linear bytes as the MS:1002312 transform; and the
     * intensity, in detector counts, as {@code intensityTransform}.
     */
    private static ArrayIndex chunkIndex(String intensityColumn, String intensityTransform) {
        return ArrayIndex.builder(ArrayIndex.CHUNK_PREFIX, SignalEntity.SPECTRUM)
                .index("spectrum_index")
                .axis("mz_chunk_start", BufferFormat.CHUNK_START, null)
                .axis("mz_chunk_end", BufferFormat.CHUNK_END, null)
                .axis("mz_chunk_values", BufferFormat.CHUNK_VALUES, null)
                .axis("chunk_encoding", BufferFormat.CHUNK_ENCODING, null)
                .axis("mz_numpress_linear_bytes", BufferFormat.CHUNK_TRANSFORM, MSNumpress.ACC_NUMPRESS_LINEAR)
                .intensity(intensityColumn, BufferFormat.CHUNK_TRANSFORM, intensityTransform, ArrayIndex.COUNTS_UNIT)
                .build();
    }

    /** A {@code list<uint8>} column: repeated INT32 annotated as unsigned 8-bit inside a LIST group. */
    private static Type numpressByteListField(String name) {
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .repeatedGroup()
                .required(PrimitiveTypeName.INT32).as(LogicalTypeAnnotation.intType(8, false)).named("item")
                .named("list")
                .named(name);
    }

    /**
     * Write spectra as MS-Numpress chunk rows: the plan's spectra_data rows ({@code mz()}/{@code intensity()} of
     * every entry routed there) or, for {@code centroidFile}, its spectra_peaks rows ({@code mz()}/{@code intensity()}
     * or {@code peaks()} of every entry routed there, as the entry says). Each spectrum is split into chunks of up to
     * 500 points, the last two 499 + 2 where a single point would be left over; m/z is encoded with Numpress linear
     * and intensity with the plan's Numpress intensity encoding. The rows are read by the Numpress path of
     * {@link ChunkRowDecoder}. {@code mz()}/{@code intensity()} are written in the order of the entry's
     * {@link WritePlan.SignalArrays}. The codecs encode only the values {@link WritePlanner#requireNumpressEncodable}
     * lets into the plan: finite m/z and intensities of at least 0, and PIC counts up to 2147483647.
     */
    static void writeNumpressChunks(Path file, WritePlan plan, boolean centroidFile) throws IOException {
        NumpressIntensityEncoding intensityEncoding = plan.options().numpressIntensity();
        MessageType schema = intensityEncoding == NumpressIntensityEncoding.PIC ? PIC_CHUNK_SCHEMA : SLOF_CHUNK_SCHEMA;
        ArrayIndex index = intensityEncoding == NumpressIntensityEncoding.PIC ? PIC_CHUNK_INDEX : SLOF_CHUNK_INDEX;
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        Map<String, String> footer = signalFooter(index, plan.spectra().size(), plan.spectrumPoints(centroidFile));
        try (ParquetWriter<Group> writer = openWriter(file, schema, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                Spectrum s = entry.source();
                if (centroidFile ? !entry.peaks() : !entry.data()) {
                    continue;
                }
                double[] mz;
                double[] intensity;
                if (centroidFile && !entry.peaksFromArrays()) {
                    mz = new double[s.peaks().size()];
                    intensity = new double[s.peaks().size()];
                    for (int i = 0; i < mz.length; i++) {
                        mz[i] = s.peaks().get(i).mz();
                        intensity[i] = s.peaks().get(i).intensity();
                    }
                } else {
                    mz = entry.arrays().x();
                    intensity = entry.arrays().intensity();
                }
                int n = mz.length;
                int pos = 0;
                while (pos < n) {
                    int end = Math.min(pos + NUMPRESS_CHUNK_SIZE, n);
                    if (n - end == 1) {
                        // No single-point last chunk: its 12-byte linear payload is one pynumpress cannot decode.
                        // This chunk is a full one here, so it keeps NUMPRESS_CHUNK_SIZE - 1 points.
                        end--;
                    }
                    int chunkLen = end - pos;
                    double[] chunkMz = Arrays.copyOfRange(mz, pos, end);
                    double[] chunkIn = Arrays.copyOfRange(intensity, pos, end);

                    // Encode m/z with Numpress linear; max output = 8 (header) + 5 bytes/value
                    byte[] mzBuf = new byte[8 + chunkLen * 5];
                    int mzLen = MSNumpress.encodeLinear(chunkMz, chunkLen, mzBuf, NUMPRESS_LINEAR_FIXED_POINT);

                    // Encode intensity with SLOF or PIC depending on the caller's choice.
                    final byte[] inBuf;
                    final int inLen;
                    final String inColumn;
                    if (intensityEncoding == NumpressIntensityEncoding.PIC) {
                        // PIC: 8-byte header + variable bytes; max = 8 + 5*n (worst case)
                        inBuf = new byte[8 + chunkLen * 5];
                        inLen = MSNumpress.encodePic(chunkIn, chunkLen, inBuf);
                        inColumn = PIC_COLUMN;
                    } else {
                        // SLOF: dynamic fixedPoint so log(max_in+1)*fp fits in uint16 (max 65535).
                        double maxIn = 0;
                        for (double v : chunkIn) { if (v > maxIn) maxIn = v; }
                        double slofFixed = maxIn <= 0 ? 1.0 : Math.floor(SLOF_MAX_SYMBOL / Math.log(maxIn + 1));
                        inBuf = new byte[8 + chunkLen * 2];
                        inLen = MSNumpress.encodeSlof(chunkIn, chunkLen, inBuf, slofFixed);
                        inColumn = SLOF_COLUMN;
                    }

                    Group row = factory.newGroup();
                    Group chunk = row.addGroup("chunk");
                    chunk.add("spectrum_index", entry.sourceIndex());
                    chunk.add("mz_chunk_start", chunkMz[0]);
                    chunk.add("mz_chunk_end", chunkMz[chunkLen - 1]);
                    chunk.addGroup("mz_chunk_values"); // an empty list, not null
                    chunk.add("chunk_encoding", MSNumpress.ACC_NUMPRESS_LINEAR);

                    // Write mz_numpress_linear_bytes as repeated-group list (one "list" Group per byte)
                    Group mzBytesWrapper = chunk.addGroup("mz_numpress_linear_bytes");
                    for (int i = 0; i < mzLen; i++) {
                        mzBytesWrapper.addGroup("list").add("item", mzBuf[i] & 0xFF);
                    }

                    // Write the chosen intensity column (SLOF or PIC)
                    Group inBytesWrapper = chunk.addGroup(inColumn);
                    for (int i = 0; i < inLen; i++) {
                        inBytesWrapper.addGroup("list").add("item", inBuf[i] & 0xFF);
                    }

                    writer.write(row);
                    pos = end;
                }
            }
        }
    }
}
