package org.mzpeak.mzml;

import ms.numpress.MSNumpress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakException;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.mzml.ModelAssert.Model;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * JZ-11, opt-in: pyteomics 5.0.2 with pynumpress as an external mzML reader (mzML design 12.6). It shares no code with
 * jmzML, so it checks the converter's reader and writer independently of each other. Runs only with
 * {@code -Dmzpeak.mzml.python=<uv>}, the path of a {@code uv} executable; without it every test is skipped. The
 * Python side is {@code src/test/resources/mzml/python/read_mzml.py} with the pinned requirements of
 * {@code uv-requirements.txt} next to it, run by
 * {@code uv run --no-project --with-requirements uv-requirements.txt python -I read_mzml.py}. pyteomics 5 parses PSI
 * formats only with a psims controlled vocabulary, so psims is pinned too (1.4.0, whose vendored PSI-MS 4.1.258 knows
 * every term the outputs use; 1.3.6's 4.1.172 lacks MS:1003812) and the script hands pyteomics that vendored OBO: no
 * remote OBO. For every spectrum it prints the id, MS level, n and the SHA-256 of the m/z (or wavelength) and
 * intensity arrays as {@code <f8}; these equal mzPeakJ's model:
 * <ul>
 *   <li>A: each of the 13 mzPeak fixtures converted by {@link MzMLConversion#toMzML}, with zlib and without, against
 *       the fixture as {@code MzPeakReader} reads it;
 *   <li>B: each of the 4 vendored mzML read by pyteomics against {@link MzMLReader}'s model, and converted by
 *       {@link MzMLConversion#toMzPeak} and back by {@link MzMLConversion#toMzML}, with zlib and without, against the
 *       intermediate mzPeak as {@code MzPeakReader} reads it.
 * </ul>
 * Guard 3: a hand-made mzML whose intensity array is the MS-Numpress PIC payload (MS:1002313, bytes from the vendored
 * {@link MSNumpress#encodePic}) of the counts 2147483647, 2147483648, 3e9 and 4294967294 decodes in pynumpress to
 * exactly those counts, while {@link MzMLReader#read} rejects it, naming the count 2147483648 and jmzML's
 * -2147483648.
 */
class PythonMzMLReaderTest {

    private static final String PROPERTY = "mzpeak.mzml.python";
    private static final long TIMEOUT_MINUTES = 30;

    private static String uv;

    @TempDir
    Path dir;

    @BeforeEach
    void uvIsGiven() {
        uv = System.getProperty(PROPERTY, "");
        assumeTrue(!uv.isBlank(), "-D" + PROPERTY + "=<uv> not set");
        assertThat(Path.of(uv)).as("-D%s", PROPERTY).isExecutable();
    }

    /** One spectrum as read_mzml.py prints it, and as the model gives it. */
    record Line(int file, String id, String msLevel, int n, String axis, String intensity) {
    }

    // ---- A: the 13 mzPeak fixtures ------------------------------------------------------------------------------

    @Test
    void directionAOutputsReadAsTheMzPeakModel() throws IOException, InterruptedException {
        List<Path> fixtures = new ArrayList<>();
        Fixtures.CURRENT_NAMES.forEach(n -> fixtures.add(Fixtures.current(n)));
        Fixtures.LEGACY_NAMES.forEach(n -> fixtures.add(Fixtures.legacy(n)));
        assertThat(fixtures).hasSize(13);
        List<Path> outputs = new ArrayList<>();
        List<Line> expected = new ArrayList<>();
        for (Path fixture : fixtures) {
            Model model = Model.load(fixture);
            for (boolean zlib : List.of(true, false)) {
                Path mzml = dir.resolve("a" + outputs.size() + ".mzML");
                MzMLConversion.toMzML(fixture, mzml, zlib);
                MzMLAssert.valid(mzml);
                expected.addAll(lines(outputs.size(), model));
                outputs.add(mzml);
            }
        }

        assertThat(python(outputs, false)).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---- B: the 4 vendored mzML ---------------------------------------------------------------------------------

    @Test
    void vendoredInputsAndDirectionBOutputsReadAsTheModel() throws IOException, InterruptedException {
        List<Path> files = new ArrayList<>();
        List<Line> expected = new ArrayList<>();
        Path sources = Files.createDirectories(dir.resolve("sources"));
        for (String name : MzMLFixtures.NAMES) {
            Path source = MzMLFixtures.extract(name, sources);
            expected.addAll(lines(files.size(), MzMLFixpointTest.mzmlModel(source)));
            files.add(source);
            Path mzpeak = dir.resolve("b" + files.size() + ".mzpeak");
            MzMLConversion.toMzPeak(source, mzpeak, false, MzPeakWriter.Options.defaults());
            Model written = Model.load(mzpeak);
            for (boolean zlib : List.of(true, false)) {
                Path mzml = dir.resolve("b" + files.size() + ".mzML");
                MzMLConversion.toMzML(mzpeak, mzml, zlib);
                MzMLAssert.valid(mzml);
                expected.addAll(lines(files.size(), written));
                files.add(mzml);
            }
        }
        assertThat(files).hasSize(4 * 3);

        assertThat(python(files, false)).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ---- guard 3 ------------------------------------------------------------------------------------------------

    @Test
    void picCountsOf2To31AndAboveDecodeInPynumpressAndAreRejectedByTheReader() throws IOException,
            InterruptedException {
        double[] counts = {2147483647.0, 2147483648.0, 3e9, 4294967294.0};
        byte[] buffer = new byte[5 * counts.length];
        byte[] pic = Arrays.copyOf(buffer, MSNumpress.encodePic(counts, counts.length, buffer));
        Path file = picDocument(pic);

        List<String> values = pythonValues(file, "scan=19");
        assertThat(values).containsExactly("2147483647.0", "2147483648.0", "3000000000.0", "4294967294.0");
        assertThat(values.stream().mapToDouble(Double::parseDouble).toArray()).containsExactly(counts);

        MzMLReader reader = new MzMLReader(file);
        assertThatThrownBy(() -> reader.read(new Discard()))
                .isInstanceOf(MzPeakException.class)
                .hasMessageContaining("spectrum #0 (scan=19)")
                .hasMessageContaining("MS:1002313")
                .hasMessageContaining("the count 2147483648, which jmzML 1.7.11 decodes as -2147483648");
    }

    /**
     * tiny with scan=19 cut to 4 points: m/z 100, 200, 300, 400 as uncompressed 64-bit floats and the intensities
     * {@code pic} as an MS-Numpress PIC payload (64-bit float, MS:1002313).
     */
    private Path picDocument(byte[] pic) throws IOException {
        String tiny = Files.readString(MzMLFixtures.extract(MzMLFixtures.TINY, dir), StandardCharsets.ISO_8859_1);
        int start = tiny.indexOf("id=\"scan=19\"");
        int from = tiny.indexOf("<binaryDataArrayList", start);
        int end = tiny.indexOf("</binaryDataArrayList>", from) + "</binaryDataArrayList>".length();
        String head = tiny.substring(start, from);
        assertThat(head).contains("defaultArrayLength=\"15\"");
        ByteBuffer mz = ByteBuffer.allocate(4 * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : new double[] {100, 200, 300, 400}) {
            mz.putDouble(v);
        }
        String arrays = "<binaryDataArrayList count=\"2\">\n"
                + array(cv("MS:1000523", "64-bit float") + cv("MS:1000576", "no compression")
                        + cv("MS:1000514", "m/z array", "MS:1000040", "m/z"), mz.array())
                + array(cv("MS:1000523", "64-bit float")
                        + cv("MS:1002313", "MS-Numpress positive integer compression")
                        + cv("MS:1000515", "intensity array", "MS:1000131", "number of detector counts"), pic)
                + "</binaryDataArrayList>";
        String edited = tiny.substring(0, start) + head.replace("defaultArrayLength=\"15\"", "defaultArrayLength=\"4\"")
                + arrays + tiny.substring(end);
        return Files.writeString(dir.resolve("pic.mzML"), edited, StandardCharsets.ISO_8859_1);
    }

    private static String array(String terms, byte[] binary) {
        String base64 = Base64.getEncoder().encodeToString(binary);
        return "<binaryDataArray encodedLength=\"" + base64.length() + "\">\n" + terms + "<binary>" + base64
                + "</binary>\n</binaryDataArray>\n";
    }

    private static String cv(String accession, String name) {
        return "<cvParam cvRef=\"MS\" accession=\"" + accession + "\" name=\"" + name + "\" value=\"\"/>\n";
    }

    private static String cv(String accession, String name, String unit, String unitName) {
        return "<cvParam cvRef=\"MS\" accession=\"" + accession + "\" name=\"" + name + "\" value=\"\" unitCvRef=\"MS\""
                + " unitAccession=\"" + unit + "\" unitName=\"" + unitName + "\"/>\n";
    }

    /** A visitor that keeps nothing. */
    private static final class Discard implements MzMLReader.Visitor {
        @Override
        public void spectrum(Spectrum spectrum) {
        }

        @Override
        public void wavelengthSpectrum(WavelengthSpectrum spectrum) {
        }

        @Override
        public void chromatogram(Chromatogram chromatogram) {
        }
    }

    // ---- the model side -----------------------------------------------------------------------------------------

    /**
     * The lines of every MS and wavelength spectrum of {@code model}, attributed to the file number {@code file}. An MS
     * level of 0 is the model's 'absent' (mzML design 5.4: written only when above 0), so it reads as '-', as a
     * wavelength spectrum's does (Example_Processed's 9 spectra).
     */
    private static List<Line> lines(int file, Model model) {
        List<Line> lines = new ArrayList<>();
        for (Spectrum s : model.spectrumList()) {
            int level = s.description().msLevel();
            lines.add(new Line(file, s.description().id(), level > 0 ? Integer.toString(level) : "-", s.mz().length,
                    sha256(s.mz()), sha256(s.intensity())));
        }
        for (WavelengthSpectrum w : model.wavelengthSpectra()) {
            lines.add(new Line(file, w.id(), "-", w.wavelength().length, sha256(w.wavelength()),
                    sha256(w.intensity())));
        }
        return lines;
    }

    /** The SHA-256 of {@code values} as little-endian 64-bit floats ({@code <f8}). */
    static String sha256(double[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(8 * values.length).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) {
            bytes.putDouble(v);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- the Python side ----------------------------------------------------------------------------------------

    /** The lines read_mzml.py prints for {@code files}. */
    private List<Line> python(List<Path> files, boolean values) throws IOException, InterruptedException {
        List<Line> lines = new ArrayList<>();
        for (String row : run(files, values)) {
            String[] f = row.split("\t", -1);
            assertThat(f).as(row).hasSize(values ? 7 : 6);
            lines.add(new Line(Integer.parseInt(f[0]), f[1], f[2], Integer.parseInt(f[3]), f[4], f[5]));
        }
        return lines;
    }

    /** The intensities read_mzml.py prints, as Python's repr of each, for the spectrum {@code id} of {@code file}. */
    private List<String> pythonValues(Path file, String id) throws IOException, InterruptedException {
        List<String> rows = run(List.of(file), true).stream().filter(r -> r.split("\t", -1)[1].equals(id)).toList();
        assertThat(rows).as("spectrum %s", id).hasSize(1);
        String[] f = rows.get(0).split("\t", -1);
        assertThat(f).hasSize(7);
        return List.of(f[6].split(","));
    }

    /** Runs read_mzml.py on {@code files} through uv and returns its stdout lines; fails unless it exits with 0. */
    private List<String> run(List<Path> files, boolean values) throws IOException, InterruptedException {
        Path script = resource("read_mzml.py");
        List<String> command = new ArrayList<>(List.of(uv, "run", "--no-project", "--with-requirements",
                resource("uv-requirements.txt").toString(), "python", "-I", script.toString()));
        if (values) {
            command.add("--values");
        }
        files.forEach(f -> command.add(f.toString()));
        Path logs = Files.createDirectories(dir.resolve("logs"));
        Path stdout = Files.createTempFile(logs, "stdout", ".txt");
        Path stderr = Files.createTempFile(logs, "stderr", ".txt");
        Process process = new ProcessBuilder(command).directory(logs.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out after " + TIMEOUT_MINUTES + " min: " + command);
        }
        assertThat(process.exitValue()).as("%s%n%s", command, Files.readString(stderr, StandardCharsets.UTF_8))
                .isZero();
        return Files.readAllLines(stdout, StandardCharsets.UTF_8);
    }

    /** The file of the class-path resource {@code /mzml/python/<name>}. */
    private static Path resource(String name) {
        try {
            return Path.of(PythonMzMLReaderTest.class.getResource("/mzml/python/" + name).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
