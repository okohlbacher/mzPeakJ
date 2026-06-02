package org.mzpeak.model;

/**
 * Something located at a position in a coordinate system (m/z, neutral mass, time, ...).
 * Mirrors the {@code CoordinateLike} trait from the Rust {@code mzpeaks} crate.
 */
public interface CoordinateLike {
    /** The position in this entity's primary coordinate system. */
    double coordinate();
}
