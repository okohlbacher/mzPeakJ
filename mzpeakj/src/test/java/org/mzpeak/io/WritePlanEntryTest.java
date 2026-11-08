package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.ForwardBackwardCorpusTest.Source;
import org.mzpeak.io.MzPeakWriter.Options;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.io.WritePlan.SpectrumSignal;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link WritePlan.SpectrumEntry} is light (design §7, MZ-10): no record component, followed through the components
 * of every record it reaches, has the type {@link Spectrum}, {@code double[]} or {@code List<CentroidPeak>}, so an
 * entry cannot retain a spectrum's signal, and it holds what the metadata members write: the caller's description,
 * the row counts of each signal file and the summary values derived from the payload.
 *
 * <p>Oracle: the walk itself is checked on {@link WritePlan.SpectrumSignal}, which holds a spectrum and its arrays,
 * and on a record holding a peak list; the entries are checked against the spectra of legacy small.unpacked, whose
 * MS1 spectra are profile spectra written to spectra_data and whose MS2 spectra are centroid peak copies written to
 * spectra_peaks.
 */
class WritePlanEntryTest {

    /** A record that holds a peak list, to check that the walk finds one. */
    private record Peaks(List<CentroidPeak> peaks) {
    }

    /** A record that holds a peak list one record down, to check that the walk follows components. */
    private record Nested(String name, List<Peaks> peaks) {
    }

    @Test
    void aSpectrumEntryHasNoComponentThatHoldsSignal() {
        Walk walk = Walk.of(SpectrumEntry.class);
        assertThat(walk.signal).isEmpty();
        assertThat(walk.records).contains(SpectrumEntry.class, SpectrumDescription.class, ScanEvent.class,
                ScanWindow.class, Precursor.class, IsolationWindow.class, SelectedIon.class, Activation.class,
                Param.class, SpectrumSummary.Values.class);
    }

    @Test
    void theWalkFindsSignalComponents() {
        assertThat(Walk.of(SpectrumSignal.class).signal)
                .containsExactly("SpectrumSignal.source: Spectrum", "SpectrumSignal.arrays.x: double[]",
                        "SpectrumSignal.arrays.intensity: double[]");
        assertThat(Walk.of(Peaks.class).signal).containsExactly("Peaks.peaks: List<CentroidPeak>");
        assertThat(Walk.of(Nested.class).signal).containsExactly("Nested.peaks.peaks: List<CentroidPeak>");
    }

    @Test
    void anEntryKeepsTheDescriptionRowCountsAndSummaryOfItsSpectrum() {
        Source small = ForwardBackwardCorpusTest.readSource(Fixtures.legacy("small.unpacked.mzpeak"));
        WritePlan plan = WritePlanner.plan(small.spectra, List.of(), List.of(), List.of(), small.fileMetadata,
                Options.defaults());
        assertThat(plan.spectra()).hasSameSizeAs(small.spectra);
        assertThat(plan.spectrumSignals()).hasSameSizeAs(small.spectra);
        int profile = 0;
        int centroid = 0;
        for (int i = 0; i < small.spectra.size(); i++) {
            Spectrum s = small.spectra.get(i);
            SpectrumEntry entry = plan.spectra().get(i);
            assertThat(plan.spectrumSignals().get(i).entry()).isSameAs(entry);
            assertThat(plan.spectrumSignals().get(i).source()).isSameAs(s);
            assertThat(entry.description()).isSameAs(s.description());
            assertThat(entry.sourceIndex()).isEqualTo(i);
            assertThat(entry.summary()).isEqualTo(SpectrumSummary.derive(s));
            if (entry.data()) {
                assertThat(entry.dataRows()).isEqualTo(s.mz().length).isPositive();
                assertThat(entry.peakRows()).isZero();
                profile++;
            } else {
                assertThat(entry.peaks()).isTrue();
                assertThat(entry.peaksFromArrays()).isTrue();
                assertThat(entry.dataRows()).isZero();
                assertThat(entry.peakRows()).isEqualTo(s.mz().length).isPositive();
                centroid++;
            }
        }
        assertThat(profile).isPositive();
        assertThat(centroid).isPositive();
    }

    /**
     * The record components reachable from a record, each followed into the components of every record type it names
     * (directly, as an array element or as a type argument), with the paths of those whose type is {@link Spectrum},
     * {@code double[]} or {@code List<CentroidPeak>}.
     */
    private static final class Walk {

        final Set<Class<?>> records = new LinkedHashSet<>();
        final List<String> signal = new ArrayList<>();

        static Walk of(Class<?> record) {
            Walk walk = new Walk();
            walk.visit(record, record.getSimpleName());
            return walk;
        }

        private void visit(Class<?> record, String path) {
            if (!records.add(record)) {
                return;
            }
            for (RecordComponent component : record.getRecordComponents()) {
                check(component.getGenericType(), path + "." + component.getName());
            }
        }

        private void check(Type type, String path) {
            if (type instanceof ParameterizedType p) {
                if (p.getRawType() == List.class && p.getActualTypeArguments()[0] == CentroidPeak.class) {
                    signal.add(path + ": List<CentroidPeak>");
                }
                for (Type argument : p.getActualTypeArguments()) {
                    check(argument, path);
                }
            } else if (type instanceof Class<?> c) {
                if (c == Spectrum.class || c == double[].class) {
                    signal.add(path + ": " + c.getSimpleName());
                } else if (c.isArray()) {
                    check(c.getComponentType(), path);
                } else if (c.isRecord()) {
                    visit(c, path);
                }
            }
        }
    }
}
