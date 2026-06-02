package org.mzpeak.model;

/** Something with a position within a sorted collection. Mirrors {@code IndexedCoordinate} from {@code mzpeaks}. */
public interface Indexed {
    /** Position within the owning collection (0-based), or -1 if unset. */
    int index();
}
