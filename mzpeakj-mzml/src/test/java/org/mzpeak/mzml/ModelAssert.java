package org.mzpeak.mzml;

import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Compares two whole models, {@link Model}, and reports every difference with its path, such as
 * {@code spectrum[3].scans[0].startTime} (mzML design 12.3, MZ-13). One comparator for every round trip:
 * <ul>
 *   <li><b>Reflective over record components.</b> {@link #RULES} gives every component of every record of the model
 *       one {@link Rule}, keyed {@code Record.component}, a nested record under its enclosing class
 *       ({@code FileMetadata.Contact.affiliation}, {@code MzPeakManifest.CvEntry.version}). {@link Spectrum}, a
 *       class, has explicit entries for {@link #SPECTRUM_COMPONENTS}; the components of its description appear
 *       directly under the spectrum's path. A record component without a rule fails the comparison with
 *       "ModelAssert has no rule for X.y", and {@code ModelAssertRulesTest} walks every record reachable from the
 *       model, so a component added later fails the build until it is given a rule.
 *   <li><b>No silent exclusions.</b> Nothing is left out unless the caller passes a named {@link Exclusion} (the ids
 *       of design 6, such as {@link #A_16}), each a predicate on a {@link Difference}; a passed exclusion that hides
 *       no difference fails the comparison as "stale exclusion X".
 *   <li><b>Mixed mode</b> ({@link Mode#MZML_VS_MZPEAK}, {@link Mode#MZPEAK_VS_MZML}) for one model from
 *       {@code MzMLReader} and the other from {@link MzPeakReader}; see {@link Mode}.
 * </ul>
 * Paths start at {@code spectrum[i]}, {@code chromatogram[i]}, {@code wavelengthSpectrum[i]}, {@code fileMetadata},
 * {@code scanSettings[i]} and {@code cvList[i]}; the model's lists are compared element by element up to the shorter
 * one and then by count ({@code spectrum.size}). Inside, a component is {@code .name}, a list element {@code [i]},
 * a nested list of another size is the one difference {@code .size}, a map entry {@code [key]}, and the Params of
 * one accession (else name) {@code [accession]}. At most {@value #MAX_LISTED} differences are listed in a report.
 */
public final class ModelAssert {

    /** At most this many differences are listed in a {@link Result#report()}. */
    static final int MAX_LISTED = 50;

    /** Longer renderings of a value in a difference are cut to this many characters. */
    private static final int MAX_SHOWN = 200;

    private ModelAssert() {
    }

    // ---- the model ------------------------------------------------------------------------------------------------

    /**
     * What a round trip compares: the spectra, chromatograms, wavelength spectra, file metadata, scan settings and
     * cv list of one dataset.
     *
     * @param spectra the spectra in order; iterated once per comparison, one spectrum at a time
     */
    public record Model(Iterable<Spectrum> spectra, List<Chromatogram> chromatograms,
                        List<WavelengthSpectrum> wavelengthSpectra, FileMetadata fileMetadata,
                        List<ScanSettings> scanSettings, List<MzPeakManifest.CvEntry> cvList) {

        /**
         * The model of an open reader. Its spectra are read through the reader's iterator, one at a time, each time
         * the model is compared, so the reader must stay open while it is; everything else is read now.
         */
        public static Model of(MzPeakReader reader) {
            return new Model(reader, reader.chromatograms(), reader.wavelengthSpectra(), reader.fileMetadata(),
                    reader.scanSettings(), reader.manifest().cvList());
        }

        /** The model of the dataset at {@code path}, its spectra read into a list (for fixtures). */
        public static Model load(Path path) {
            try (MzPeakReader reader = MzPeakReader.open(path)) {
                return of(reader).loaded();
            }
        }

        /** This model with its spectra read into an unmodifiable list. */
        public Model loaded() {
            return new Model(spectrumList(), chromatograms, wavelengthSpectra, fileMetadata, scanSettings, cvList);
        }

        /** The spectra as a list: {@link #spectra()} itself when it is one, else read into a new unmodifiable list. */
        public List<Spectrum> spectrumList() {
            if (spectra instanceof List<Spectrum> list) {
                return list;
            }
            List<Spectrum> list = new ArrayList<>();
            for (Spectrum s : spectra) {
                list.add(s);
            }
            return Collections.unmodifiableList(list);
        }
    }

    // ---- modes, rules, exclusions, results ------------------------------------------------------------------------

    /**
     * How the two models were read.
     *
     * <p>In the two mixed modes (one model from {@code MzMLReader}, the other from {@link MzPeakReader}), which
     * differ only in which side is the mzPeak one: {@code numberOfDataPoints} and {@code numberOfPeaks} are not
     * compared (mzML's {@code defaultArrayLength} is recomputed on write, B-12). When the mzPeak side has peaks, the
     * peak lists ({@code peaks} and {@code peakArrays}) are not compared pairwise: the mzPeak side's peaks that are a
     * row copy of its arrays ({@link ModelAssert#isRowCopy}, design 5.4, the reader invariant for CENTROID spectra)
     * are skipped with their peak arrays, peaks that are not are one difference at {@code spectrum[i].peaks} unless
     * {@link ModelAssert#A_1} is passed, and a non-empty {@code peaks()} or {@code peakArrays()} on the mzML side,
     * which {@code MzMLReader} never builds (design 5.4), is one difference at {@code spectrum[i].peaks} or
     * {@code spectrum[i].peakArrays} that {@link ModelAssert#A_1} does not hide (MJ-4). When the mzPeak side has no
     * peaks, both peak lists are compared as they are, so a non-empty one on the mzML side differs too.
     * Term-marker names (a Param with a {@code null} name on the mzPeak side, A-4) are not compared, in any mode: the
     * {@link Kind#PARAMS} rule never compares the name of a Param with an accession.
     */
    public enum Mode {
        /** Both models from the same reader. */
        STRICT,
        /** Mixed: the expected model from {@code MzMLReader}, the actual one from {@link MzPeakReader}. */
        MZML_VS_MZPEAK,
        /** Mixed: the expected model from {@link MzPeakReader}, the actual one from {@code MzMLReader}. */
        MZPEAK_VS_MZML
    }

    /** The kinds of comparison a {@link Rule} can name. */
    enum Kind {
        /** {@link Objects#equals}. */
        EXACT,
        /** {@code double} or {@code Double}: {@link Double#compare} (NaN equals NaN, 0.0 differs from -0.0). */
        DOUBLE,
        /** {@code double[]}: same length, every element bit-exact ({@link Double#compare}). */
        DOUBLE_ARRAY,
        /** {@code List<Param>}: a multiset of (accession, else name; value; unit), one difference per accession. */
        PARAMS,
        /** A list: same size (else one {@code .size} difference), then pairwise in order by the element rule. */
        LIST,
        /** A record: component by component, each by its own rule. */
        RECORD,
        /** {@code Map<String, double[]>}: the same keys in any order, each array as {@link #DOUBLE_ARRAY}. */
        MAP_OF_ARRAYS
    }

    /** A rule: a kind, and for {@link Kind#LIST} the rule of its elements. */
    record Rule(Kind kind, Rule element) {
        static final Rule EXACT = new Rule(Kind.EXACT, null);
        static final Rule DOUBLE = new Rule(Kind.DOUBLE, null);
        static final Rule DOUBLE_ARRAY = new Rule(Kind.DOUBLE_ARRAY, null);
        static final Rule PARAMS = new Rule(Kind.PARAMS, null);
        static final Rule RECORD = new Rule(Kind.RECORD, null);
        static final Rule MAP_OF_ARRAYS = new Rule(Kind.MAP_OF_ARRAYS, null);

        static Rule list(Rule element) {
            return new Rule(Kind.LIST, element);
        }

        @Override
        public String toString() {
            return element == null ? kind.name() : kind + "(" + element + ")";
        }
    }

    /** The explicit components of {@link Spectrum}, a class, in comparison order; each has a rule in {@link #RULES}. */
    static final Map<String, Function<Spectrum, Object>> SPECTRUM_COMPONENTS = spectrumComponents();

    private static Map<String, Function<Spectrum, Object>> spectrumComponents() {
        Map<String, Function<Spectrum, Object>> components = new LinkedHashMap<>();
        components.put("description", Spectrum::description);
        components.put("mz", Spectrum::mz);
        components.put("intensity", Spectrum::intensity);
        components.put("peaks", Spectrum::peaks);
        components.put("arrays", Spectrum::arrays);
        components.put("peakArrays", Spectrum::peakArrays);
        return Collections.unmodifiableMap(components);
    }

    /** One rule per component of every record of the model, and per entry of {@link #SPECTRUM_COMPONENTS}. */
    static final Map<String, Rule> RULES = Map.ofEntries(
            entry("Spectrum.description", Rule.RECORD),
            entry("Spectrum.mz", Rule.DOUBLE_ARRAY),
            entry("Spectrum.intensity", Rule.DOUBLE_ARRAY),
            entry("Spectrum.peaks", Rule.list(Rule.RECORD)),
            entry("Spectrum.arrays", Rule.MAP_OF_ARRAYS),
            entry("Spectrum.peakArrays", Rule.MAP_OF_ARRAYS),

            entry("SpectrumDescription.index", Rule.EXACT),
            entry("SpectrumDescription.id", Rule.EXACT),
            entry("SpectrumDescription.msLevel", Rule.EXACT),
            entry("SpectrumDescription.retentionTime", Rule.DOUBLE),
            entry("SpectrumDescription.polarity", Rule.EXACT),
            entry("SpectrumDescription.signalContinuity", Rule.EXACT),
            entry("SpectrumDescription.numberOfDataPoints", Rule.EXACT),
            entry("SpectrumDescription.numberOfPeaks", Rule.EXACT),
            entry("SpectrumDescription.scans", Rule.list(Rule.RECORD)),
            entry("SpectrumDescription.precursors", Rule.list(Rule.RECORD)),
            entry("SpectrumDescription.parameters", Rule.PARAMS),

            entry("ScanEvent.startTime", Rule.DOUBLE),
            entry("ScanEvent.injectionTime", Rule.DOUBLE),
            entry("ScanEvent.filterString", Rule.EXACT),
            entry("ScanEvent.presetScanConfiguration", Rule.EXACT),
            entry("ScanEvent.instrumentConfigurationRef", Rule.EXACT),
            entry("ScanEvent.spectrumReference", Rule.EXACT),
            entry("ScanEvent.scanWindows", Rule.list(Rule.RECORD)),
            entry("ScanEvent.parameters", Rule.PARAMS),

            entry("ScanWindow.lowerLimit", Rule.DOUBLE),
            entry("ScanWindow.upperLimit", Rule.DOUBLE),

            entry("Precursor.precursorIndex", Rule.EXACT),
            entry("Precursor.precursorId", Rule.EXACT),
            entry("Precursor.isolationWindow", Rule.RECORD),
            entry("Precursor.selectedIons", Rule.list(Rule.RECORD)),
            entry("Precursor.activation", Rule.RECORD),

            entry("IsolationWindow.targetMz", Rule.DOUBLE),
            entry("IsolationWindow.lowerOffset", Rule.DOUBLE),
            entry("IsolationWindow.upperOffset", Rule.DOUBLE),
            entry("IsolationWindow.parameters", Rule.PARAMS),

            entry("SelectedIon.mz", Rule.DOUBLE),
            entry("SelectedIon.charge", Rule.EXACT),
            entry("SelectedIon.intensity", Rule.DOUBLE),
            entry("SelectedIon.parameters", Rule.PARAMS),

            entry("Activation.parameters", Rule.PARAMS),

            entry("CentroidPeak.mz", Rule.DOUBLE),
            entry("CentroidPeak.intensity", Rule.DOUBLE),
            entry("CentroidPeak.index", Rule.EXACT),

            entry("Chromatogram.index", Rule.EXACT),
            entry("Chromatogram.id", Rule.EXACT),
            entry("Chromatogram.typeCurie", Rule.EXACT),
            entry("Chromatogram.time", Rule.DOUBLE_ARRAY),
            entry("Chromatogram.intensity", Rule.DOUBLE_ARRAY),
            entry("Chromatogram.intensityUnit", Rule.EXACT),
            entry("Chromatogram.arrays", Rule.MAP_OF_ARRAYS),
            entry("Chromatogram.polarity", Rule.EXACT),
            entry("Chromatogram.dataProcessingRef", Rule.EXACT),
            entry("Chromatogram.parameters", Rule.PARAMS),
            entry("Chromatogram.precursors", Rule.list(Rule.RECORD)),

            entry("WavelengthSpectrum.index", Rule.EXACT),
            entry("WavelengthSpectrum.id", Rule.EXACT),
            entry("WavelengthSpectrum.time", Rule.DOUBLE),
            entry("WavelengthSpectrum.lambdaMax", Rule.DOUBLE),
            entry("WavelengthSpectrum.wavelength", Rule.DOUBLE_ARRAY),
            entry("WavelengthSpectrum.intensity", Rule.DOUBLE_ARRAY),
            entry("WavelengthSpectrum.parameters", Rule.PARAMS),

            entry("FileMetadata.fileDescription", Rule.RECORD),
            entry("FileMetadata.instrumentConfigurations", Rule.list(Rule.RECORD)),
            entry("FileMetadata.dataProcessingMethods", Rule.list(Rule.RECORD)),
            entry("FileMetadata.samples", Rule.list(Rule.RECORD)),
            entry("FileMetadata.software", Rule.list(Rule.RECORD)),
            entry("FileMetadata.run", Rule.RECORD),
            entry("FileMetadata.FileDescription.contents", Rule.PARAMS),
            entry("FileMetadata.FileDescription.sourceFiles", Rule.list(Rule.RECORD)),
            entry("FileMetadata.FileDescription.contacts", Rule.list(Rule.RECORD)),
            entry("FileMetadata.SourceFile.id", Rule.EXACT),
            entry("FileMetadata.SourceFile.name", Rule.EXACT),
            entry("FileMetadata.SourceFile.location", Rule.EXACT),
            entry("FileMetadata.SourceFile.parameters", Rule.PARAMS),
            entry("FileMetadata.Contact.name", Rule.EXACT),
            entry("FileMetadata.Contact.affiliation", Rule.EXACT),
            entry("FileMetadata.Contact.parameters", Rule.PARAMS),
            entry("FileMetadata.InstrumentConfiguration.id", Rule.EXACT),
            entry("FileMetadata.InstrumentConfiguration.components", Rule.list(Rule.RECORD)),
            entry("FileMetadata.InstrumentConfiguration.softwareReference", Rule.EXACT),
            entry("FileMetadata.InstrumentConfiguration.parameters", Rule.PARAMS),
            entry("FileMetadata.Component.componentType", Rule.EXACT),
            entry("FileMetadata.Component.order", Rule.EXACT),
            entry("FileMetadata.Component.parameters", Rule.PARAMS),
            entry("FileMetadata.Software.id", Rule.EXACT),
            entry("FileMetadata.Software.version", Rule.EXACT),
            entry("FileMetadata.Software.parameters", Rule.PARAMS),
            entry("FileMetadata.DataProcessing.id", Rule.EXACT),
            entry("FileMetadata.DataProcessing.methods", Rule.list(Rule.RECORD)),
            entry("FileMetadata.ProcessingMethod.order", Rule.EXACT),
            entry("FileMetadata.ProcessingMethod.softwareReference", Rule.EXACT),
            entry("FileMetadata.ProcessingMethod.parameters", Rule.PARAMS),
            entry("FileMetadata.Sample.id", Rule.EXACT),
            entry("FileMetadata.Sample.name", Rule.EXACT),
            entry("FileMetadata.Sample.parameters", Rule.PARAMS),
            entry("FileMetadata.MsRun.id", Rule.EXACT),
            entry("FileMetadata.MsRun.defaultInstrumentId", Rule.EXACT),
            entry("FileMetadata.MsRun.defaultDataProcessingId", Rule.EXACT),
            entry("FileMetadata.MsRun.defaultSourceFileId", Rule.EXACT),
            entry("FileMetadata.MsRun.startTime", Rule.EXACT),
            entry("FileMetadata.MsRun.parameters", Rule.PARAMS),

            entry("ScanSettings.id", Rule.EXACT),
            entry("ScanSettings.parameters", Rule.PARAMS),
            entry("ScanSettings.sourceFileRefs", Rule.list(Rule.EXACT)),
            entry("ScanSettings.targets", Rule.list(Rule.PARAMS)),

            entry("MzPeakManifest.CvEntry.id", Rule.EXACT),
            entry("MzPeakManifest.CvEntry.fullName", Rule.EXACT),
            entry("MzPeakManifest.CvEntry.uri", Rule.EXACT),
            entry("MzPeakManifest.CvEntry.version", Rule.EXACT));

    /** The components mixed mode does not compare. */
    private static final Set<String> MIXED_UNCOMPARED =
            Set.of("SpectrumDescription.numberOfDataPoints", "SpectrumDescription.numberOfPeaks");

    /** The rule key of a record class: its simple name, under its enclosing classes ({@code FileMetadata.Contact}). */
    static String key(Class<?> type) {
        Class<?> outer = type.getEnclosingClass();
        return outer == null ? type.getSimpleName() : key(outer) + "." + type.getSimpleName();
    }

    /**
     * One difference found: its {@code path}, the two values there ({@code null} where a side has none; for the
     * Params of one accession, the two lists of them), the top-level element of each side the path lies in (a
     * {@link Spectrum}, {@link Chromatogram}, {@link WavelengthSpectrum}, {@link FileMetadata}, {@link ScanSettings}
     * or cv entry, or for a {@code .size} of the model the two lists), for exclusions that judge a value by its
     * context, and the {@code detail} a report shows after the path.
     */
    public record Difference(String path, Object expected, Object actual, Object expectedRoot, Object actualRoot,
                             String detail) {
    }

    /**
     * A named exclusion: differences it {@code hides} are not reported but counted. {@code id} is the design 6 id
     * ({@code A_7} for A-7), {@code description} says what it hides and in which fixtures, with counts.
     */
    public record Exclusion(String id, String description, Predicate<Difference> hides) {

        @Override
        public String toString() {
            return id;
        }
    }

    /**
     * The outcome of a comparison.
     *
     * @param differences every difference no exclusion hides, as {@code path: detail}, in the order found
     * @param paths       the path of each of {@code differences}
     * @param hidden      per passed exclusion id, in the order passed, how many differences it hides
     * @param stale       the ids of the passed exclusions that hide no difference
     */
    public record Result(List<String> differences, List<String> paths, Map<String, Integer> hidden,
                         List<String> stale) {

        /** Whether the models are equal: no difference is left and every passed exclusion hides one. */
        public boolean equal() {
            return differences.isEmpty() && stale.isEmpty();
        }

        /**
         * The stale exclusions, then the number of differences and at most {@value ModelAssert#MAX_LISTED} of them.
         */
        public String report() {
            StringBuilder b = new StringBuilder();
            for (String id : stale) {
                b.append("stale exclusion ").append(id).append(": it hides no difference\n");
            }
            b.append(differences.size()).append(" differences");
            List<String> shown = differences.subList(0, Math.min(differences.size(), MAX_LISTED));
            for (String d : shown) {
                b.append("\n  ").append(d);
            }
            if (differences.size() > shown.size()) {
                b.append("\n  ... and ").append(differences.size() - shown.size()).append(" more");
            }
            return b.toString();
        }
    }

    // ---- entry points ---------------------------------------------------------------------------------------------

    /**
     * Compares {@code actual} with {@code expected}.
     *
     * @throws AssertionError           when a record component has no rule ("ModelAssert has no rule for X.y")
     * @throws IllegalArgumentException when two exclusions share an id
     */
    public static Result compare(Model expected, Model actual, Mode mode, Exclusion... exclusions) {
        Comparison c = new Comparison(mode, List.of(exclusions), RULES);
        c.model(expected, actual);
        return c.result();
    }

    /**
     * Fails with an {@link AssertionError} naming {@code what} and the {@link Result#report()} unless the models are
     * {@link Result#equal() equal}; returns the result otherwise, for the hidden counts.
     */
    public static Result assertEqual(String what, Model expected, Model actual, Mode mode,
                                     Exclusion... exclusions) {
        Result result = compare(expected, actual, mode, exclusions);
        if (!result.equal()) {
            throw new AssertionError(what + ": " + result.report());
        }
        return result;
    }

    /**
     * Compares two values by {@code rule} in strict mode, below {@code path}, with {@code moreRules} added to
     * {@link #RULES}; the values are their own roots. For tests of the rule mechanism.
     */
    static Result compareValues(String path, Rule rule, Object expected, Object actual, Map<String, Rule> moreRules,
                                Exclusion... exclusions) {
        Map<String, Rule> rules = new HashMap<>(RULES);
        rules.putAll(moreRules);
        Comparison c = new Comparison(Mode.STRICT, List.of(exclusions), rules);
        c.root(expected, actual);
        c.value(path, rule, expected, actual);
        return c.result();
    }

    // ---- the comparison -------------------------------------------------------------------------------------------

    /** A record component with its rule. */
    private record Ruled(String name, String key, Rule rule, Method accessor) {

        Object get(Object record) {
            try {
                return accessor.invoke(record);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("cannot read " + key, e);
            }
        }
    }

    /** How a mixed-mode comparison treats a pair's peak lists. */
    private enum PeakLists { COMPARE, SKIP, NOT_A_COPY }

    private static final class Comparison {
        private final Mode mode;
        private final List<Exclusion> exclusions;
        private final Map<String, Rule> rules;
        private final int[] hidden;
        private final List<String> differences = new ArrayList<>();
        private final List<String> paths = new ArrayList<>();
        private final Map<Class<?>, List<Ruled>> components = new HashMap<>();
        private Object expectedRoot;
        private Object actualRoot;

        Comparison(Mode mode, List<Exclusion> exclusions, Map<String, Rule> rules) {
            Set<String> ids = new LinkedHashSet<>();
            for (Exclusion e : exclusions) {
                if (!ids.add(e.id())) {
                    throw new IllegalArgumentException("exclusion " + e.id() + " passed twice");
                }
            }
            this.mode = Objects.requireNonNull(mode, "mode");
            this.exclusions = exclusions;
            this.rules = rules;
            this.hidden = new int[exclusions.size()];
        }

        Result result() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<String> stale = new ArrayList<>();
            for (int i = 0; i < exclusions.size(); i++) {
                counts.put(exclusions.get(i).id(), hidden[i]);
                if (hidden[i] == 0) {
                    stale.add(exclusions.get(i).id());
                }
            }
            return new Result(List.copyOf(differences), List.copyOf(paths), Collections.unmodifiableMap(counts),
                    List.copyOf(stale));
        }

        void root(Object expected, Object actual) {
            expectedRoot = expected;
            actualRoot = actual;
        }

        void differ(String path, Object expected, Object actual, String detail) {
            Difference d = new Difference(path, expected, actual, expectedRoot, actualRoot, detail);
            boolean hiddenByAny = false;
            for (int i = 0; i < exclusions.size(); i++) {
                if (exclusions.get(i).hides().test(d)) {
                    hidden[i]++;
                    hiddenByAny = true;
                }
            }
            if (!hiddenByAny) {
                differences.add(path + ": " + detail);
                paths.add(path);
            }
        }

        void model(Model expected, Model actual) {
            spectra(expected.spectra(), actual.spectra());
            elements("chromatogram", expected.chromatograms(), actual.chromatograms());
            elements("wavelengthSpectrum", expected.wavelengthSpectra(), actual.wavelengthSpectra());
            root(expected.fileMetadata(), actual.fileMetadata());
            value("fileMetadata", Rule.RECORD, expected.fileMetadata(), actual.fileMetadata());
            elements("scanSettings", expected.scanSettings(), actual.scanSettings());
            elements("cvList", expected.cvList(), actual.cvList());
        }

        private void spectra(Iterable<Spectrum> expected, Iterable<Spectrum> actual) {
            Iterator<Spectrum> e = expected.iterator();
            Iterator<Spectrum> a = actual.iterator();
            int n = 0;
            while (e.hasNext() && a.hasNext()) {
                Spectrum se = e.next();
                Spectrum sa = a.next();
                root(se, sa);
                spectrum("spectrum[" + n + "]", se, sa);
                n++;
            }
            int expectedSize = n + remaining(e);
            int actualSize = n + remaining(a);
            root(expected, actual);
            size("spectrum.size", expectedSize, actualSize);
        }

        private static int remaining(Iterator<?> it) {
            int n = 0;
            while (it.hasNext()) {
                it.next();
                n++;
            }
            return n;
        }

        /** A list of the model: element by element as records up to the shorter list, then the count. */
        private void elements(String name, List<?> expected, List<?> actual) {
            root(expected, actual);
            if (expected == null || actual == null) {
                value(name, Rule.RECORD, expected, actual);
                return;
            }
            int n = Math.min(expected.size(), actual.size());
            for (int i = 0; i < n; i++) {
                root(expected.get(i), actual.get(i));
                value(name + "[" + i + "]", Rule.RECORD, expected.get(i), actual.get(i));
            }
            root(expected, actual);
            size(name + ".size", expected.size(), actual.size());
        }

        private void size(String path, int expected, int actual) {
            if (expected != actual) {
                differ(path, expected, actual, "expected " + expected + " elements, got " + actual);
            }
        }

        private Rule rule(String key) {
            Rule rule = rules.get(key);
            if (rule == null) {
                throw new AssertionError("ModelAssert has no rule for " + key);
            }
            return rule;
        }

        private void spectrum(String path, Spectrum expected, Spectrum actual) {
            if (expected == null || actual == null) {
                value(path, Rule.EXACT, expected, actual);
                return;
            }
            PeakLists peaks = peakLists(expected, actual);
            for (Map.Entry<String, Function<Spectrum, Object>> c : SPECTRUM_COMPONENTS.entrySet()) {
                String name = c.getKey();
                Rule rule = rule("Spectrum." + name);
                if (peaks != PeakLists.COMPARE && (name.equals("peaks") || name.equals("peakArrays"))) {
                    if (peaks == PeakLists.NOT_A_COPY && name.equals("peaks")) {
                        differ(path + ".peaks", expected.peaks(), actual.peaks(), "the mzPeak side's "
                                + mzPeakSide(expected, actual).peaks().size()
                                + " peaks are not a row copy of its arrays (design 5.4)");
                    }
                    mzMLSide(path, name, expected, actual);
                    continue;
                }
                value(name.equals("description") ? path : path + "." + name, rule, c.getValue().apply(expected),
                        c.getValue().apply(actual));
            }
        }

        private Spectrum mzPeakSide(Spectrum expected, Spectrum actual) {
            return mode == Mode.MZML_VS_MZPEAK ? actual : expected;
        }

        /**
         * In mixed mode, where the peak lists are not compared pairwise: the mzML side's {@code peaks} or
         * {@code peakArrays} ({@code name}), which {@code MzMLReader} never builds, is a difference when not empty.
         */
        private void mzMLSide(String path, String name, Spectrum expected, Spectrum actual) {
            Spectrum mzML = mode == Mode.MZML_VS_MZPEAK ? expected : actual;
            if (name.equals("peaks") && !mzML.peaks().isEmpty()) {
                differ(path + ".peaks", expected.peaks(), actual.peaks(), MZML_SIDE + mzML.peaks().size()
                        + " peaks: MzMLReader builds none (design 5.4)");
            } else if (name.equals("peakArrays") && !mzML.peakArrays().isEmpty()) {
                differ(path + ".peakArrays", expected.peakArrays(), actual.peakArrays(), MZML_SIDE + "peak arrays "
                        + new TreeSet<>(mzML.peakArrays().keySet()) + ": MzMLReader builds none (design 5.4)");
            }
        }

        private PeakLists peakLists(Spectrum expected, Spectrum actual) {
            if (mode == Mode.STRICT) {
                return PeakLists.COMPARE;
            }
            Spectrum mzPeak = mzPeakSide(expected, actual);
            if (mzPeak.peaks().isEmpty()) {
                return PeakLists.COMPARE;
            }
            return isRowCopy(mzPeak) ? PeakLists.SKIP : PeakLists.NOT_A_COPY;
        }

        void value(String path, Rule rule, Object expected, Object actual) {
            switch (rule.kind()) {
                case EXACT -> {
                    if (!Objects.equals(expected, actual)) {
                        differ(path, expected, actual, "expected " + show(expected) + ", got " + show(actual));
                    }
                }
                case DOUBLE -> {
                    Double e = (Double) expected;
                    Double a = (Double) actual;
                    if (e == null || a == null ? e != a : Double.compare(e, a) != 0) {
                        differ(path, expected, actual, "expected " + e + ", got " + a);
                    }
                }
                default -> {
                    if (expected == null || actual == null) {
                        if (expected != actual) {
                            differ(path, expected, actual, "expected " + show(expected) + ", got " + show(actual));
                        }
                        return;
                    }
                    switch (rule.kind()) {
                        case DOUBLE_ARRAY -> array(path, (double[]) expected, (double[]) actual);
                        case PARAMS -> params(path, (List<?>) expected, (List<?>) actual);
                        case LIST -> list(path, rule.element(), (List<?>) expected, (List<?>) actual);
                        case RECORD -> record(path, expected, actual);
                        case MAP_OF_ARRAYS -> arrays(path, (Map<?, ?>) expected, (Map<?, ?>) actual);
                        default -> throw new IllegalStateException("unknown rule " + rule);
                    }
                }
            }
        }

        private void array(String path, double[] expected, double[] actual) {
            if (Arrays.equals(expected, actual)) {
                return;
            }
            if (expected.length != actual.length) {
                differ(path, expected, actual, "expected " + expected.length + " values, got " + actual.length);
                return;
            }
            int first = -1;
            int count = 0;
            for (int i = 0; i < expected.length; i++) {
                if (Double.compare(expected[i], actual[i]) != 0) {
                    first = first < 0 ? i : first;
                    count++;
                }
            }
            differ(path, expected, actual, count + " of " + expected.length + " values differ, first [" + first
                    + "]: expected " + expected[first] + ", got " + actual[first]);
        }

        private void params(String path, List<?> expected, List<?> actual) {
            if (expected.equals(actual)) {
                return;
            }
            Map<String, List<Param>> e = byAccession(expected);
            Map<String, List<Param>> a = byAccession(actual);
            Set<String> ids = new LinkedHashSet<>(e.keySet());
            ids.addAll(a.keySet());
            for (String id : ids) {
                List<Param> ep = e.getOrDefault(id, List.of());
                List<Param> ap = a.getOrDefault(id, List.of());
                if (!counts(ep).equals(counts(ap))) {
                    differ(path + "[" + id + "]", ep, ap, "expected " + showParams(ep) + ", got " + showParams(ap));
                }
            }
        }

        private void list(String path, Rule element, List<?> expected, List<?> actual) {
            if (expected.equals(actual)) {
                resolve(Rule.list(element), expected); // equal lists are equal under every rule; check the rules
                return;
            }
            if (expected.size() != actual.size()) {
                size(path + ".size", expected.size(), actual.size());
                return;
            }
            for (int i = 0; i < expected.size(); i++) {
                value(path + "[" + i + "]", element, expected.get(i), actual.get(i));
            }
        }

        private void record(String path, Object expected, Object actual) {
            if (expected.getClass() != actual.getClass()) {
                differ(path, expected, actual, "expected a " + key(expected.getClass()) + ", got a "
                        + key(actual.getClass()));
                return;
            }
            List<Ruled> ruled = components(expected.getClass());
            if (expected.equals(actual)) {
                resolve(Rule.RECORD, expected); // record equality implies equality under every rule; check the rules
                return;
            }
            for (Ruled c : ruled) {
                if (mode != Mode.STRICT && MIXED_UNCOMPARED.contains(c.key())) {
                    continue;
                }
                value(path + "." + c.name(), c.rule(), c.get(expected), c.get(actual));
            }
        }

        /**
         * Looks up the rule of every component of every record that {@code value} holds under {@code rule}, nested
         * records and list elements included, as comparing it with a different value would; so a component without
         * a rule fails the comparison although both sides are equal (MJ-4). Components of other rules are not read.
         */
        private void resolve(Rule rule, Object value) {
            if (value == null) {
                return;
            }
            if (rule.kind() == Kind.RECORD) {
                for (Ruled c : components(value.getClass())) {
                    if (holdsRecords(c.rule())) {
                        resolve(c.rule(), c.get(value));
                    }
                }
            } else if (rule.kind() == Kind.LIST && holdsRecords(rule.element())) {
                for (Object element : (List<?>) value) {
                    resolve(rule.element(), element);
                }
            }
        }

        private static boolean holdsRecords(Rule rule) {
            return rule.kind() == Kind.RECORD || rule.kind() == Kind.LIST && holdsRecords(rule.element());
        }

        private List<Ruled> components(Class<?> type) {
            List<Ruled> ruled = components.get(type);
            if (ruled == null) {
                RecordComponent[] recordComponents = type.getRecordComponents();
                if (recordComponents == null) {
                    throw new IllegalStateException(type.getName() + " is not a record");
                }
                ruled = new ArrayList<>();
                for (RecordComponent c : recordComponents) {
                    String key = key(type) + "." + c.getName();
                    ruled.add(new Ruled(c.getName(), key, rule(key), c.getAccessor()));
                }
                components.put(type, ruled);
            }
            return ruled;
        }

        private void arrays(String path, Map<?, ?> expected, Map<?, ?> actual) {
            if (expected.equals(actual)) {
                return;
            }
            Set<Object> keys = new LinkedHashSet<>(expected.keySet());
            keys.addAll(actual.keySet());
            for (Object key : keys) {
                value(path + "[" + key + "]", Rule.DOUBLE_ARRAY, expected.get(key), actual.get(key));
            }
        }
    }

    /** The Params of {@code params} grouped by accession, else name, in first-seen order. */
    private static Map<String, List<Param>> byAccession(List<?> params) {
        Map<String, List<Param>> groups = new LinkedHashMap<>();
        for (Object o : params) {
            Param p = (Param) o;
            groups.computeIfAbsent(p.accession() != null ? p.accession() : p.name(), k -> new ArrayList<>()).add(p);
        }
        return groups;
    }

    /** How often each (value, unit) occurs among {@code params}. */
    private static Map<List<Object>, Integer> counts(List<Param> params) {
        Map<List<Object>, Integer> counts = new HashMap<>();
        for (Param p : params) {
            counts.merge(Arrays.asList(p.value(), p.unit()), 1, Integer::sum);
        }
        return counts;
    }

    private static String show(Object value) {
        String s;
        if (value instanceof String text) {
            s = '"' + text + '"';
        } else if (value instanceof double[] array) {
            s = "[" + array.length + " values]";
        } else {
            s = String.valueOf(value);
        }
        return s.length() > MAX_SHOWN ? s.substring(0, MAX_SHOWN) + "..." : s;
    }

    private static String showParams(List<Param> params) {
        List<String> shown = new ArrayList<>();
        for (Param p : params) {
            StringBuilder b = new StringBuilder(p.accession() != null ? p.accession() : show(p.name()));
            if (p.value() != null) {
                b.append('=').append(show(p.value())).append(" (").append(p.value().getClass().getSimpleName())
                        .append(')');
            }
            if (p.unit() != null) {
                b.append(" unit ").append(p.unit());
            }
            shown.add(b.toString());
        }
        return shown.toString();
    }

    // ---- row copies (design 5.4) ----------------------------------------------------------------------------------

    /** {@link #isRowCopy} per spectrum (identity), as exclusions ask it once per difference. */
    private static final Map<Spectrum, Boolean> ROW_COPY = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Whether the peaks of {@code s} are a row copy of its arrays (design 5.4, {@code WritePlanner.isPeakCopy}
     * without its continuity condition): {@code arrays()} and {@code peakArrays()} have the same keys, every peak's
     * {@link CentroidPeak#index()} names a distinct row of {@code mz()}/{@code intensity()} holding the peak's m/z and
     * intensity, every peak array holds at the peak's index the row's value of the array with its key (all by
     * {@link Double#compare}), and every row no peak names has a non-finite m/z.
     */
    static boolean isRowCopy(Spectrum s) {
        return ROW_COPY.computeIfAbsent(s, ModelAssert::rowCopy);
    }

    private static boolean rowCopy(Spectrum s) {
        if (!s.arrays().keySet().equals(s.peakArrays().keySet())) {
            return false;
        }
        double[] mz = s.mz();
        double[] intensity = s.intensity();
        boolean[] named = new boolean[mz.length];
        for (CentroidPeak p : s.peaks()) {
            int row = p.index();
            if (row < 0 || row >= mz.length || named[row] || Double.compare(mz[row], p.mz()) != 0
                    || Double.compare(intensity[row], p.intensity()) != 0) {
                return false;
            }
            for (Map.Entry<String, double[]> peakArray : s.peakArrays().entrySet()) {
                double[] values = peakArray.getValue();
                if (row >= values.length
                        || Double.compare(values[row], s.arrays().get(peakArray.getKey())[row]) != 0) {
                    return false;
                }
            }
            named[row] = true;
        }
        for (int row = 0; row < mz.length; row++) {
            if (!named[row] && Double.isFinite(mz[row])) {
                return false;
            }
        }
        return true;
    }

    // ---- exclusions (design 6) ------------------------------------------------------------------------------------

    private static final Pattern PEAK_LIST = Pattern.compile("spectrum\\[\\d+]\\.(peaks|peakArrays|numberOfPeaks)"
            + "([.\\[].*)?");
    private static final Pattern RUN_ID = Pattern.compile("fileMetadata\\.run\\.id");
    private static final Pattern NO_COMBINATION =
            Pattern.compile("(spectrum|wavelengthSpectrum)\\[\\d+]\\.parameters\\[MS:1000795]");
    private static final Pattern RUN_DEFAULT_DP = Pattern.compile("fileMetadata\\.run\\.defaultDataProcessingId");
    private static final Pattern RETENTION_TIME = Pattern.compile("spectrum\\[\\d+]\\.retentionTime");
    private static final Pattern SPECTRUM_PARAM = Pattern.compile("spectrum\\[\\d+]\\.parameters\\[(.*)]");
    private static final Pattern RUN_DEFAULT_IC = Pattern.compile("fileMetadata\\.run\\.defaultInstrumentId");
    private static final Pattern SIGNAL = Pattern.compile("spectrum\\[\\d+]\\.(mz|intensity|arrays(\\[.*)?)");
    private static final Pattern MZ = Pattern.compile("spectrum\\[\\d+]\\.mz");

    /** The start of the detail of a mixed-mode difference in the mzML side's own peak list, which A_1 never hides. */
    static final String MZML_SIDE = "the mzML side has ";
    /** The spectra combinations other than MS:1000795 'no combination': sum, median and mean of spectra. */
    private static final Set<String> OTHER_COMBINATIONS = Set.of("MS:1000571", "MS:1000573", "MS:1000575");
    /** The spectrum types the mzML writer derives from the MS level: MS1 spectrum, MSn spectrum. */
    private static final Set<String> DERIVED_TYPES = Set.of("MS:1000579", "MS:1000580");
    /** The five summary params the mzPeak writer derives when a spectrum has none of them. */
    private static final Set<String> SUMMARIES =
            Set.of("MS:1000528", "MS:1000527", "MS:1000504", "MS:1000505", "MS:1000285");

    /** A-1: peak lists that are not a row copy of the arrays (design 5.4). */
    public static final Exclusion A_1 = new Exclusion("A_1",
            "A-1: the peak list (peaks, peakArrays and numberOfPeaks) of a spectrum whose peaks on one side are not a"
                    + " row copy of its arrays (design 5.4) and on the other side are none or a row copy; in mixed"
                    + " mode the one difference 'not a row copy', never one in the mzML side's own peak list: current"
                    + " and legacy small.chunked, 14 spectra each",
            d -> PEAK_LIST.matcher(d.path()).matches() && !d.detail().startsWith(MZML_SIDE)
                    && d.expectedRoot() instanceof Spectrum e && d.actualRoot() instanceof Spectrum a
                    && (notACopy(e, a) || notACopy(a, e)));

    /** A-2: the run id encoded as an NCName. */
    public static final Exclusion A_2 = new Exclusion("A_2",
            "A-2: a run id that is not an NCName in expected and encoded by rule NC (MzMLTerms.ncName) in actual:"
                    + " diaPASEF.ref and .grid, test/data/diaPASEF.d -> test_x002f_data_x002f_diaPASEF.d",
            d -> RUN_ID.matcher(d.path()).matches() && d.expected() instanceof String e
                    && !MzMLTerms.ncName(e).equals(e) && MzMLTerms.ncName(e).equals(d.actual()));

    /** A-7: MS:1000795 'no combination' added. */
    public static final Exclusion A_7 = new Exclusion("A_7",
            "A-7: MS:1000795 'no combination' in the params of a spectrum or wavelength spectrum of actual and"
                    + " absent from expected, whose params carry no other combination term (MS:1000571, MS:1000573,"
                    + " MS:1000575): every spectrum of every mzPeak fixture (small* 48, has_uv 212 + 520, diaPASEF.ref"
                    + " and .grid 9, Example_Processed 9)",
            d -> NO_COMBINATION.matcher(d.path()).matches() && onlyActual(d)
                    && noOtherCombination(d.expectedRoot()));

    /** A-8: the run's default data processing taken from the first data processing. */
    public static final Exclusion A_8 = new Exclusion("A_8",
            "A-8: a run default data processing id that is null in expected and the id of the first data processing"
                    + " in actual: diaPASEF.ref and .grid, null -> mzpeak_conversion1",
            d -> RUN_DEFAULT_DP.matcher(d.path()).matches() && d.expected() == null
                    && d.actualRoot() instanceof FileMetadata a && !a.dataProcessingMethods().isEmpty()
                    && a.dataProcessingMethods().get(0).id().equals(d.actual()));

    /** A-9: the retention time replaced by the first scan's start time. */
    public static final Exclusion A_9 = new Exclusion("A_9",
            "A-9: a retentionTime that differs from the expected spectrum's first scan start time in expected and is"
                    + " that start time in actual: legacy small* 48, legacy has_uv 211",
            d -> RETENTION_TIME.matcher(d.path()).matches() && d.expectedRoot() instanceof Spectrum e
                    && !e.description().scans().isEmpty() && d.actual() instanceof Double a
                    && Double.compare(a, e.description().scans().get(0).startTime()) == 0);

    /** A-16: the spectrum type and the five summary params gained. */
    public static final Exclusion A_16 = new Exclusion("A_16",
            "A-16: the spectrum types MS:1000579, MS:1000580 and the summary params MS:1000528, MS:1000527,"
                    + " MS:1000504, MS:1000505, MS:1000285 present only in actual, in a spectrum whose expected params"
                    + " carry no spectrum type (for a type) or none of the five (for a summary param): legacy small*"
                    + " 48, legacy has_uv 212",
            ModelAssert::gainedTypeOrSummary);

    /** A-17: an empty cv list filled. */
    public static final Exclusion A_17 = new Exclusion("A_17",
            "A-17: the cv list, empty in expected and in actual filled with entries of CvEntry.known(): legacy"
                    + " fixtures [] -> [MS, UO], Example_Processed [] -> [MS, UO, IMS]",
            d -> d.path().equals("cvList.size") && Integer.valueOf(0).equals(d.expected())
                    && d.actualRoot() instanceof List<?> a && MzPeakManifest.CvEntry.known().containsAll(a));

    /** A-18: a dangling run default instrument configuration redirected to the first. */
    public static final Exclusion A_18 = new Exclusion("A_18",
            "A-18: a run default instrument configuration that names no instrument configuration in expected and is"
                    + " the first one's id in actual: diaPASEF.ref and .grid, 1 -> 0",
            d -> RUN_DEFAULT_IC.matcher(d.path()).matches() && d.expected() instanceof Integer e
                    && d.expectedRoot() instanceof FileMetadata em
                    && em.instrumentConfigurations().stream().noneMatch(ic -> ic.id() == e)
                    && d.actualRoot() instanceof FileMetadata am && !am.instrumentConfigurations().isEmpty()
                    && Integer.valueOf(am.instrumentConfigurations().get(0).id()).equals(d.actual()));

    /** B-22: arrays written sorted by m/z. */
    public static final Exclusion B_22 = new Exclusion("B_22",
            "B-22: the m/z, intensity and extra arrays of a spectrum that are equal as m/z-sorted parallel tuples"
                    + " (ties ordered by the other values): diaPASEF.mzML, 9 spectra (pwiz writes scan order)",
            d -> SIGNAL.matcher(d.path()).matches() && d.expectedRoot() instanceof Spectrum e
                    && d.actualRoot() instanceof Spectrum a && sortedTuplesEqual(e, a));

    /** B-34: NaN m/z values interpolated. */
    public static final Exclusion B_34 = new Exclusion("B_34",
            "B-34: an m/z array that differs only where expected has NaN (interpolated by the mzPeak read): the NaN"
                    + " m/z branch document of MZ-20a",
            d -> MZ.matcher(d.path()).matches() && d.expected() instanceof double[] e
                    && d.actual() instanceof double[] a && differsOnlyAtNaN(e, a));

    /** Every exclusion above. */
    public static final List<Exclusion> EXCLUSIONS = List.of(A_1, A_2, A_7, A_8, A_9, A_16, A_17, A_18, B_22, B_34);

    /** {@code s} has peaks that are not a row copy of its arrays, and {@code other} none or a row copy. */
    private static boolean notACopy(Spectrum s, Spectrum other) {
        return !s.peaks().isEmpty() && !isRowCopy(s) && (other.peaks().isEmpty() || isRowCopy(other));
    }

    /** A difference of the Params of one accession: none in expected, some in actual. */
    private static boolean onlyActual(Difference d) {
        return d.expected() instanceof List<?> e && e.isEmpty() && d.actual() instanceof List<?> a && !a.isEmpty();
    }

    /** {@code root} is a spectrum or wavelength spectrum whose params carry none of {@link #OTHER_COMBINATIONS}. */
    private static boolean noOtherCombination(Object root) {
        List<Param> params = root instanceof Spectrum s ? s.description().parameters()
                : root instanceof WavelengthSpectrum w ? w.parameters() : null;
        return params != null && params.stream().noneMatch(p -> OTHER_COMBINATIONS.contains(p.accession()));
    }

    private static boolean gainedTypeOrSummary(Difference d) {
        Matcher m = SPECTRUM_PARAM.matcher(d.path());
        if (!m.matches() || !onlyActual(d) || !(d.expectedRoot() instanceof Spectrum e)) {
            return false;
        }
        String accession = m.group(1);
        Set<String> blocking = DERIVED_TYPES.contains(accession) ? MzMLTerms.SPECTRUM_TYPES.keySet()
                : SUMMARIES.contains(accession) ? SUMMARIES : Set.of();
        return !blocking.isEmpty()
                && e.description().parameters().stream().noneMatch(p -> blocking.contains(p.accession()));
    }

    private static boolean differsOnlyAtNaN(double[] expected, double[] actual) {
        if (expected.length != actual.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (Double.compare(expected[i], actual[i]) != 0 && !Double.isNaN(expected[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean sortedTuplesEqual(Spectrum expected, Spectrum actual) {
        if (!expected.arrays().keySet().equals(actual.arrays().keySet())) {
            return false;
        }
        List<String> keys = new ArrayList<>(expected.arrays().keySet());
        double[][] e = sortedTuples(expected, keys);
        double[][] a = sortedTuples(actual, keys);
        return Arrays.deepEquals(e, a);
    }

    /** The rows of {@code s} as tuples (m/z, intensity, the arrays of {@code keys}), sorted lexicographically. */
    private static double[][] sortedTuples(Spectrum s, List<String> keys) {
        double[][] rows = new double[s.mz().length][];
        for (int i = 0; i < rows.length; i++) {
            double[] row = new double[2 + keys.size()];
            row[0] = s.mz()[i];
            row[1] = s.intensity()[i];
            for (int k = 0; k < keys.size(); k++) {
                row[2 + k] = s.arrays().get(keys.get(k))[i];
            }
            rows[i] = row;
        }
        Arrays.sort(rows, (x, y) -> {
            for (int k = 0; k < x.length; k++) {
                int c = Double.compare(x[k], y[k]);
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        });
        return rows;
    }
}
