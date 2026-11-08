package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mzpeak.io.ParquetOutput.UINT64;
import static org.mzpeak.io.ParquetOutput.openWriter;
import static org.mzpeak.io.ParquetOutput.signalFooter;

/**
 * Writes the point layout of {@link MzPeakWriter}: one row per point, carrying the entity index, the axis value
 * and the intensity, for spectra (spectra_data and spectra_peaks), chromatograms and wavelength spectra; a spectrum
 * row also carries the plan's extra columns, null where the spectrum has no such array. Each file's footer holds the
 * array index of its columns and the counts ({@link ParquetOutput#signalFooter}).
 */
final class PointSignalWriter {

    private static final MessageType CHROM_DATA_SCHEMA = pointSchema("chromatogram_index", "time", List.of());
    private static final MessageType WAVELENGTH_DATA_SCHEMA = pointSchema("wavelength_spectrum_index", "wavelength",
            List.of());

    /**
     * The array indexes of the schemas above, whose intensity unit is unknown; spectrum intensity is in detector
     * counts, and the spectrum schema and index are built for each write, with its extra columns.
     */
    private static final ArrayIndex CHROM_DATA_INDEX = pointIndex(SignalEntity.CHROMATOGRAM, "time", null, List.of());
    private static final ArrayIndex WAVELENGTH_DATA_INDEX = pointIndex(SignalEntity.WAVELENGTH_SPECTRUM, "wavelength",
            null, List.of());

    private PointSignalWriter() {
    }

    private static ArrayIndex pointIndex(SignalEntity entity, String xField, String intensityUnit,
                                         List<ExtraColumn> extras) {
        ArrayIndex.Builder builder = ArrayIndex.builder(ArrayIndex.POINT_PREFIX, entity)
                .index(entity.indexField())
                .axis(xField, BufferFormat.POINT, null)
                .intensity("intensity", BufferFormat.POINT, null, intensityUnit);
        for (ExtraColumn extra : extras) {
            builder.extra(extra.column(), extra.arrayType(), extra.arrayName(), extra.unit(), BufferFormat.POINT);
        }
        return builder.build();
    }

    private static MessageType pointSchema(String indexField, String xField, List<ExtraColumn> extras) {
        var point = Types.requiredGroup()
                .required(PrimitiveTypeName.INT64).as(UINT64).named(indexField)
                .optional(PrimitiveTypeName.DOUBLE).named(xField)
                .optional(PrimitiveTypeName.DOUBLE).named("intensity");
        for (ExtraColumn extra : extras) {
            point = point.optional(PrimitiveTypeName.DOUBLE).named(extra.column());
        }
        return Types.buildMessage().addField(point.named("point")).named("data");
    }

    /**
     * Write the plan's spectra_data rows ({@code mz()}/{@code intensity()} of every entry routed there) or, for
     * {@code centroidFile}, its spectra_peaks rows ({@code mz()}/{@code intensity()} or {@code peaks()} of every
     * entry routed there, as the entry says), each with the values of the plan's extra columns
     * ({@link SpectrumEntry#extraValues}). {@code mz()}/{@code intensity()} are written in the order of the entry's
     * {@link WritePlan.SignalArrays}.
     */
    static void writeSpectrumPoints(Path file, WritePlan plan, boolean centroidFile) throws IOException {
        List<ExtraColumn> extras = plan.spectrumExtras();
        MessageType schema = pointSchema("spectrum_index", "mz", extras);
        SimpleGroupFactory factory = new SimpleGroupFactory(schema);
        Map<String, String> footer = signalFooter(pointIndex(SignalEntity.SPECTRUM, "mz", ArrayIndex.COUNTS_UNIT,
                extras), plan.spectra().size(), plan.spectrumPoints(centroidFile));
        try (ParquetWriter<Group> writer = openWriter(file, schema, footer, plan.options())) {
            for (SpectrumEntry entry : plan.spectra()) {
                Spectrum s = entry.source();
                if (centroidFile ? !entry.peaks() : !entry.data()) {
                    continue;
                }
                double[][] extraValues = entry.extraValues(extras, centroidFile);
                if (centroidFile && !entry.peaksFromArrays()) {
                    List<CentroidPeak> peaks = s.peaks();
                    for (int i = 0; i < peaks.size(); i++) {
                        writeSpectrumPoint(writer, factory, entry.sourceIndex(), peaks.get(i).mz(),
                                peaks.get(i).intensity(), extras, extraValues, i);
                    }
                } else {
                    double[] mz = entry.arrays().x();
                    double[] intensity = entry.arrays().intensity();
                    for (int i = 0; i < mz.length; i++) {
                        writeSpectrumPoint(writer, factory, entry.sourceIndex(), mz[i], intensity[i], extras,
                                extraValues, i);
                    }
                }
            }
        }
    }

    /**
     * One spectrum row: the point {@code row} of {@code extraValues}, each array the values of the extra column at
     * its position of {@code extras}, written where the array is not null.
     */
    private static void writeSpectrumPoint(ParquetWriter<Group> writer, SimpleGroupFactory factory, long index,
                                           double mz, double intensity, List<ExtraColumn> extras,
                                           double[][] extraValues, int row) throws IOException {
        Group group = factory.newGroup();
        Group point = group.addGroup("point");
        point.add("spectrum_index", index);
        point.add("mz", mz);
        point.add("intensity", intensity);
        for (int k = 0; k < extraValues.length; k++) {
            if (extraValues[k] != null) {
                point.add(extras.get(k).column(), extraValues[k][row]);
            }
        }
        writer.write(group);
    }

    static void writeChromatogramData(Path file, WritePlan plan) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(CHROM_DATA_SCHEMA);
        Map<String, String> footer = signalFooter(CHROM_DATA_INDEX, plan.chromatograms().size(),
                points(plan.chromatograms()));
        try (ParquetWriter<Group> writer = openWriter(file, CHROM_DATA_SCHEMA, footer, plan.options())) {
            for (Entry<Chromatogram> entry : plan.chromatograms()) {
                double[] time = entry.arrays().x();
                double[] intensity = entry.arrays().intensity();
                for (int i = 0; i < time.length; i++) {
                    writePoint(writer, factory, "chromatogram_index", entry.sourceIndex(), "time", time[i],
                            intensity[i]);
                }
            }
        }
    }

    static void writeWavelengthData(Path file, WritePlan plan) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(WAVELENGTH_DATA_SCHEMA);
        Map<String, String> footer = signalFooter(WAVELENGTH_DATA_INDEX, plan.wavelengthSpectra().size(),
                points(plan.wavelengthSpectra()));
        try (ParquetWriter<Group> writer = openWriter(file, WAVELENGTH_DATA_SCHEMA, footer, plan.options())) {
            for (Entry<WavelengthSpectrum> entry : plan.wavelengthSpectra()) {
                double[] wl = entry.arrays().x();
                double[] intensity = entry.arrays().intensity();
                for (int i = 0; i < wl.length; i++) {
                    writePoint(writer, factory, "wavelength_spectrum_index", entry.sourceIndex(), "wavelength",
                            wl[i], intensity[i]);
                }
            }
        }
    }

    /** The points of {@code entries}, one row each: the length of every entry's axis, summed. */
    private static long points(List<? extends Entry<?>> entries) {
        long points = 0;
        for (Entry<?> entry : entries) {
            points += entry.arrays().x().length;
        }
        return points;
    }

    private static void writePoint(ParquetWriter<Group> writer, SimpleGroupFactory factory,
                                   String indexField, long index, String xField, double x, double intensity)
            throws IOException {
        Group row = factory.newGroup();
        Group point = row.addGroup("point");
        point.add(indexField, index);
        point.add(xField, x);
        point.add("intensity", intensity);
        writer.write(row);
    }
}
