package org.mzpeak.io;

import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SpectrumDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares {@link SpectrumDescription}s field by field, for writer round trips (design §11.9, WR-5a):
 * <ul>
 *   <li>typed fields are compared exactly, doubles NaN-aware ({@link Double#compare}), nested lists (scans, scan
 *       windows, precursors, selected ions) pairwise in order;
 *   <li>Params are compared as multisets of (accession, else name; value; unit), so their order and a name next to
 *       an accession do not count;
 *   <li>a Param of the read-back side whose accession is one of {@link #WRITER_DERIVED} is ignored when the source
 *       side of that list has no Param with that accession: the writer derives these values from the payload.
 * </ul>
 * The comparison reports every difference with its path, such as {@code spectrum[3].scans[0].startTime}.
 */
final class DescriptionAssert {

    /**
     * The accessions a writer derives when the source lacks them: the five summary values (lowest and highest
     * observed m/z, base peak m/z and intensity, total ion current) and the spectrum types it derives from the MS
     * level, MS1 spectrum and MSn spectrum.
     */
    static final Set<String> WRITER_DERIVED = Set.of(
            "MS:1000528", "MS:1000527", "MS:1000504", "MS:1000505", "MS:1000285", "MS:1000579", "MS:1000580");

    /** At most this many differences are listed in a failure message. */
    private static final int MAX_LISTED = 50;

    private DescriptionAssert() {
    }

    /**
     * Asserts that {@code actual} equals {@code expected} spectrum by spectrum, in order; the precursors are compared
     * only when {@code precursors} is set.
     */
    static void assertEqual(String what, List<SpectrumDescription> expected, List<SpectrumDescription> actual,
                            boolean precursors) {
        List<String> diffs = new ArrayList<>();
        equal(diffs, "spectrum count", expected.size(), actual.size());
        for (int i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            diffs.addAll(differences("spectrum[" + i + "]", expected.get(i), actual.get(i), precursors));
        }
        assertThat(diffs).as("%s: %d differences%s", what, diffs.size(), listed(diffs)).isEmpty();
    }

    /**
     * The differences between {@code expected} and {@code actual}, each prefixed with {@code path}; the precursors
     * are compared only when {@code precursors} is set.
     */
    static List<String> differences(String path, SpectrumDescription expected, SpectrumDescription actual,
                                    boolean precursors) {
        List<String> diffs = new ArrayList<>();
        equal(diffs, path + ".index", expected.index(), actual.index());
        equal(diffs, path + ".id", expected.id(), actual.id());
        equal(diffs, path + ".msLevel", expected.msLevel(), actual.msLevel());
        equalDouble(diffs, path + ".retentionTime", expected.retentionTime(), actual.retentionTime());
        equal(diffs, path + ".polarity", expected.polarity(), actual.polarity());
        equal(diffs, path + ".signalContinuity", expected.signalContinuity(), actual.signalContinuity());
        equal(diffs, path + ".numberOfDataPoints", expected.numberOfDataPoints(), actual.numberOfDataPoints());
        equal(diffs, path + ".numberOfPeaks", expected.numberOfPeaks(), actual.numberOfPeaks());
        params(diffs, path + ".parameters", expected.parameters(), actual.parameters());
        equal(diffs, path + ".scans.size", expected.scans().size(), actual.scans().size());
        for (int i = 0; i < Math.min(expected.scans().size(), actual.scans().size()); i++) {
            scan(diffs, path + ".scans[" + i + "]", expected.scans().get(i), actual.scans().get(i));
        }
        if (precursors) {
            equal(diffs, path + ".precursors.size", expected.precursors().size(), actual.precursors().size());
            for (int i = 0; i < Math.min(expected.precursors().size(), actual.precursors().size()); i++) {
                precursor(diffs, path + ".precursors[" + i + "]", expected.precursors().get(i),
                        actual.precursors().get(i));
            }
        }
        return diffs;
    }

    private static void scan(List<String> diffs, String path, ScanEvent expected, ScanEvent actual) {
        equalDouble(diffs, path + ".startTime", expected.startTime(), actual.startTime());
        equal(diffs, path + ".injectionTime", expected.injectionTime(), actual.injectionTime());
        equal(diffs, path + ".filterString", expected.filterString(), actual.filterString());
        equal(diffs, path + ".presetScanConfiguration", expected.presetScanConfiguration(),
                actual.presetScanConfiguration());
        equal(diffs, path + ".instrumentConfigurationRef", expected.instrumentConfigurationRef(),
                actual.instrumentConfigurationRef());
        equal(diffs, path + ".spectrumReference", expected.spectrumReference(), actual.spectrumReference());
        List<ScanWindow> ew = expected.scanWindows();
        List<ScanWindow> aw = actual.scanWindows();
        equal(diffs, path + ".scanWindows.size", ew.size(), aw.size());
        for (int i = 0; i < Math.min(ew.size(), aw.size()); i++) {
            equalDouble(diffs, path + ".scanWindows[" + i + "].lowerLimit", ew.get(i).lowerLimit(),
                    aw.get(i).lowerLimit());
            equalDouble(diffs, path + ".scanWindows[" + i + "].upperLimit", ew.get(i).upperLimit(),
                    aw.get(i).upperLimit());
        }
        params(diffs, path + ".parameters", expected.parameters(), actual.parameters());
    }

    private static void precursor(List<String> diffs, String path, Precursor expected, Precursor actual) {
        equal(diffs, path + ".precursorIndex", expected.precursorIndex(), actual.precursorIndex());
        equal(diffs, path + ".precursorId", expected.precursorId(), actual.precursorId());
        IsolationWindow ew = expected.isolationWindow();
        IsolationWindow aw = actual.isolationWindow();
        if (ew == null || aw == null) {
            equal(diffs, path + ".isolationWindow", ew, aw);
        } else {
            equal(diffs, path + ".isolationWindow.targetMz", ew.targetMz(), aw.targetMz());
            equal(diffs, path + ".isolationWindow.lowerOffset", ew.lowerOffset(), aw.lowerOffset());
            equal(diffs, path + ".isolationWindow.upperOffset", ew.upperOffset(), aw.upperOffset());
        }
        params(diffs, path + ".activation", expected.activation().parameters(), actual.activation().parameters());
        List<SelectedIon> ei = expected.selectedIons();
        List<SelectedIon> ai = actual.selectedIons();
        equal(diffs, path + ".selectedIons.size", ei.size(), ai.size());
        for (int i = 0; i < Math.min(ei.size(), ai.size()); i++) {
            String ion = path + ".selectedIons[" + i + "]";
            equalDouble(diffs, ion + ".mz", ei.get(i).mz(), ai.get(i).mz());
            equal(diffs, ion + ".charge", ei.get(i).charge(), ai.get(i).charge());
            equal(diffs, ion + ".intensity", ei.get(i).intensity(), ai.get(i).intensity());
            params(diffs, ion + ".parameters", ei.get(i).parameters(), ai.get(i).parameters());
        }
    }

    /**
     * Compares two Param lists as multisets; a Param of {@code actual} with a {@link #WRITER_DERIVED} accession that
     * {@code expected} lacks is left out.
     */
    private static void params(List<String> diffs, String path, List<Param> expected, List<Param> actual) {
        Set<String> sourceAccessions = new HashSet<>();
        for (Param p : expected) {
            sourceAccessions.add(p.accession());
        }
        List<Param> kept = new ArrayList<>(actual.size());
        for (Param p : actual) {
            if (p.accession() == null || !WRITER_DERIVED.contains(p.accession())
                    || sourceAccessions.contains(p.accession())) {
                kept.add(p);
            }
        }
        Map<ParamKey, Integer> missing = counts(expected);
        Map<ParamKey, Integer> extra = counts(kept);
        for (ParamKey key : new ArrayList<>(missing.keySet())) {
            int common = Math.min(missing.get(key), extra.getOrDefault(key, 0));
            subtract(missing, key, common);
            subtract(extra, key, common);
        }
        if (!missing.isEmpty() || !extra.isEmpty()) {
            diffs.add(path + ": missing " + missing.keySet() + ", unexpected " + extra.keySet());
        }
    }

    /** How a Param is compared: its accession, else its name; its value; its unit. */
    record ParamKey(String id, Object value, String unit) {

        static ParamKey of(Param p) {
            return new ParamKey(p.accession() != null ? p.accession() : p.name(), p.value(), p.unit());
        }
    }

    private static Map<ParamKey, Integer> counts(List<Param> params) {
        Map<ParamKey, Integer> counts = new HashMap<>();
        for (Param p : params) {
            counts.merge(ParamKey.of(p), 1, Integer::sum);
        }
        return counts;
    }

    private static void subtract(Map<ParamKey, Integer> counts, ParamKey key, int n) {
        int left = counts.getOrDefault(key, 0) - n;
        if (left > 0) {
            counts.put(key, left);
        } else {
            counts.remove(key);
        }
    }

    private static void equal(List<String> diffs, String path, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            diffs.add(path + ": expected " + expected + ", got " + actual);
        }
    }

    private static void equalDouble(List<String> diffs, String path, double expected, double actual) {
        if (Double.compare(expected, actual) != 0) {
            diffs.add(path + ": expected " + expected + ", got " + actual);
        }
    }

    private static String listed(List<String> diffs) {
        if (diffs.isEmpty()) {
            return "";
        }
        List<String> shown = diffs.subList(0, Math.min(diffs.size(), MAX_LISTED));
        return ":\n" + String.join("\n", shown) + (diffs.size() > shown.size() ? "\n..." : "");
    }
}
