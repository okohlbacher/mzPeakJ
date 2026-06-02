package org.mzpeak.model;

/** Something with a known charge state. Mirrors {@code KnownCharge} from {@code mzpeaks}. */
public interface KnownCharge {
    /** Charge state (signed; sign follows polarity). */
    int charge();
}
