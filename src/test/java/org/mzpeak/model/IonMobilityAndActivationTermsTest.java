package org.mzpeak.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mzpeak.io.Fixtures;
import org.mzpeak.io.MzPeakReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RD-11: the ion-mobility and dissociation-method CV tables behind {@link ScanEvent#ionMobilityValue()},
 * {@link ScanEvent#ionMobilityType()}, {@link SelectedIon#ionMobilityValue()}, {@link Activation#methods()} and
 * {@link Activation#summary()}.
 *
 * <p>The characterization cases pinned the hand-written lists one accession at a time. Documented changes since:
 * <ul>
 *   <li>the former array terms, the charge array MS:1002478 and the unit MS:1002814 are no longer ion mobility
 *       types, and the 7 children of MS:1002892 (among them MS:1002815) now are;
 *   <li>MS:1000044, MS:1002631 (EThcD) and MS:1003181 are no longer in {@code methods()}, and the other
 *       descendants of MS:1000044 (among them MS:1002679, MS:1003246 and MS:1003294) now are;
 *   <li>the dissociation_method term-marker Param of the split layout is named from
 *       {@link CvTerms#DISSOCIATION_METHODS}, and a scan's typed ion mobility is no longer appended a second time
 *       when its parameters list already holds the same type;
 *   <li>FN-7: a scan takes the ion mobility types in mzdata's priority order instead of accession order, and its
 *       {@code ionMobilityType()} is the type of the value {@code ionMobilityValue()} returns.
 * </ul>
 *
 * Oracles: psi-ms 4.2.11 (mzdata v0.68.0 cv/psi-ms.obo.gz, regenerated when {@code -Dmzpeak.psims.obo} names it),
 * the FX-1b golden meta lines of diaPASEF.ref, and legacy small (HUPO-PSI/mzPeak 29e59b2).
 */
class IonMobilityAndActivationTermsTest {

    private static final String COLLISION_ENERGY = "MS:1000045";
    private static final String ELECTRONVOLT = "UO:0000266";
    private static final Param ENERGY = new Param("collision energy", COLLISION_ENERGY, 35.0, ELECTRONVOLT);

    private static final String DIA_REF = "diaPASEF.ref.mzpeak";
    private static final String INVERSE_REDUCED_ION_MOBILITY = "MS:1002815";
    /**
     * The ion_mobility_value cells of diaPASEF.ref spectra 1..8, from the FX-1b golden meta lines (pyarrow): the scans
     * facet, then the selected-ions facet, which differs in the last digit of spectrum 4.
     */
    private static final double[] DIA_REF_SCAN_MOBILITY = {1.2517460582524271, 0.7514424379719524,
        1.2592937421790724, 0.7589901218985975, 1.267380546386192, 0.7670769261057173, 1.274928230312837,
        0.7746246100323624};
    private static final double[] DIA_REF_ION_MOBILITY = {1.2517460582524271, 0.7514424379719524,
        1.2592937421790724, 0.7589901218985976, 1.267380546386192, 0.7670769261057173, 1.274928230312837,
        0.7746246100323624};

    // ---- (a) diaPASEF.ref ----------------------------------------------------------------------------------------

    /**
     * Every diaPASEF.ref MS2 scan and selected ion stores its 1/K0 as MS:1002815 'inverse reduced ion mobility', a
     * child of MS:1002892; spectrum 0 (MS1) has none.
     */
    @Test
    void diaPasefRefScansAndSelectedIonsReportTheirInverseReducedIonMobility() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            ScanEvent ms1 = reader.getMetadata(0).orElseThrow().scans().get(0);
            assertThat(ms1.ionMobilityValue()).isEmpty();
            assertThat(ms1.ionMobilityType()).isNull();
            for (int index = 1; index <= 8; index++) {
                SpectrumDescription d = reader.getMetadata(index).orElseThrow();
                ScanEvent scan = d.scans().get(0);
                assertThat(scan.ionMobilityValue()).as("spectrum %d scan", index)
                        .hasValue(DIA_REF_SCAN_MOBILITY[index - 1]);
                assertThat(scan.ionMobilityType()).as("spectrum %d scan type", index)
                        .isEqualTo(INVERSE_REDUCED_ION_MOBILITY);
                SelectedIon ion = d.primaryPrecursor().primaryIon();
                assertThat(ion.ionMobilityValue()).as("spectrum %d selected ion", index)
                        .hasValue(DIA_REF_ION_MOBILITY[index - 1]);
            }
            assertThat(reader.getMetadata(1).orElseThrow().scans().get(0).ionMobilityValue())
                    .hasValue(1.2517460582524271);
        }
    }

    /**
     * diaPASEF.ref spectrum 1: the scan's parameters list carries MS:1002815, so the typed
     * ion_mobility_value/ion_mobility_type cells are not appended a second time (documented change: before RD-11 the
     * list's term was not recognised and a second MS:1002815 Param 'ion mobility value' followed).
     */
    @Test
    void diaPasefRefScanParamsOfSpectrum1() {
        try (MzPeakReader reader = MzPeakReader.open(Fixtures.current(DIA_REF))) {
            ScanEvent scan = reader.getMetadata(1).orElseThrow().scans().get(0);
            assertThat(scan.parameters()).containsExactly(
                    new Param("window group", null, 1L, null),
                    new Param("inverse reduced ion mobility", "MS:1002815", 1.2517460582524271, "MS:1002814"));
        }
    }

    // ---- characterization: ion mobility ----------------------------------------------------------------------

    /**
     * A scan or selected ion whose only CV param is {@code accession}, valued 1.5 and preceded by a user param: the
     * value and type are reported exactly when the accession is an ion mobility type.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            // the former hand-written list: only MS:1002476 is an ion mobility type (documented change)
            "MS:1002476, ion mobility drift time,                          true",
            "MS:1002477, mean ion mobility drift time array,               false",
            "MS:1002478, mean charge array,                                false",
            "MS:1002814, volt-second per square centimeter,                false",
            "MS:1002816, mean ion mobility array,                          false",
            "MS:1003006, mean inverse reduced ion mobility array,          false",
            "MS:1003007, raw ion mobility array,                           false",
            "MS:1003153, raw ion mobility drift time array,                false",
            "MS:1003155, deconvoluted inverse reduced ion mobility array,  false",
            "MS:1003156, deconvoluted ion mobility drift time array,       false",
            // the children of MS:1002892 the list missed (documented change)
            "MS:1001581, FAIMS compensation voltage,                       true",
            "MS:1002815, inverse reduced ion mobility,                     true",
            "MS:1003371, SelexION compensation voltage,                    true",
            "MS:1003394, SelexION separation voltage,                      true",
            "MS:1003450, FAIMS compensation voltage ramp start,            true",
            "MS:1003451, FAIMS compensation voltage ramp end,              true",
            // ion mobility arrays the list missed
            "MS:1002893, ion mobility array,                               false",
            "MS:1003008, raw inverse reduced ion mobility array,           false",
            "MS:1003154, deconvoluted ion mobility array,                  false"
    })
    void ionMobilityIsReportedForTheRecognisedAccessions(String accession, String name, boolean recognised) {
        List<Param> params = List.of(new Param("window group", null, 1L, null), new Param(name, accession, 1.5, null));
        ScanEvent scan = scan(params);
        SelectedIon ion = new SelectedIon(500.0, 2, null, params);
        assertThat(CvTerms.ION_MOBILITY_TYPES.contains(accession)).isEqualTo(recognised);
        if (recognised) {
            assertThat(scan.ionMobilityValue()).hasValue(1.5);
            assertThat(scan.ionMobilityType()).isEqualTo(accession);
            assertThat(ion.ionMobilityValue()).hasValue(1.5);
        } else {
            assertThat(scan.ionMobilityValue()).isEmpty();
            assertThat(scan.ionMobilityType()).isNull();
            assertThat(ion.ionMobilityValue()).isEmpty();
        }
    }

    /** A user param (null accession) never carries ion mobility, whatever its name or value. */
    @Test
    void aUserParamWithANullAccessionIsNoIonMobility() {
        List<Param> params = List.of(new Param("ion mobility drift time", null, 1.5, null));
        assertThat(scan(params).ionMobilityValue()).isEmpty();
        assertThat(scan(params).ionMobilityType()).isNull();
        assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).isEmpty();
        assertThat(scan(List.of()).ionMobilityValue()).isEmpty();
        assertThat(scan(List.of()).ionMobilityType()).isNull();
    }

    /** A value that is not a finite number gives no value, but the scan still names the type. */
    @Test
    void aNonFiniteOrNonNumericValueGivesNoValueButTheScanKeepsTheType() {
        for (Object value : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, "1.5", null}) {
            List<Param> params = List.of(new Param("ion mobility drift time", "MS:1002476", value, null));
            assertThat(scan(params).ionMobilityValue()).as("%s", value).isEmpty();
            assertThat(scan(params).ionMobilityType()).as("%s", value).isEqualTo("MS:1002476");
            assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).as("%s", value).isEmpty();
        }
        // a later recognised param with a finite value still gives the value
        List<Param> params = List.of(new Param("ion mobility drift time", "MS:1002476", Double.NaN, null),
                new Param("ion mobility drift time", "MS:1002476", 2.0, null));
        assertThat(scan(params).ionMobilityValue()).hasValue(2.0);
        assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).hasValue(2.0);
    }

    /**
     * With two recognised params a scan reports the one whose type comes first in mzdata's priority order (FN-7:
     * MS:1002476, MS:1002815, MS:1001581, MS:1003371, then the table order), a selected ion the first in list order.
     * MS:1003006 is an array term (documented change: a selected ion used to report it).
     */
    @Test
    void twoRecognisedParamsAScanPrefersMzdatasPriorityASelectedIonTheListOrder() {
        List<Param> params = List.of(new Param("mean inverse reduced ion mobility array", "MS:1003006", 2.0, null),
                new Param("ion mobility drift time", "MS:1002476", 1.0, null));
        assertThat(scan(params).ionMobilityValue()).hasValue(1.0);
        assertThat(scan(params).ionMobilityType()).isEqualTo("MS:1002476");
        assertThat(new SelectedIon(500.0, null, null, params).ionMobilityValue()).hasValue(1.0);

        // inverse reduced ion mobility before the FAIMS compensation voltage, whatever the list order (the table's
        // accession order gave -45 / MS:1001581 before FN-7)
        List<Param> types = List.of(new Param("FAIMS compensation voltage", "MS:1001581", -45.0, null),
                new Param("inverse reduced ion mobility", "MS:1002815", 0.95, null));
        assertThat(scan(types).ionMobilityValue()).hasValue(0.95);
        assertThat(scan(types).ionMobilityType()).isEqualTo("MS:1002815");
        assertThat(new SelectedIon(500.0, null, null, types).ionMobilityValue()).hasValue(-45.0);

        // SelexION separation voltage comes after the four prioritised types
        List<Param> selexion = List.of(new Param("SelexION separation voltage", "MS:1003394", 3.0, null),
                new Param("SELEXION compensation voltage", "MS:1003371", 4.0, null));
        assertThat(scan(selexion).ionMobilityValue()).hasValue(4.0);
        assertThat(scan(selexion).ionMobilityType()).isEqualTo("MS:1003371");
    }

    /** The type is that of the param whose value is returned: a NaN drift time does not name the type (FN-7). */
    @Test
    void theTypeIsThatOfTheReturnedValue() {
        List<Param> params = List.of(new Param("ion mobility drift time", "MS:1002476", Double.NaN, null),
                new Param("inverse reduced ion mobility", "MS:1002815", 1.0, null));
        assertThat(scan(params).ionMobilityValue()).hasValue(1.0);
        assertThat(scan(params).ionMobilityType()).isEqualTo("MS:1002815");

        // without a finite value, the first type present in priority order
        List<Param> noValue = List.of(new Param("FAIMS compensation voltage", "MS:1001581", Double.NaN, null),
                new Param("inverse reduced ion mobility", "MS:1002815", "n/a", null));
        assertThat(scan(noValue).ionMobilityValue()).isEmpty();
        assertThat(scan(noValue).ionMobilityType()).isEqualTo("MS:1002815");
    }

    // ---- characterization: dissociation methods --------------------------------------------------------------

    /**
     * An activation holding one method Param and the collision energy: the method is listed by {@code methods()}
     * and named by {@code summary()} exactly when its accession is a descendant of MS:1000044.
     */
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "MS:1000133, collision-induced dissociation,                          true",
            "MS:1000422, beam-type collision-induced dissociation,                true",
            "MS:1000598, electron transfer dissociation,                          true",
            "MS:1000250, electron capture dissociation,                           true",
            "MS:1000262, infrared multiphoton dissociation,                       true",
            "MS:1000435, photodissociation,                                       true",
            // the root and the combined dissociation methods are no methods (documented change)
            "MS:1000044, dissociation method,                                     false",
            "MS:1002631, electron-transfer/higher-energy collision dissociation,  false",
            "MS:1002678, supplemental beam-type collision-induced dissociation,   true",
            "MS:1003181, combined dissociation method,                            false",
            // descendants of MS:1000044 the list missed (documented change)
            "MS:1002679, supplemental collision-induced dissociation,             true",
            "MS:1003246, ultraviolet photodissociation,                           true",
            "MS:1003294, electron activated dissociation,                         true",
            // not a dissociation method
            "MS:1000045, collision energy,                                        false"
    })
    void methodsAndSummaryForEachAccession(String accession, String name, boolean recognised) {
        Param method = new Param(name, accession, null, null);
        Activation activation = new Activation(List.of(method, ENERGY));
        assertThat(CvTerms.DISSOCIATION_METHODS.containsKey(accession)).isEqualTo(recognised);
        if (recognised) {
            assertThat(CvTerms.DISSOCIATION_METHODS).containsEntry(accession, name);
            assertThat(activation.methods()).containsExactly(method);
            assertThat(activation.summary()).isEqualTo(name + " @ 35.0 eV");
        } else {
            assertThat(activation.methods()).isEmpty();
            assertThat(activation.summary()).isEqualTo("custom");
        }
        assertThat(activation.parameters()).containsExactly(method, ENERGY);
    }

    @Test
    void summaryFormsAndMethodOrder() {
        Param etd = new Param("electron transfer dissociation", "MS:1000598", null, null);
        Param sbcid = new Param("supplemental beam-type collision-induced dissociation", "MS:1002678", null, null);
        Param user = new Param("free text", null, "pulsed", null);
        assertThat(Activation.EMPTY.methods()).isEmpty();
        assertThat(Activation.EMPTY.summary()).isEqualTo("(none)");
        assertThat(new Activation(List.of(ENERGY)).summary()).isEqualTo("custom");
        assertThat(new Activation(List.of(user)).methods()).isEmpty();
        assertThat(new Activation(List.of(user)).summary()).isEqualTo("custom");
        assertThat(new Activation(List.of(etd)).summary()).isEqualTo("electron transfer dissociation");
        // a method without a name is shown by its accession
        assertThat(new Activation(List.of(new Param(null, "MS:1000133", null, null), ENERGY)).summary())
                .isEqualTo("MS:1000133 @ 35.0 eV");
        // several methods keep their list order and are joined with ' + '
        Activation ethcd = new Activation(List.of(sbcid, user, ENERGY, etd));
        assertThat(ethcd.methods()).containsExactly(sbcid, etd);
        assertThat(ethcd.summary()).isEqualTo("supplemental beam-type collision-induced dissociation"
                + " + electron transfer dissociation @ 35.0 eV");
    }

    /**
     * Current small names the method by the dissociation_method CURIE, now named from the table (documented change:
     * its summary showed the CURIE), so its activation equals legacy small's, which names it in its parameters list.
     */
    @Test
    void smallSpectrum2ActivationSummaryCurrentAndLegacy() {
        try (MzPeakReader current = MzPeakReader.open(Fixtures.current("small.mzpeak"));
             MzPeakReader legacy = MzPeakReader.open(Fixtures.legacy("small.mzpeak"))) {
            Activation c = current.getMetadata(2).orElseThrow().primaryPrecursor().activation();
            Activation l = legacy.getMetadata(2).orElseThrow().primaryPrecursor().activation();
            assertThat(c.parameters())
                    .containsExactly(new Param("collision-induced dissociation", "MS:1000133", null, null), ENERGY)
                    .isEqualTo(l.parameters());
            assertThat(c.summary()).isEqualTo("collision-induced dissociation @ 35.0 eV");
            assertThat(l.summary()).isEqualTo("collision-induced dissociation @ 35.0 eV");
        }
    }

    // ---- (c) methods(): descendants of MS:1000044 only ---------------------------------------------------------

    @Test
    void methodsRecognizeTheDescendantsOfDissociationMethodOnly() {
        for (String accession : List.of(CvTerms.UVPD, CvTerms.SUPPLEMENTAL_CID, CvTerms.SUPPLEMENTAL_BEAM_CID)) {
            Param p = new Param(CvTerms.DISSOCIATION_METHODS.get(accession), accession, null, null);
            assertThat(new Activation(List.of(p, ENERGY)).methods()).as(accession).containsExactly(p);
        }

        // EThcD as mzdata describes it: ETD plus supplemental beam-type CID
        Param etd = new Param("electron transfer dissociation", CvTerms.ETD, null, null);
        Param sbcid = new Param("supplemental beam-type collision-induced dissociation", CvTerms.SUPPLEMENTAL_BEAM_CID,
                null, null);
        Activation mzdataEthcd = new Activation(List.of(etd, sbcid, ENERGY));
        assertThat(mzdataEthcd.methods()).containsExactly(etd, sbcid);
        assertThat(mzdataEthcd.summary()).isEqualTo("electron transfer dissociation"
                + " + supplemental beam-type collision-induced dissociation @ 35.0 eV");

        // EThcD as MS:1002631, a combined dissociation method attribute: no method, still a parameter
        Param ethcd = new Param("electron-transfer/higher-energy collision dissociation", CvTerms.ETHCD, null, null);
        Activation combined = new Activation(List.of(ethcd, ENERGY));
        assertThat(combined.methods()).isEmpty();
        assertThat(combined.parameters()).containsExactly(ethcd, ENERGY);
        assertThat(combined.summary()).isEqualTo("custom");
    }

    // ---- the tables ------------------------------------------------------------------------------------------

    @Test
    void theTablesHaveTheirSizesAndAccessionOrderAndRejectChanges() {
        assertThat(CvTerms.ION_MOBILITY_TYPES).hasSize(7)
                .contains("MS:1001581", "MS:1002476", INVERSE_REDUCED_ION_MOBILITY)
                .doesNotContain("MS:1002478", "MS:1002814", "MS:1002892")
                .doesNotContainAnyElementsOf(CvTerms.ION_MOBILITY_ARRAYS);
        assertThat(CvTerms.ION_MOBILITY_ARRAYS).hasSize(10)
                .contains("MS:1002893", "MS:1003006", "MS:1003008", "MS:1003154");
        assertThat(CvTerms.DISSOCIATION_METHODS).hasSize(22)
                .containsEntry(CvTerms.CID, "collision-induced dissociation")
                .containsEntry(CvTerms.IRMPD, "infrared multiphoton dissociation")
                .doesNotContainKeys(CvTerms.DISSOCIATION_METHOD, CvTerms.ETHCD, "MS:1003181", COLLISION_ENERGY);

        assertThat(List.copyOf(CvTerms.ION_MOBILITY_TYPES)).isSorted();
        assertThat(List.copyOf(CvTerms.ION_MOBILITY_ARRAYS)).isSorted();
        assertThat(List.copyOf(CvTerms.DISSOCIATION_METHODS.keySet())).isSorted();

        assertThat(CvTerms.ION_MOBILITY_TYPES.contains(null)).isFalse();
        assertThat(CvTerms.ION_MOBILITY_ARRAYS.contains(null)).isFalse();
        assertThat(CvTerms.DISSOCIATION_METHODS.containsKey(null)).isFalse();
        assertThat(CvTerms.DISSOCIATION_METHODS.get(null)).isNull();

        assertThatThrownBy(() -> CvTerms.ION_MOBILITY_TYPES.add("MS:1002893"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CvTerms.ION_MOBILITY_ARRAYS.remove("MS:1002893"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> CvTerms.DISSOCIATION_METHODS.put(CvTerms.ETHCD, "EThcD"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---- (e) constants -----------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("deprecation")
    void supplementalActivationIsADeprecatedAliasOfDissociationMethod() throws NoSuchFieldException {
        assertThat(CvTerms.SUPPLEMENTAL_ACTIVATION).isEqualTo(CvTerms.DISSOCIATION_METHOD).isEqualTo("MS:1000044");
        assertThat(CvTerms.class.getField("SUPPLEMENTAL_ACTIVATION").isAnnotationPresent(Deprecated.class)).isTrue();
        assertThat(CvTerms.class.getField("DISSOCIATION_METHOD").isAnnotationPresent(Deprecated.class)).isFalse();

        assertThat(List.of(CvTerms.CID, CvTerms.HCD, CvTerms.ETD, CvTerms.ECD, CvTerms.IRMPD))
                .containsExactly("MS:1000133", "MS:1000422", "MS:1000598", "MS:1000250", "MS:1000262");
        assertThat(CvTerms.DISSOCIATION_METHODS)
                .containsEntry(CvTerms.UVPD, "ultraviolet photodissociation")
                .containsEntry(CvTerms.SUPPLEMENTAL_CID, "supplemental collision-induced dissociation")
                .containsEntry(CvTerms.SUPPLEMENTAL_BEAM_CID, "supplemental beam-type collision-induced dissociation");
        assertThat(CvTerms.ETHCD).isEqualTo("MS:1002631");
    }

    // ---- (d) regeneration from the psi-ms OBO ------------------------------------------------------------------

    /**
     * Regenerates the three tables from the psi-ms OBO file that {@code -Dmzpeak.psims.obo} names (plain or gzip,
     * such as mzdata v0.68.0 cv/psi-ms.obo.gz) with the rule recorded in {@link CvTerms}, and compares them with the
     * checked-in constants, order and names included.
     */
    @Test
    @EnabledIfSystemProperty(named = "mzpeak.psims.obo", matches = ".+")
    void theTablesRegenerateFromThePsiMsObo() throws IOException {
        Obo obo = Obo.read(Path.of(System.getProperty("mzpeak.psims.obo")));
        assertThat(obo.dataVersion).as("data-version").isEqualTo("4.2.11");

        assertThat(obo.children("MS:1002892")).containsExactlyElementsOf(CvTerms.ION_MOBILITY_TYPES);

        Set<String> arrays = obo.descendants("MS:1002893");
        arrays.add("MS:1002893");
        assertThat(arrays).containsExactlyElementsOf(CvTerms.ION_MOBILITY_ARRAYS);

        Map<String, String> methods = new LinkedHashMap<>();
        for (String id : obo.descendants("MS:1000044")) {
            methods.put(id, obo.names.get(id));
        }
        assertThat(CvTerms.DISSOCIATION_METHODS).containsExactlyEntriesOf(methods);
    }

    /** The is_a graph and names of the non-obsolete [Term] stanzas of an OBO file. */
    private static final class Obo {
        String dataVersion;
        final Map<String, String> names = new HashMap<>();
        final Map<String, List<String>> children = new HashMap<>();

        static Obo read(Path path) throws IOException {
            Obo obo = new Obo();
            InputStream in = Files.newInputStream(path);
            if (path.getFileName().toString().endsWith(".gz")) {
                in = new GZIPInputStream(in);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                boolean header = true;
                boolean term = false;
                String id = null;
                String name = null;
                boolean obsolete = false;
                List<String> parents = new ArrayList<>();
                for (String line = reader.readLine(); ; line = reader.readLine()) {
                    if (line == null || line.startsWith("[")) {
                        if (term && id != null && !obsolete) {
                            obo.names.put(id, name);
                            for (String parent : parents) {
                                obo.children.computeIfAbsent(parent, k -> new ArrayList<>()).add(id);
                            }
                        }
                        if (line == null) {
                            break;
                        }
                        header = false;
                        term = line.trim().equals("[Term]");
                        id = null;
                        name = null;
                        obsolete = false;
                        parents = new ArrayList<>();
                        continue;
                    }
                    int colon = line.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String tag = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (header) {
                        if (tag.equals("data-version")) {
                            obo.dataVersion = value;
                        }
                    } else if (term) {
                        switch (tag) {
                            case "id" -> id = value;
                            case "name" -> name = value;
                            case "is_a" -> parents.add(value.split("[\\s!{]", 2)[0]);
                            case "is_obsolete" -> obsolete = value.equals("true");
                            default -> { }
                        }
                    }
                }
            }
            return obo;
        }

        /** The direct is_a children of {@code id}, in accession order. */
        Set<String> children(String id) {
            return new TreeSet<>(children.getOrDefault(id, List.of()));
        }

        /** The is_a descendants of {@code id}, without {@code id}, in accession order. */
        Set<String> descendants(String id) {
            Set<String> out = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(id));
            while (!todo.isEmpty()) {
                for (String child : children.getOrDefault(todo.pop(), List.of())) {
                    if (out.add(child)) {
                        todo.push(child);
                    }
                }
            }
            return out;
        }
    }

    private static ScanEvent scan(List<Param> params) {
        return new ScanEvent(1.0, null, null, null, null, null, List.of(), params);
    }
}
