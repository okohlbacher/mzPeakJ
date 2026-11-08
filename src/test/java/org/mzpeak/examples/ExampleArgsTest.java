package org.mzpeak.examples;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The command-line parser shared by the example tools, and the exit helper their main methods end with. */
class ExampleArgsTest {

    @Test
    void separatesPositionalArgumentsFromTheThreeOptionForms() {
        ExampleArgs a = new ExampleArgs(new String[] {
                "in.mzpeak", "--ms-level", "2", "--mz=500.25", "--centroid", "--rt", "1.5", "out.mzpeak", "--last"});

        assertThat(a.positional).containsExactly("in.mzpeak", "out.mzpeak");
        // --key value, --key=value, and a flag followed by another option or by nothing
        assertThat(a.options).containsExactlyInAnyOrderEntriesOf(Map.of(
                "ms-level", "2", "mz", "500.25", "centroid", "true", "rt", "1.5", "last", "true"));
    }

    @Test
    void splitsAnEqualsFormAtItsFirstEqualsSign() {
        ExampleArgs a = new ExampleArgs(new String[] {"--filter=a=b", "--empty="});

        assertThat(a.options).containsExactlyInAnyOrderEntriesOf(Map.of("filter", "a=b", "empty", ""));
        assertThat(a.positional).isEmpty();
    }

    @Test
    void joinsRepeatedOptionsWithCommasInArgumentOrder() {
        ExampleArgs a = new ExampleArgs(new String[] {"--ms-level", "1", "--ms-level=2", "--ms-level"});

        assertThat(a.options).containsOnly(Map.entry("ms-level", "1,2,true"));
    }

    @Test
    void readsNoArgumentsAsNoPositionalsAndNoOptions() {
        ExampleArgs a = new ExampleArgs(new String[0]);

        assertThat(a.positional).isEmpty();
        assertThat(a.options).isEmpty();
    }

    @Test
    void optReturnsTheValueOrTheDefault() {
        ExampleArgs a = new ExampleArgs(new String[] {"--name", "tic"});

        assertThat(a.opt("name", "bpc")).isEqualTo("tic");
        assertThat(a.opt("other", "bpc")).isEqualTo("bpc");
        assertThat(a.opt("other", null)).isNull();
    }

    @Test
    void optDoubleAndOptIntParseTheValueOrReturnTheDefault() {
        ExampleArgs a = new ExampleArgs(new String[] {"--tol", "0.02", "--charge", "3"});

        assertThat(a.optDouble("tol", 0.5)).isEqualTo(0.02);
        assertThat(a.optDouble("missing", 0.5)).isEqualTo(0.5);
        assertThat(a.optInt("charge", 1)).isEqualTo(3);
        assertThat(a.optInt("missing", 1)).isEqualTo(1);
    }

    @Test
    void optDoubleAndOptIntRejectValuesThatAreNotNumbers() {
        ExampleArgs a = new ExampleArgs(new String[] {"--tol", "wide", "--charge", "2.5", "--flag"});

        assertThatThrownBy(() -> a.optDouble("tol", 0.5))
                .isInstanceOf(NumberFormatException.class).hasMessageContaining("wide");
        assertThatThrownBy(() -> a.optInt("charge", 1))
                .isInstanceOf(NumberFormatException.class).hasMessageContaining("2.5");
        // a flag's value is the string "true", which is not a number either
        assertThatThrownBy(() -> a.optInt("flag", 1))
                .isInstanceOf(NumberFormatException.class).hasMessageContaining("true");
    }

    @Test
    void exitHandsOnlyANonZeroStatusToTheExiter() {
        List<Integer> statuses = new ArrayList<>();

        ExampleArgs.exit(0, statuses::add);
        assertThat(statuses).isEmpty();

        ExampleArgs.exit(2, statuses::add);
        assertThat(statuses).containsExactly(2);
    }
}
