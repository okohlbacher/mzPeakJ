package org.mzpeak.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * PSI-MS controlled-vocabulary accession constants used throughout mzPeakJ. Only accessions that are
 * actively referenced in code or tests are listed here; this is not a full CV mirror. The term tables
 * {@link #ION_MOBILITY_TYPES}, {@link #ION_MOBILITY_ARRAYS} and {@link #DISSOCIATION_METHODS} are generated
 * subsets of psi-ms 4.2.11.
 */
public final class CvTerms {

    private CvTerms() {
    }

    // --- Dissociation methods (descendants of MS:1000044) ---
    /**
     * Dissociation method, the parent term of all dissociation methods; also the accession of the
     * {@code dissociation_method} column, whose cell names the method.
     */
    public static final String DISSOCIATION_METHOD = "MS:1000044";
    /** Collision-induced dissociation (CID). */
    public static final String CID = "MS:1000133";
    /** Beam-type collision-induced dissociation (HCD). */
    public static final String HCD = "MS:1000422";
    /** Electron transfer dissociation (ETD). */
    public static final String ETD = "MS:1000598";
    /** Electron capture dissociation (ECD). */
    public static final String ECD = "MS:1000250";
    /** Infrared multiphoton dissociation (IRMPD). */
    public static final String IRMPD = "MS:1000262";
    /** Ultraviolet photodissociation (UVPD). */
    public static final String UVPD = "MS:1003246";
    /** Supplemental collision-induced dissociation. */
    public static final String SUPPLEMENTAL_CID = "MS:1002679";
    /** Supplemental beam-type collision-induced dissociation; with {@link #ETD} it describes EThcD. */
    public static final String SUPPLEMENTAL_BEAM_CID = "MS:1002678";
    /**
     * Supplemental activation.
     *
     * @deprecated MS:1000044 is 'dissociation method', the parent term of the methods, not a supplemental
     *             activation. Use {@link #DISSOCIATION_METHOD}, or {@link #SUPPLEMENTAL_CID} and
     *             {@link #SUPPLEMENTAL_BEAM_CID} for supplemental activation.
     */
    @Deprecated
    public static final String SUPPLEMENTAL_ACTIVATION = DISSOCIATION_METHOD;
    /**
     * Electron-transfer/higher-energy collision dissociation (EThcD). Not a dissociation method in PSI-MS: it is a
     * child of MS:1003181 'combined dissociation method', a precursor activation attribute (MS:1000510), not of
     * MS:1000044. It is therefore not in {@link #DISSOCIATION_METHODS}, and {@link Activation#methods()} does not
     * report it. mzdata describes EThcD as {@link #ETD} plus {@link #SUPPLEMENTAL_BEAM_CID}.
     */
    public static final String ETHCD = "MS:1002631";

    // --- Precursor activation attributes ---
    /** Collision energy (value in eV). */
    public static final String COLLISION_ENERGY = "MS:1000045";

    // --- Spectrum types ---
    public static final String MS_LEVEL = "MS:1000511";
    public static final String PROFILE_SPECTRUM = "MS:1000128";
    public static final String CENTROID_SPECTRUM = "MS:1000127";

    // --- Instrument ---
    public static final String RESOLUTION = "MS:1000028";

    // --- Selected ion ---
    public static final String SELECTED_ION_MZ = "MS:1000744";
    public static final String CHARGE_STATE = "MS:1000041";
    public static final String SELECTED_ION_INTENSITY = "MS:1000042";

    // --- Generated term tables ---
    // Generated once from psi-ms.obo data-version 4.2.11, as bundled with mzdata v0.68.0 (cv/psi-ms.obo.gz).
    // Only is_a edges are followed (relationship lines such as part_of are not), obsolete terms are skipped, and
    // each table is in accession order. Recorded rule for the dissociation methods: the descendants of MS:1000044
    // only, as the reference writer (children_of_recursive(MS:1000044)) and mzdata's DissociationMethodTerm use;
    // MS:1002631 (EThcD) is_a MS:1003181 'combined dissociation method' is_a MS:1000510 and is not listed.
    // IonMobilityAndActivationTermsTest regenerates the three tables from the OBO file that
    // -Dmzpeak.psims.obo=<path> names and compares them with these.

    /**
     * Ion mobility types: the direct children of MS:1002892 'ion mobility attribute' (7 terms), the terms a scalar
     * ion mobility value of a scan or selected ion is stored under. Unmodifiable, in accession order;
     * {@code contains(null)} is false.
     */
    public static final Set<String> ION_MOBILITY_TYPES = orderedSet(
            "MS:1001581", // FAIMS compensation voltage
            "MS:1002476", // ion mobility drift time
            "MS:1002815", // inverse reduced ion mobility
            "MS:1003371", // SelexION compensation voltage
            "MS:1003394", // SelexION separation voltage
            "MS:1003450", // FAIMS compensation voltage ramp start
            "MS:1003451"  // FAIMS compensation voltage ramp end
    );

    /**
     * Ion mobility array types: MS:1002893 'ion mobility array' and its descendants (10 terms). Unmodifiable, in
     * accession order; {@code contains(null)} is false.
     */
    public static final Set<String> ION_MOBILITY_ARRAYS = orderedSet(
            "MS:1002477", // mean ion mobility drift time array
            "MS:1002816", // mean ion mobility array
            "MS:1002893", // ion mobility array
            "MS:1003006", // mean inverse reduced ion mobility array
            "MS:1003007", // raw ion mobility array
            "MS:1003008", // raw inverse reduced ion mobility array
            "MS:1003153", // raw ion mobility drift time array
            "MS:1003154", // deconvoluted ion mobility array
            "MS:1003155", // deconvoluted inverse reduced ion mobility array
            "MS:1003156"  // deconvoluted ion mobility drift time array
    );

    /**
     * Dissociation methods: the descendants of MS:1000044 'dissociation method' (22 terms), mapped to their PSI-MS
     * names. MS:1000044 itself is not listed. Unmodifiable, in accession order; {@code containsKey(null)} is false.
     */
    public static final Map<String, String> DISSOCIATION_METHODS = orderedMap(
            "MS:1000133", "collision-induced dissociation",
            "MS:1000134", "plasma desorption",
            "MS:1000135", "post-source decay",
            "MS:1000136", "surface-induced dissociation",
            "MS:1000242", "blackbody infrared radiative dissociation",
            "MS:1000250", "electron capture dissociation",
            "MS:1000262", "infrared multiphoton dissociation",
            "MS:1000282", "sustained off-resonance irradiation",
            "MS:1000422", "beam-type collision-induced dissociation",
            "MS:1000433", "low-energy collision-induced dissociation",
            "MS:1000435", "photodissociation",
            "MS:1000598", "electron transfer dissociation",
            "MS:1000599", "pulsed q dissociation",
            "MS:1001880", "in-source collision-induced dissociation",
            "MS:1002000", "LIFT",
            "MS:1002472", "trap-type collision-induced dissociation",
            "MS:1002481", "higher energy beam-type collision-induced dissociation",
            "MS:1002678", "supplemental beam-type collision-induced dissociation",
            "MS:1002679", "supplemental collision-induced dissociation",
            "MS:1003246", "ultraviolet photodissociation",
            "MS:1003247", "negative electron transfer dissociation",
            "MS:1003294", "electron activated dissociation"
    );

    private static Set<String> orderedSet(String... accessions) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(accessions)));
    }

    /** An unmodifiable map from alternating accession/name pairs, in argument order. */
    private static Map<String, String> orderedMap(String... accessionNamePairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < accessionNamePairs.length; i += 2) {
            out.put(accessionNamePairs[i], accessionNamePairs[i + 1]);
        }
        return Collections.unmodifiableMap(out);
    }
}
