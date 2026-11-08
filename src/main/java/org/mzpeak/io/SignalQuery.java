package org.mzpeak.io;

import org.mzpeak.model.DoubleRange;
import org.mzpeak.model.SpectrumDescription;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * A range query over the spectra of a dataset, for {@link MzPeakReader#select}, {@link MzPeakReader#extractSignal}
 * and {@link MzPeakReader#queryPeaks}. Immutable: start from {@link #all()}, {@link #timeRange} or
 * {@link #indexRange} and narrow it with the {@code with...} methods, each of which returns a new instance. Two
 * instances are equal when they select and filter alike.
 *
 * <p>Spectra are selected by their metadata: a time or index range, and optionally MS levels. The m/z and
 * ion-mobility filters do not select spectra; they select the points of each selected spectrum. Every range is
 * closed (both bounds included) and compared exactly, without a tolerance.
 */
public final class SignalQuery {

    private static final SignalQuery ALL = new SignalQuery(null, -1, -1, null, null, 0, 0);

    /** The retention-time range, or null. */
    private final DoubleRange time;
    /** The first and last spectrum index, or -1 when the query has no index range. */
    private final long firstIndex;
    private final long lastIndex;
    /** The m/z range, or null. */
    private final DoubleRange mz;
    /** The ion-mobility range, or null. */
    private final DoubleRange ionMobility;
    /** The lowest and highest MS level, or 0 when the query has no MS-level filter. */
    private final int minMsLevel;
    private final int maxMsLevel;

    private SignalQuery(DoubleRange time, long firstIndex, long lastIndex, DoubleRange mz, DoubleRange ionMobility,
                        int minMsLevel, int maxMsLevel) {
        this.time = time;
        this.firstIndex = firstIndex;
        this.lastIndex = lastIndex;
        this.mz = mz;
        this.ionMobility = ionMobility;
        this.minMsLevel = minMsLevel;
        this.maxMsLevel = maxMsLevel;
    }

    /** Every spectrum, with no filter. */
    public static SignalQuery all() {
        return ALL;
    }

    /**
     * The spectra whose retention time lies in {@code [start, end]}, in the file's time unit (typically minutes). A
     * spectrum whose retention time is not finite is never selected. A bound may be infinite.
     *
     * @throws IllegalArgumentException when a bound is NaN or {@code start > end}
     */
    public static SignalQuery timeRange(double start, double end) {
        return new SignalQuery(new DoubleRange(start, end), -1, -1, null, null, 0, 0);
    }

    /**
     * The spectra whose index lies in {@code [first, last]}.
     *
     * @throws IllegalArgumentException unless {@code 0 <= first <= last}
     */
    public static SignalQuery indexRange(long first, long last) {
        if (first < 0 || first > last) {
            throw new IllegalArgumentException("index range must satisfy 0 <= first <= last: [" + first + ", "
                    + last + "]");
        }
        return new SignalQuery(null, first, last, null, null, 0, 0);
    }

    /**
     * This query keeping only the points whose m/z lies in {@code [low, high]}, as a new instance; replaces an
     * earlier m/z filter. A NaN m/z never matches.
     *
     * @throws IllegalArgumentException when a bound is NaN or {@code low > high}
     */
    public SignalQuery withMz(double low, double high) {
        return new SignalQuery(time, firstIndex, lastIndex, new DoubleRange(low, high), ionMobility, minMsLevel,
                maxMsLevel);
    }

    /**
     * This query keeping only the points whose ion mobility lies in {@code [low, high]}, as a new instance; replaces
     * an earlier ion-mobility filter. The filter applies to the spectra that have an ion mobility array
     * ({@link org.mzpeak.model.Spectrum#ionMobility()}), in that array's unit, and drops their points without an ion
     * mobility value (NaN); it leaves the points of the other spectra untouched.
     *
     * @throws IllegalArgumentException when a bound is NaN or {@code low > high}
     */
    public SignalQuery withIonMobility(double low, double high) {
        return new SignalQuery(time, firstIndex, lastIndex, mz, new DoubleRange(low, high), minMsLevel, maxMsLevel);
    }

    /**
     * This query selecting only the spectra whose MS level lies in {@code [min, max]}, as a new instance; replaces
     * an earlier MS-level filter.
     *
     * @throws IllegalArgumentException unless {@code 1 <= min <= max}
     */
    public SignalQuery withMsLevels(int min, int max) {
        if (min < 1 || min > max) {
            throw new IllegalArgumentException("MS levels must satisfy 1 <= min <= max: [" + min + ", " + max + "]");
        }
        return new SignalQuery(time, firstIndex, lastIndex, mz, ionMobility, min, max);
    }

    /** The retention-time range of a {@link #timeRange} query; empty otherwise. */
    public Optional<DoubleRange> time() {
        return Optional.ofNullable(time);
    }

    /** The first spectrum index of an {@link #indexRange} query; empty otherwise. */
    public OptionalLong firstIndex() {
        return firstIndex < 0 ? OptionalLong.empty() : OptionalLong.of(firstIndex);
    }

    /** The last spectrum index of an {@link #indexRange} query; empty otherwise. */
    public OptionalLong lastIndex() {
        return lastIndex < 0 ? OptionalLong.empty() : OptionalLong.of(lastIndex);
    }

    /** The m/z filter set with {@link #withMz}, if any. */
    public Optional<DoubleRange> mz() {
        return Optional.ofNullable(mz);
    }

    /** The ion-mobility filter set with {@link #withIonMobility}, if any. */
    public Optional<DoubleRange> ionMobility() {
        return Optional.ofNullable(ionMobility);
    }

    /** The lowest MS level set with {@link #withMsLevels}, if any. */
    public OptionalInt minMsLevel() {
        return minMsLevel == 0 ? OptionalInt.empty() : OptionalInt.of(minMsLevel);
    }

    /** The highest MS level set with {@link #withMsLevels}, if any. */
    public OptionalInt maxMsLevel() {
        return maxMsLevel == 0 ? OptionalInt.empty() : OptionalInt.of(maxMsLevel);
    }

    /** {@code true} when this query selects the spectrum {@code d}: its time or index range and its MS levels. */
    boolean selects(SpectrumDescription d) {
        if (time != null && !(Double.isFinite(d.retentionTime()) && time.contains(d.retentionTime()))) {
            return false;
        }
        if (firstIndex >= 0 && (d.index() < firstIndex || d.index() > lastIndex)) {
            return false;
        }
        return minMsLevel == 0 || (d.msLevel() >= minMsLevel && d.msLevel() <= maxMsLevel);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignalQuery other && Objects.equals(time, other.time) && firstIndex == other.firstIndex
                && lastIndex == other.lastIndex && Objects.equals(mz, other.mz)
                && Objects.equals(ionMobility, other.ionMobility) && minMsLevel == other.minMsLevel
                && maxMsLevel == other.maxMsLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(time, firstIndex, lastIndex, mz, ionMobility, minMsLevel, maxMsLevel);
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("SignalQuery[");
        if (time != null) {
            out.append("time=[").append(time.low()).append(", ").append(time.high()).append(']');
        } else if (firstIndex >= 0) {
            out.append("index=[").append(firstIndex).append(", ").append(lastIndex).append(']');
        } else {
            out.append("all");
        }
        if (minMsLevel != 0) {
            out.append(", msLevels=[").append(minMsLevel).append(", ").append(maxMsLevel).append(']');
        }
        if (mz != null) {
            out.append(", mz=[").append(mz.low()).append(", ").append(mz.high()).append(']');
        }
        if (ionMobility != null) {
            out.append(", ionMobility=[").append(ionMobility.low()).append(", ").append(ionMobility.high())
                    .append(']');
        }
        return out.append(']').toString();
    }
}
