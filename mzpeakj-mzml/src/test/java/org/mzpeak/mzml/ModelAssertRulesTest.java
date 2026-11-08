package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest;
import org.mzpeak.model.Activation;
import org.mzpeak.model.CentroidPeak;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.IsolationWindow;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.ScanWindow;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.FileMetadata;
import org.mzpeak.model.meta.ScanSettings;
import org.mzpeak.mzml.ModelAssert.Result;
import org.mzpeak.mzml.ModelAssert.Rule;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MZ-13: every component of every record reachable from {@link Spectrum}, {@link Chromatogram},
 * {@link WavelengthSpectrum}, {@link FileMetadata}, {@link ScanSettings} and {@link MzPeakManifest.CvEntry} has a rule
 * in {@link ModelAssert#RULES} that fits its type, and the table has no other entry (mzML design 12.3). A component
 * added to the model later fails here until it is given a rule; a record that has a component without one fails the
 * comparison itself.
 */
class ModelAssertRulesTest {

    /** The fields of {@link Spectrum} that are not compared: the key of the ion mobility array, from its arrays. */
    private static final Set<String> SPECTRUM_DERIVED_FIELDS = Set.of("ionMobilityKey");

    /** Rule keys met by the walk, and the record classes reached, in walk order. */
    private final Set<String> keys = new LinkedHashSet<>();
    private final Set<Class<?>> records = new LinkedHashSet<>();
    private final Map<String, String> problems = new LinkedHashMap<>();

    @Test
    void everyComponentReachableFromTheModelHasAFittingRule() throws NoSuchFieldException {
        for (String name : ModelAssert.SPECTRUM_COMPONENTS.keySet()) {
            check("Spectrum." + name, Spectrum.class.getDeclaredField(name).getGenericType());
        }
        for (Class<?> root : List.of(Chromatogram.class, WavelengthSpectrum.class, FileMetadata.class,
                ScanSettings.class, MzPeakManifest.CvEntry.class)) {
            walk(root);
        }

        assertThat(problems).as("components without a fitting rule").isEmpty();
        assertThat(records).containsExactlyInAnyOrder(SpectrumDescription.class, ScanEvent.class, ScanWindow.class,
                Precursor.class, IsolationWindow.class, SelectedIon.class, Activation.class, CentroidPeak.class,
                Chromatogram.class, WavelengthSpectrum.class, FileMetadata.class, FileMetadata.FileDescription.class,
                FileMetadata.SourceFile.class, FileMetadata.Contact.class, FileMetadata.InstrumentConfiguration.class,
                FileMetadata.Component.class, FileMetadata.Software.class, FileMetadata.DataProcessing.class,
                FileMetadata.ProcessingMethod.class, FileMetadata.Sample.class, FileMetadata.MsRun.class,
                ScanSettings.class, MzPeakManifest.CvEntry.class);
        assertThat(ModelAssert.RULES.keySet()).as("rules for no reachable component").isEqualTo(keys);
        assertThat(keys).contains("WavelengthSpectrum.parameters", "Chromatogram.polarity",
                "Chromatogram.dataProcessingRef", "Chromatogram.parameters", "Chromatogram.precursors",
                "FileMetadata.FileDescription.contacts", "FileMetadata.Contact.name",
                "FileMetadata.Contact.affiliation", "FileMetadata.Contact.parameters", "ScanSettings.sourceFileRefs",
                "ScanSettings.targets", "MzPeakManifest.CvEntry.version");
    }

    @Test
    void spectrumHasAnExplicitEntryForEveryFieldButTheDerivedOne() {
        Set<String> fields = new LinkedHashSet<>();
        for (Field f : Spectrum.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                fields.add(f.getName());
            }
        }
        Set<String> compared = new LinkedHashSet<>(ModelAssert.SPECTRUM_COMPONENTS.keySet());
        compared.addAll(SPECTRUM_DERIVED_FIELDS);

        assertThat(ModelAssert.SPECTRUM_COMPONENTS.keySet()).containsExactly("description", "mz", "intensity",
                "peaks", "arrays", "peakArrays");
        assertThat(fields).isEqualTo(compared);
    }

    @Test
    void paramsAndTheModelHaveTheComponentsTheComparisonKnows() {
        // the PARAMS rule compares (accession, else name; value; unit): a further Param component needs it changed
        assertThat(componentNames(Param.class)).containsExactly("name", "accession", "value", "unit");
        assertThat(componentNames(ModelAssert.Model.class)).containsExactly("spectra", "chromatograms",
                "wavelengthSpectra", "fileMetadata", "scanSettings", "cvList");
    }

    @Test
    void ruleKeysNameRecordsUnderTheirEnclosingClasses() {
        assertThat(ModelAssert.key(SpectrumDescription.class)).isEqualTo("SpectrumDescription");
        assertThat(ModelAssert.key(FileMetadata.Contact.class)).isEqualTo("FileMetadata.Contact");
        assertThat(ModelAssert.key(MzPeakManifest.CvEntry.class)).isEqualTo("MzPeakManifest.CvEntry");
        assertThat(ModelAssert.key(Unruled.class)).isEqualTo("ModelAssertRulesTest.Unruled");
        assertThat(Rule.list(Rule.list(Rule.PARAMS))).hasToString("LIST(LIST(PARAMS))");
        assertThat(Rule.EXACT).hasToString("EXACT");
    }

    /** A test-local record: {@code ruled} gets a rule only where a test adds one, {@code unruled} never. */
    record Unruled(int ruled, String unruled) {
    }

    /** A record whose component holds a record without rules. */
    record Holder(String name, Unruled inner) {
    }

    @Test
    void aRecordWithAComponentWithoutARuleFailsTheComparison() {
        Unruled value = new Unruled(1, "a");

        assertThatThrownBy(() -> ModelAssert.compareValues("local", Rule.RECORD, value, value, Map.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessage("ModelAssert has no rule for ModelAssertRulesTest.Unruled.ruled");
        Map<String, Rule> oneRule = Map.of("ModelAssertRulesTest.Unruled.ruled", Rule.EXACT);
        assertThatThrownBy(() -> ModelAssert.compareValues("local", Rule.RECORD, value, value, oneRule))
                .isInstanceOf(AssertionError.class)
                .hasMessage("ModelAssert has no rule for ModelAssertRulesTest.Unruled.unruled");
        Map<String, Rule> holderRules = Map.of("ModelAssertRulesTest.Holder.name", Rule.EXACT,
                "ModelAssertRulesTest.Holder.inner", Rule.RECORD);
        assertThatThrownBy(() -> ModelAssert.compareValues("holder", Rule.RECORD, new Holder("h", value),
                new Holder("h", new Unruled(2, "a")), holderRules))
                .isInstanceOf(AssertionError.class).hasMessageContaining("no rule for ModelAssertRulesTest.Unruled");
    }

    /** A record whose component is a list of records without rules. */
    record ListHolder(List<Unruled> items) {
    }

    @Test
    void aComponentWithoutARuleFailsAlsoWhereBothSidesAreEqual() {
        Unruled value = new Unruled(1, "a");
        String noRule = "ModelAssert has no rule for ModelAssertRulesTest.Unruled.ruled";
        Map<String, Rule> holderRules = Map.of("ModelAssertRulesTest.Holder.name", Rule.EXACT,
                "ModelAssertRulesTest.Holder.inner", Rule.RECORD);
        Map<String, Rule> listHolderRules = Map.of("ModelAssertRulesTest.ListHolder.items", Rule.list(Rule.RECORD));

        // a nested record, a list element, a record in a list in a record: identical values on both sides
        assertThatThrownBy(() -> ModelAssert.compareValues("holder", Rule.RECORD, new Holder("h", value),
                new Holder("h", value), holderRules)).isInstanceOf(AssertionError.class).hasMessage(noRule);
        assertThatThrownBy(() -> ModelAssert.compareValues("list", Rule.list(Rule.RECORD), List.of(value),
                List.of(value), Map.of())).isInstanceOf(AssertionError.class).hasMessage(noRule);
        assertThatThrownBy(() -> ModelAssert.compareValues("lists", Rule.list(Rule.list(Rule.RECORD)),
                List.of(List.of(value)), List.of(List.of(value)), Map.of()))
                .isInstanceOf(AssertionError.class).hasMessage(noRule);
        assertThatThrownBy(() -> ModelAssert.compareValues("listHolder", Rule.RECORD,
                new ListHolder(List.of(value)), new ListHolder(List.of(value)), listHolderRules))
                .isInstanceOf(AssertionError.class).hasMessage(noRule);

        // with a rule for every component, the same values are equal; a null nested record and a list of
        // strings hold no record
        Map<String, Rule> all = new LinkedHashMap<>(holderRules);
        all.put("ModelAssertRulesTest.Unruled.ruled", Rule.EXACT);
        all.put("ModelAssertRulesTest.Unruled.unruled", Rule.EXACT);
        assertThat(ModelAssert.compareValues("holder", Rule.RECORD, new Holder("h", value), new Holder("h", value),
                all).equal()).isTrue();
        assertThat(ModelAssert.compareValues("holder", Rule.RECORD, new Holder("h", null), new Holder("h", null),
                holderRules).equal()).isTrue();
        assertThat(ModelAssert.compareValues("strings", Rule.list(Rule.EXACT), List.of("a"), List.of("a"), Map.of())
                .equal()).isTrue();
    }

    @Test
    void aRecordWithARuleForEveryComponentIsComparedByThem() {
        Map<String, Rule> rules = Map.of("ModelAssertRulesTest.Unruled.ruled", Rule.EXACT,
                "ModelAssertRulesTest.Unruled.unruled", Rule.EXACT);

        Result result = ModelAssert.compareValues("local", Rule.RECORD, new Unruled(1, "a"), new Unruled(1, "b"),
                rules);
        assertThat(result.differences()).containsExactly("local.unruled: expected \"a\", got \"b\"");
        assertThat(ModelAssert.compareValues("local", Rule.RECORD, new Unruled(1, "a"), new Unruled(1, "a"), rules)
                .equal()).isTrue();
        assertThat(ModelAssert.compareValues("local", Rule.RECORD, new Unruled(1, "a"), "a", rules).differences())
                .containsExactly("local: expected a ModelAssertRulesTest.Unruled, got a String");
    }

    // ---- the walk ---------------------------------------------------------------------------------------------------

    private void walk(Class<?> record) {
        if (!records.add(record)) {
            return;
        }
        for (RecordComponent c : record.getRecordComponents()) {
            check(ModelAssert.key(record) + "." + c.getName(), c.getGenericType());
        }
    }

    private void check(String key, Type type) {
        keys.add(key);
        Rule rule = ModelAssert.RULES.get(key);
        Rule fitting = fitting(type);
        if (rule == null) {
            problems.put(key, "ModelAssert has no rule for " + key + " (" + type.getTypeName() + ")");
        } else if (!rule.equals(fitting)) {
            problems.put(key, rule + " does not fit " + type.getTypeName() + ", which takes " + fitting);
        }
        reach(type);
    }

    /** The rule a value of {@code type} takes. */
    private static Rule fitting(Type type) {
        if (type == double.class || type == Double.class) {
            return Rule.DOUBLE;
        }
        if (type == double[].class) {
            return Rule.DOUBLE_ARRAY;
        }
        if (type instanceof Class<?> c) {
            if (c.isRecord()) {
                return Rule.RECORD;
            }
            assertThat(c.isArray()).as("an array other than double[]: %s", c).isFalse();
            return Rule.EXACT;
        }
        ParameterizedType p = (ParameterizedType) type;
        Type[] args = p.getActualTypeArguments();
        if (p.getRawType() == List.class) {
            return args[0] == Param.class ? Rule.PARAMS : Rule.list(fitting(args[0]));
        }
        assertThat(p.getRawType()).as("a parameterized type other than List and Map").isEqualTo(Map.class);
        assertThat(args).as("a map other than Map<String, double[]>").containsExactly(String.class, double[].class);
        return Rule.MAP_OF_ARRAYS;
    }

    /** Walks the records a value of {@code type} holds; a Param list is compared by the PARAMS rule. */
    private void reach(Type type) {
        if (type instanceof Class<?> c) {
            if (c.isRecord()) {
                walk(c);
            }
        } else if (type instanceof ParameterizedType p && p.getRawType() == List.class
                && p.getActualTypeArguments()[0] != Param.class) {
            reach(p.getActualTypeArguments()[0]);
        }
    }

    private static List<String> componentNames(Class<?> record) {
        List<String> names = new ArrayList<>();
        for (RecordComponent c : record.getRecordComponents()) {
            names.add(c.getName());
        }
        return names;
    }
}
