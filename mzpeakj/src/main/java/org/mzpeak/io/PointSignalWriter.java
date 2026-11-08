package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.mzpeak.io.ArrayIndex.BufferFormat;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.ParquetOutput.SignalFile;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.IntensityColumn;
import org.mzpeak.io.WritePlan.SpectrumSignal;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.mzpeak.io.ParquetOutput.UINT64;

/**
 * Writes the point layout of {@link MzPeakWriter}: one row per point, carrying the entity index, the axis value
 * and the intensity, for spectra (spectra_data and spectra_peaks, appended one spectrum at a time), chromatograms and
 * wavelength spectra; a spectrum or chromatogram row also carries the plan's extra columns, null where the entity has
 * no such array. A chromatogram row holds its intensity in the plan's intensity column of the chromatogram's unit and
 * null in the others. Each file's footer holds the array index of its columns and the counts
 * ({@link ParquetOutput.SignalFile}).
 */
final class PointSignalWriter {

    /** The intensity column of the spectrum signal files: detector counts. */
    private static final List<IntensityColumn> SPECTRUM_INTENSITY = List.of(
            new IntensityColumn(ArrayIndex.COUNTS_UNIT, "intensity", true));
    /** The intensity column of wavelength_spectra_data, whose unit is unknown. */
    private static final List<IntensityColumn> WAVELENGTH_INTENSITY = List.of(
            new IntensityColumn(null, "intensity", true));

    private static final MessageType WAVELENGTH_DATA_SCHEMA = pointSchema("wavelength_spectrum_index", "wavelength",
            WAVELENGTH_INTENSITY, List.of());

    /**
     * The array index of the schema above; the spectrum and chromatogram schemas and indexes are built for each write,
     * with its intensity and extra columns.
     */
    private static final ArrayIndex WAVELENGTH_DATA_INDEX = pointIndex(SignalEntity.WAVELENGTH_SPECTRUM, "wavelength",
            WAVELENGTH_INTENSITY, List.of());

    /** The values of no extra column, for the rows of wavelength_spectra_data. */
    private static final double[][] NO_EXTRA_VALUES = new double[0][];

    private PointSignalWriter() {
    }

    private static ArrayIndex pointIndex(SignalEntity entity, String xField, List<IntensityColumn> intensities,
                                         List<ExtraColumn> extras) {
        ArrayIndex.Builder builder = ArrayIndex.builder(ArrayIndex.POINT_PREFIX, entity)
                .index(entity.indexField())
                .axis(xField, BufferFormat.POINT, null);
        for (IntensityColumn intensity : intensities) {
            if (intensity.primary()) {
                builder.intensity(intensity.column(), BufferFormat.POINT, null, intensity.unit());
            } else {
                builder.secondaryIntensity(intensity.column(), BufferFormat.POINT, intensity.unit());
            }
        }
        for (ExtraColumn extra : extras) {
            builder.extra(extra.column(), extra.arrayType(), extra.arrayName(), extra.unit(), BufferFormat.POINT);
        }
        return builder.build();
    }

    private static MessageType pointSchema(String indexField, String xField, List<IntensityColumn> intensities,
                                           List<ExtraColumn> extras) {
        var point = Types.requiredGroup()
                .required(PrimitiveTypeName.INT64).as(UINT64).named(indexField)
                .optional(PrimitiveTypeName.DOUBLE).named(xField);
        for (IntensityColumn intensity : intensities) {
            point = point.optional(PrimitiveTypeName.DOUBLE).named(intensity.column());
        }
        for (ExtraColumn extra : extras) {
            point = point.optional(PrimitiveTypeName.DOUBLE).named(extra.column());
        }
        return Types.buildMessage().addField(point.named("point")).named("data");
    }

    /**
     * Opens spectra_data or spectra_peaks in the point layout, with a column for each of {@code extras}, the plan's
     * extra columns, for {@link #appendSpectrumPoints}.
     */
    static SignalFile openSpectrumPoints(Path file, List<ExtraColumn> extras, Options options) throws IOException {
        return new SignalFile(file, pointSchema("spectrum_index", "mz", SPECTRUM_INTENSITY, extras),
                pointIndex(SignalEntity.SPECTRUM, "mz", SPECTRUM_INTENSITY, extras), options);
    }

    /**
     * Append to {@code file}, opened by {@link #openSpectrumPoints} with {@code extras}, the rows {@code spectrum}
     * writes to spectra_data ({@code mz()}/{@code intensity()}) or, for {@code peaksFile}, to spectra_peaks
     * ({@code mz()}/{@code intensity()} or {@code peaks()}, as its entry says), each with the values of the extra
     * columns ({@link SpectrumSignal#extraValues}), and add them to the file's points. {@code mz()}/{@code intensity()}
     * are written in the order of the spectrum's {@link WritePlan.SignalArrays}.
     */
    static void appendSpectrumPoints(SignalFile file, SpectrumSignal spectrum, List<ExtraColumn> extras,
                                     boolean peaksFile) throws IOException {
        long index = spectrum.entry().sourceIndex();
        double[][] extraValues = spectrum.extraValues(extras, peaksFile);
        int points;
        if (peaksFile && !spectrum.entry().peaksFromArrays()) {
            List<CentroidPeak> peaks = spectrum.source().peaks();
            points = peaks.size();
            for (int i = 0; i < points; i++) {
                writePoint(file, "spectrum_index", index, "mz", peaks.get(i).mz(), "intensity",
                        peaks.get(i).intensity(), extras, extraValues, i);
            }
        } else {
            double[] mz = spectrum.arrays().x();
            double[] intensity = spectrum.arrays().intensity();
            points = mz.length;
            for (int i = 0; i < points; i++) {
                writePoint(file, "spectrum_index", index, "mz", mz[i], "intensity", intensity[i], extras,
                        extraValues, i);
            }
        }
        file.addPoints(points);
    }

    /**
     * Write the plan's chromatograms_data rows: each chromatogram's time and intensity in the order of its
     * {@link WritePlan.SignalArrays}, the intensity in the column of its unit ({@link WritePlan#intensityColumn}),
     * with the values of the plan's extra columns from its {@link Chromatogram#arrays()} in the same order.
     */
    static void writeChromatogramData(Path file, WritePlan plan) throws IOException {
        List<IntensityColumn> intensities = plan.chromatogramIntensities();
        List<ExtraColumn> extras = plan.chromatogramExtras();
        try (SignalFile out = new SignalFile(file, pointSchema("chromatogram_index", "time", intensities, extras),
                pointIndex(SignalEntity.CHROMATOGRAM, "time", intensities, extras), plan.options())) {
            out.setEntityCount(plan.chromatograms().size());
            for (Entry<Chromatogram> entry : plan.chromatograms()) {
                Chromatogram c = entry.source();
                String intensityColumn = plan.intensityColumn(c);
                double[][] extraValues = new double[extras.size()][];
                for (int k = 0; k < extraValues.length; k++) {
                    extraValues[k] = entry.arrays().inWrittenOrder(c.arrays().get(extras.get(k).key()));
                }
                double[] time = entry.arrays().x();
                double[] intensity = entry.arrays().intensity();
                for (int i = 0; i < time.length; i++) {
                    writePoint(out, "chromatogram_index", entry.sourceIndex(), "time", time[i], intensityColumn,
                            intensity[i], extras, extraValues, i);
                }
                out.addPoints(time.length);
            }
        }
    }

    static void writeWavelengthData(Path file, WritePlan plan) throws IOException {
        try (SignalFile out = new SignalFile(file, WAVELENGTH_DATA_SCHEMA, WAVELENGTH_DATA_INDEX, plan.options())) {
            out.setEntityCount(plan.wavelengthSpectra().size());
            for (Entry<WavelengthSpectrum> entry : plan.wavelengthSpectra()) {
                double[] wl = entry.arrays().x();
                double[] intensity = entry.arrays().intensity();
                for (int i = 0; i < wl.length; i++) {
                    writePoint(out, "wavelength_spectrum_index", entry.sourceIndex(), "wavelength", wl[i],
                            "intensity", intensity[i], List.of(), NO_EXTRA_VALUES, i);
                }
                out.addPoints(wl.length);
            }
        }
    }

    /**
     * One row: the entity {@code index}, the axis value {@code x}, the {@code intensity} in {@code intensityField},
     * and the point {@code row} of {@code extraValues}, each array the values of the extra column at its position of
     * {@code extras}, written where the array is not null. Every other column of the row is null.
     */
    private static void writePoint(SignalFile file, String indexField, long index, String xField, double x,
                                   String intensityField, double intensity, List<ExtraColumn> extras,
                                   double[][] extraValues, int row) throws IOException {
        Group group = file.newRow();
        Group point = group.addGroup("point");
        point.add(indexField, index);
        point.add(xField, x);
        point.add(intensityField, intensity);
        for (int k = 0; k < extraValues.length; k++) {
            if (extraValues[k] != null) {
                point.add(extras.get(k).column(), extraValues[k][row]);
            }
        }
        file.write(group);
    }
}
