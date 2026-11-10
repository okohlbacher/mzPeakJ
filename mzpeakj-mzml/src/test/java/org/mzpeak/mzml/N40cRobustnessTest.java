package org.mzpeak.mzml;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mzpeak.io.MzPeakWriter;
import org.mzpeak.mzml.ModelAssert.Model;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mzpeak.mzml.ModelAssert.Mode.STRICT;

/**
 * JZ-11, opt-in: direction B on the n40C subset of the HUPO-PSI mzPeak repository (mzML design 5.2, 12.1, 12.6, Q2),
 * an indexedmzML of 101 spectra and 2 chromatograms whose 87 precursors include 6 that name the spectrum
 * {@code controllerType=0 controllerNumber=1 scan=7850}, which the subset does not hold (schema-invalid dangling
 * refs). Runs only with {@code -Dmzpeak.reference.dir=<a checkout of HUPO-PSI/mzPeak>}, the file being
 * {@code test/data/n40C_...subset.mzML} there; without it, or without the file, the test is skipped. Under UTC,
 * {@link MzMLConversion#toMzPeak} and {@link MzMLConversion#toMzML} complete, with zlib and without; each output is
 * {@link MzMLAssert#valid} (so its keyrefs hold), its 6 dangling refs come out as {@code externalSpectrumID} and its
 * other 81 as {@code spectrumRef} naming a spectrum of the output, and {@link MzMLReader} reads it back to the model
 * of the source, strictly.
 */
class N40cRobustnessTest {

    private static final String PROPERTY = "mzpeak.reference.dir";
    static final String N40C = "n40C_MS1_2pt7kV_15kOT_500-4000_MaxIT100_0to35A_140uL_RF100_MS2_1secCycle_OT7pt5k"
            + "_AGC3e5and22ms_InjVol6_60min_20210220140305.subset.mzML";
    private static final String DANGLING = "controllerType=0 controllerNumber=1 scan=7850";

    @TempDir
    Path dir;

    private TimeZone zone;

    @BeforeEach
    void utc() {
        zone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(zone);
    }

    @Test
    void directionBCompletesAndKeepsTheDanglingRefsExternal() throws IOException {
        String property = System.getProperty(PROPERTY, "");
        assumeTrue(!property.isBlank(), "-D" + PROPERTY + "=<HUPO-PSI/mzPeak checkout> not set");
        Path source = Path.of(property, "test", "data", N40C);
        assumeTrue(Files.isRegularFile(source), source + " not found");
        Precursors given = precursors(source);
        assertThat(given.spectra()).hasSize(101);
        assertThat(given.spectrumRefs()).hasSize(87);
        assertThat(given.spectrumRefs()).filteredOn(r -> !given.spectra().contains(r)).hasSize(6)
                .containsOnly(DANGLING);
        assertThat(given.external()).isEmpty();

        Model m1 = MzMLFixpointTest.mzmlModel(source);
        Path mzpeak = dir.resolve("n40c.mzpeak");
        MzMLConversion.Result toMzPeak = MzMLConversion.toMzPeak(source, mzpeak, false,
                MzPeakWriter.Options.defaults());
        assertThat(List.of(toMzPeak.spectra(), toMzPeak.wavelengthSpectra(), toMzPeak.chromatograms()))
                .isEqualTo(List.of(101L, 0L, 2L));
        System.out.println("N40cRobustnessTest toMzPeak: " + toMzPeak.warnings().stream()
                .filter(w -> !w.startsWith("L-")).toList());
        for (boolean zlib : List.of(true, false)) {
            Path mzml = dir.resolve("n40c-" + zlib + ".mzML");
            MzMLConversion.Result toMzML = MzMLConversion.toMzML(mzpeak, mzml, zlib);
            System.out.println("N40cRobustnessTest toMzML, zlib " + zlib + ": " + toMzML.warnings().stream()
                    .filter(w -> !w.startsWith("L-")).toList());
            assertThat(List.of(toMzML.spectra(), toMzML.wavelengthSpectra(), toMzML.chromatograms()))
                    .isEqualTo(List.of(101L, 0L, 2L));
            MzMLAssert.valid(mzml);

            Precursors written = precursors(mzml);
            assertThat(written.external()).as("externalSpectrumID").hasSize(6).containsOnly(DANGLING);
            assertThat(written.spectrumRefs()).as("spectrumRef").hasSize(81);
            assertThat(written.spectra()).containsAll(written.spectrumRefs()).doesNotContain(DANGLING);
            assertThat(written.spectrumRefs()).containsExactlyElementsOf(given.spectrumRefs().stream()
                    .filter(r -> !r.equals(DANGLING)).toList());

            ModelAssert.assertEqual("n40C B, zlib " + zlib, m1, MzMLFixpointTest.mzmlModel(mzml), STRICT);
        }
    }

    /** The spectrum ids and, in document order, the precursors' spectrumRef and externalSpectrumID values. */
    private record Precursors(Set<String> spectra, List<String> spectrumRefs, List<String> external) {
    }

    /** What {@code file} holds of {@link Precursors}, read with StAX, independent of jmzML. */
    private static Precursors precursors(Path file) throws IOException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        Set<String> spectra = new HashSet<>();
        List<String> refs = new ArrayList<>();
        List<String> external = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = factory.createXMLStreamReader(in);
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (r.getLocalName().equals("spectrum")) {
                    spectra.add(r.getAttributeValue(null, "id"));
                } else if (r.getLocalName().equals("precursor")) {
                    String ref = r.getAttributeValue(null, "spectrumRef");
                    String ext = r.getAttributeValue(null, "externalSpectrumID");
                    if (ref != null) {
                        refs.add(ref);
                    }
                    if (ext != null) {
                        external.add(ext);
                    }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            throw new IOException(e);
        }
        return new Precursors(Collections.unmodifiableSet(spectra), refs, external);
    }
}
