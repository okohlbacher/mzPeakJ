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
import org.mzpeak.io.MzPeakWriter.NumpressIntensityEncoding;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.Spectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static org.mzpeak.io.ParquetOutput.UINT64;
import static org.mzpeak.io.ParquetOutput.openWriter;

/**
 * Writes the MS-Numpress chunk layout of {@link MzPeakWriter} for spectra_data and spectra_peaks: chunks of up to
 * 500 points, m/z encoded with Numpress linear and intensity with Numpress SLOF or PIC.
 */
final class NumpressSignalWriter {

    /** Schema for Numpress-encoded chunk layout (compatible with SpectrumArrayStore.acceptNumpressChunk). */
    private static final MessageType NUMPRESS_CHUNK_SCHEMA = Types.buildMessage()
            .addField(Types.requiredGroup()
                    .required(PrimitiveTypeName.INT64).as(UINT64).named("spectrum_index")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_start")
                    .optional(PrimitiveTypeName.DOUBLE).named("mz_chunk_end")
                    .optional(PrimitiveTypeName.BINARY).as(LogicalTypeAnnotation.stringType()).named("chunk_encoding")
                    // large_list<uint8> columns — stored as repeated INT32 inside a LIST group
                    .addField(numpressByteListField("mz_numpress_linear_bytes"))
                    .addField(numpressByteListField("intensity_numpress_slof_bytes"))
                    .addField(numpressByteListField("intensity_numpress_pic_bytes"))
                    .named("chunk"))
            .named("chunk_data");

    /** Default number of m/z points per numpress chunk. */
    private static final int NUMPRESS_CHUNK_SIZE = 500;
    /** Numpress linear fixed-point scale (near-lossless for MS m/z up to ~6000 Da at 5 sig figs). */
    private static final double NUMPRESS_LINEAR_FIXED_POINT = 100_000.0;

    /** Max value for a uint16 SLOF symbol — SLOF encodes {@code log(x+1)*fixedPoint} into 2 bytes.
     * The fixedPoint is chosen dynamically so the maximum log value maps to ≤65535.
     */
    private static final int SLOF_MAX_SYMBOL = 65535;

    private NumpressSignalWriter() {
    }

    private static Type numpressByteListField(String name) {
        return Types.optionalGroup().as(LogicalTypeAnnotation.listType())
                .repeatedGroup()
                .required(PrimitiveTypeName.INT32).named("item")
                .named("list")
                .named(name);
    }

    /**
     * Write spectra as MS-Numpress chunk rows: the plan's spectra_data rows ({@code mz()}/{@code intensity()} of
     * every entry routed there) or, for {@code centroidFile}, its spectra_peaks rows ({@code peaks()} of every entry
     * routed there). Each spectrum is split into fixed-size chunks; m/z is encoded with Numpress linear and
     * intensity with the plan's Numpress intensity encoding. Output is compatible with
     * {@code SpectrumArrayStore.acceptNumpressChunk}.
     */
    static void writeNumpressChunks(Path file, WritePlan plan, boolean centroidFile) throws IOException {
        NumpressIntensityEncoding intensityEncoding = plan.options().numpressIntensity();
        SimpleGroupFactory factory = new SimpleGroupFactory(NUMPRESS_CHUNK_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, NUMPRESS_CHUNK_SCHEMA, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                Spectrum s = entry.source();
                double[] mz;
                double[] intensity;
                if (centroidFile) {
                    if (!entry.peaks()) {
                        continue;
                    }
                    mz = new double[s.peaks().size()];
                    intensity = new double[s.peaks().size()];
                    for (int i = 0; i < mz.length; i++) {
                        mz[i] = s.peaks().get(i).mz();
                        intensity[i] = s.peaks().get(i).intensity();
                    }
                } else {
                    if (!entry.data()) {
                        continue;
                    }
                    mz = s.mz();
                    intensity = s.intensity();
                }
                int n = mz.length;
                int pos = 0;
                while (pos < n) {
                    int end = Math.min(pos + NUMPRESS_CHUNK_SIZE, n);
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
                        inColumn = "intensity_numpress_pic_bytes";
                    } else {
                        // SLOF: dynamic fixedPoint so log(max_in+1)*fp fits in uint16 (max 65535).
                        double maxIn = 0;
                        for (double v : chunkIn) { if (v > maxIn) maxIn = v; }
                        double slofFixed = maxIn <= 0 ? 1.0 : Math.floor(SLOF_MAX_SYMBOL / Math.log(maxIn + 1));
                        inBuf = new byte[8 + chunkLen * 2];
                        inLen = MSNumpress.encodeSlof(chunkIn, chunkLen, inBuf, slofFixed);
                        inColumn = "intensity_numpress_slof_bytes";
                    }

                    Group row = factory.newGroup();
                    Group chunk = row.addGroup("chunk");
                    chunk.add("spectrum_index", entry.sourceIndex());
                    chunk.add("mz_chunk_start", chunkMz[0]);
                    chunk.add("mz_chunk_end", chunkMz[chunkLen - 1]);
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
