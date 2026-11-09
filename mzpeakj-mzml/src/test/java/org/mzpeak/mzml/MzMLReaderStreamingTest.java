package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.ChildJvm;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JZ-3: {@link MzMLReader} reads the run one spectrum at a time (mzML design 7): a plain mzML of 20,000 spectra is
 * surveyed and read in a child JVM with a 48 MB heap, so a regression that holds the run in memory fails the build.
 * The document is tiny's header (with its referenceableParamGroupList) and 20,000 copies of tiny's scan=19, with ids
 * {@code scan=1} ... {@code scan=20000} and indices 0 ... 19999, and no chromatogramList.
 */
class MzMLReaderStreamingTest {

    private static final int SPECTRA = 20_000;

    @Test
    void twentyThousandSpectraAreSurveyedAndReadIn48Megabytes(@TempDir Path dir) throws IOException,
            InterruptedException {
        Path mzml = generate(dir);

        ChildJvm.Result child = ChildJvm.run(Files.createDirectories(dir.resolve("logs")), List.of("-Xmx48m"),
                Child.class, mzml.toString());

        assertThat(child.exitStatus()).as(child.stderr()).isZero();
        assertThat(child.stdout()).isEqualTo("spectra 20000 values 600000" + System.lineSeparator());
    }

    /** Writes the document into {@code dir}, ISO-8859-1 as tiny, one spectrum at a time. */
    private static Path generate(Path dir) throws IOException {
        String tiny = Files.readString(MzMLFixtures.extract(MzMLFixtures.TINY, dir), StandardCharsets.ISO_8859_1);
        String head = tiny.substring(tiny.indexOf("<mzML"), tiny.indexOf("<spectrumList"));
        int start = tiny.indexOf("<spectrum index=\"0\" id=\"scan=19\"");
        String spectrum = tiny.substring(start, tiny.indexOf("</spectrum>", start) + "</spectrum>".length());
        String attributes = "index=\"0\" id=\"scan=19\"";
        assertThat(spectrum).startsWith("<spectrum " + attributes);
        Path mzml = dir.resolve("streaming.mzML");
        try (Writer out = Files.newBufferedWriter(mzml, StandardCharsets.ISO_8859_1)) {
            out.write("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
            out.write(head);
            out.write("<spectrumList count=\"" + SPECTRA + "\" defaultDataProcessingRef=\"pwiz_processing\">\n");
            for (int i = 0; i < SPECTRA; i++) {
                out.write(spectrum.replace(attributes, "index=\"" + i + "\" id=\"scan=" + (i + 1) + "\""));
                out.write('\n');
            }
            out.write("</spectrumList>\n</run>\n</mzML>\n");
        }
        return mzml;
    }

    /** The child JVM: surveys and reads the file {@code args[0]} and prints the spectra and values read. */
    static final class Child {
        public static void main(String[] args) {
            MzMLReader reader = new MzMLReader(Path.of(args[0]));
            int surveyed = reader.survey().spectrumIds().size();
            long[] counts = new long[2];
            reader.read(new MzMLReader.Visitor() {
                @Override
                public void spectrum(Spectrum spectrum) {
                    counts[0]++;
                    counts[1] += spectrum.mz().length + spectrum.intensity().length;
                }

                @Override
                public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
                    throw new IllegalStateException("no wavelength spectrum expected");
                }

                @Override
                public void chromatogram(Chromatogram chromatogram) {
                    throw new IllegalStateException("no chromatogram expected");
                }
            });
            if (surveyed != counts[0]) {
                throw new IllegalStateException("surveyed " + surveyed + " spectra, read " + counts[0]);
            }
            System.out.println("spectra " + counts[0] + " values " + counts[1]);
        }
    }
}
