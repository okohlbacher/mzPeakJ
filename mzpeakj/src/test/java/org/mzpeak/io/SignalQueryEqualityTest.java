package org.mzpeak.io;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SignalQuery#equals} and {@link SignalQuery#hashCode}: two queries are equal when every component is, and a
 * query differing in any single component (time range, first or last index, m/z, ion mobility, lowest or highest MS
 * level) is not equal. {@link SignalQueryTest} covers selection and the accessors.
 */
class SignalQueryEqualityTest {

    @Test
    void equalQueriesAgreeOnEveryComponentAndHashAlike() {
        assertThat(byIndex()).isEqualTo(byIndex()).hasSameHashCodeAs(byIndex());
        assertThat(byTime()).isEqualTo(byTime()).hasSameHashCodeAs(byTime());
        assertThat(SignalQuery.all()).isEqualTo(SignalQuery.all());
    }

    @Test
    void aQueryIsNotEqualToNullOrAnotherType() {
        SignalQuery query = byIndex();
        assertThat(query.equals(null)).isFalse();
        assertThat(query.equals(query.toString())).isFalse();
    }

    @Test
    void eachComponentTakesPartInEquality() {
        SignalQuery index = byIndex();
        assertThat(SignalQuery.indexRange(11, 20).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(1, 2))
                .as("first index").isNotEqualTo(index);
        assertThat(SignalQuery.indexRange(10, 21).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(1, 2))
                .as("last index").isNotEqualTo(index);
        assertThat(SignalQuery.indexRange(10, 20).withMz(800, 821).withIonMobility(0.8, 0.9).withMsLevels(1, 2))
                .as("m/z").isNotEqualTo(index);
        assertThat(SignalQuery.indexRange(10, 20).withMz(800, 820).withIonMobility(0.8, 1.0).withMsLevels(1, 2))
                .as("ion mobility").isNotEqualTo(index);
        assertThat(SignalQuery.indexRange(10, 20).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(2, 2))
                .as("lowest MS level").isNotEqualTo(index);
        assertThat(SignalQuery.indexRange(10, 20).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(1, 3))
                .as("highest MS level").isNotEqualTo(index);

        assertThat(SignalQuery.timeRange(0.3, 0.5).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(1, 2))
                .as("time range").isNotEqualTo(byTime());
        assertThat(byTime()).as("a time range and an index range").isNotEqualTo(index);
    }

    /** Every component set, selecting by index. */
    private static SignalQuery byIndex() {
        return SignalQuery.indexRange(10, 20).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(1, 2);
    }

    /** Every component set, selecting by time. */
    private static SignalQuery byTime() {
        return SignalQuery.timeRange(0.3, 0.4).withMz(800, 820).withIonMobility(0.8, 0.9).withMsLevels(1, 2);
    }
}
