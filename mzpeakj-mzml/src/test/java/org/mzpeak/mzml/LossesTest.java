package org.mzpeak.mzml;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MZ-15: {@link Losses}, the counted warnings of the converter (mzML design 6, 10). */
class LossesTest {

    @Test
    void linesCountEachKindInFirstSeenOrder() {
        Losses losses = new Losses();
        losses.note("mzML@id lost");
        losses.note("spotID lost", 3);
        losses.note("mzML@id lost");
        losses.note("run@sampleRef lost");
        losses.note("spotID lost", 2);

        assertThat(losses.lines()).containsExactly("mzML@id lost (2)", "spotID lost (5)", "run@sampleRef lost (1)");
    }

    @Test
    void aZeroCountNotesNothing() {
        Losses losses = new Losses();
        losses.note("b", 0);
        losses.note("a");
        losses.note("b", 0);
        assertThat(losses.lines()).containsExactly("a (1)");

        losses.note("b", 4);
        assertThat(losses.lines()).containsExactly("a (1)", "b (4)");
    }

    @Test
    void noLossesGiveNoLines() {
        assertThat(new Losses().lines()).isEmpty();
    }

    @Test
    void countsAboveIntRangeAreKept() {
        Losses losses = new Losses();
        losses.note("values", Integer.MAX_VALUE);
        losses.note("values", 2);
        assertThat(losses.lines()).containsExactly("values (2147483649)");
    }

    @Test
    void linesAreASnapshotThatCannotBeModified() {
        Losses losses = new Losses();
        losses.note("a");
        var lines = losses.lines();
        losses.note("b");

        assertThat(lines).containsExactly("a (1)");
        assertThatThrownBy(() -> lines.add("c")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aNegativeCountOrANullKindIsRejected() {
        Losses losses = new Losses();
        assertThatThrownBy(() -> losses.note("a", -1)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("-1");
        assertThatThrownBy(() -> losses.note(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> losses.note(null, 2)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> losses.limit(null)).isInstanceOf(NullPointerException.class);
        assertThat(losses.lines()).isEmpty();
    }

    // ---- JZ-3: limit lines (mzML design 6.0, 8.1) ------------------------------------------------------------------

    @Test
    void limitLinesHaveNoCountAndKeepTheirOrder() {
        Losses losses = new Losses();
        losses.limit("L-3 second");
        losses.limit("L-1 first");
        losses.limit("L-5 third");

        assertThat(losses.lines()).containsExactly("L-3 second", "L-1 first", "L-5 third");
    }

    @Test
    void aRepeatedLimitIsKeptOnce() {
        Losses losses = new Losses();
        losses.limit("L-1 index");
        losses.limit("L-4 units");
        losses.limit("L-1 index");

        assertThat(losses.lines()).containsExactly("L-1 index", "L-4 units");
    }

    @Test
    void limitLinesComeBeforeTheCountedLinesEvenWhenAddedAfterThem() {
        Losses losses = new Losses();
        losses.note("B-3 lost", 2);
        losses.limit("L-1 index");
        losses.note("B-1 expanded");
        losses.limit("L-6 lenient");
        losses.note("B-3 lost");

        assertThat(losses.lines()).containsExactly("L-1 index", "L-6 lenient", "B-3 lost (3)", "B-1 expanded (1)");
    }
}
