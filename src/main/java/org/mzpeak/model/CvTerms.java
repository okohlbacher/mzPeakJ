package org.mzpeak.model;

/**
 * PSI-MS controlled-vocabulary accession constants used throughout mzPeakJ. Only accessions that are
 * actively referenced in code or tests are listed here; this is not a full CV mirror.
 */
public final class CvTerms {

    private CvTerms() {
    }

    // --- Activation methods (children of MS:1000044) ---
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
    /** Supplemental activation. */
    public static final String SUPPLEMENTAL_ACTIVATION = "MS:1000044";

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
}
