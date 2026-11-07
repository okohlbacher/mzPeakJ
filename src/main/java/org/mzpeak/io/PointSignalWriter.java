package org.mzpeak.io;

import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Types;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.mzpeak.io.ParquetOutput.UINT64;
import static org.mzpeak.io.ParquetOutput.openWriter;
import static org.mzpeak.io.SpectrumSummary.peakRows;
import static org.mzpeak.io.SpectrumSummary.profileRows;

/**
 * Writes the point layout of {@link MzPeakWriter}: one row per point, carrying the entity index, the axis value
 * and the intensity, for spectra (spectra_data and spectra_peaks), chromatograms and wavelength spectra.
 */
final class PointSignalWriter {

    private static final MessageType SPECTRUM_DATA_SCHEMA = pointSchema("spectrum_index", "mz");
    private static final MessageType CHROM_DATA_SCHEMA = pointSchema("chromatogram_index", "time");
    private static final MessageType WAVELENGTH_DATA_SCHEMA = pointSchema("wavelength_spectrum_index", "wavelength");

    private PointSignalWriter() {
    }

    private static MessageType pointSchema(String indexField, String xField) {
        return Types.buildMessage()
                .addField(Types.requiredGroup()
                        .required(PrimitiveTypeName.INT64).as(UINT64).named(indexField)
                        .optional(PrimitiveTypeName.DOUBLE).named(xField)
                        .optional(PrimitiveTypeName.DOUBLE).named("intensity")
                        .named("point"))
                .named("data");
    }

    static void writeSpectrumPoints(Path file, List<Spectrum> spectra, boolean centroidFile)
            throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(SPECTRUM_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, SPECTRUM_DATA_SCHEMA)) {
            for (Spectrum s : spectra) {
                if (centroidFile) {
                    if (peakRows(s) == 0) {
                        continue;
                    }
                    if (!s.peaks().isEmpty()) {
                        for (CentroidPeak peak : s.peaks()) {
                            writePoint(writer, factory, "spectrum_index", s.index(), "mz", peak.mz(), peak.intensity());
                        }
                    } else {
                        // centroid spectrum whose centroids are carried in the mz/intensity arrays
                        double[] mz = s.mz();
                        double[] intensity = s.intensity();
                        for (int i = 0; i < mz.length; i++) {
                            writePoint(writer, factory, "spectrum_index", s.index(), "mz", mz[i], intensity[i]);
                        }
                    }
                } else {
                    if (profileRows(s) == 0) {
                        continue;
                    }
                    double[] mz = s.mz();
                    double[] intensity = s.intensity();
                    for (int i = 0; i < mz.length; i++) {
                        writePoint(writer, factory, "spectrum_index", s.index(), "mz", mz[i], intensity[i]);
                    }
                }
            }
        }
    }

    static void writeChromatogramData(Path file, List<Chromatogram> chromatograms) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(CHROM_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, CHROM_DATA_SCHEMA)) {
            for (Chromatogram c : chromatograms) {
                double[] time = c.time();
                double[] intensity = c.intensity();
                for (int i = 0; i < time.length; i++) {
                    writePoint(writer, factory, "chromatogram_index", c.index(), "time", time[i], intensity[i]);
                }
            }
        }
    }

    static void writeWavelengthData(Path file, List<WavelengthSpectrum> spectra) throws IOException {
        SimpleGroupFactory factory = new SimpleGroupFactory(WAVELENGTH_DATA_SCHEMA);
        try (ParquetWriter<Group> writer = openWriter(file, WAVELENGTH_DATA_SCHEMA)) {
            for (WavelengthSpectrum w : spectra) {
                double[] wl = w.wavelength();
                double[] intensity = w.intensity();
                for (int i = 0; i < wl.length; i++) {
                    writePoint(writer, factory, "wavelength_spectrum_index", w.index(), "wavelength",
                            wl[i], intensity[i]);
                }
            }
        }
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
