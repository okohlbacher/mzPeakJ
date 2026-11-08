package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mzpeak.io.MzPeakReader.Options;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AP-1: {@link MzPeakReader.Options} and the {@code open} overloads that take it (design sections 9.2 and 10).
 *
 * <p>Oracle: the existing {@link MzPeakReader#open(Path, boolean)}, whose output the {@link Options} overloads must
 * reproduce on every fixture of both sets, compared through {@link TestSources#content(MzPeakReader)}.
 */
class ReaderOptionsTest {

    static Stream<Path> allFixtures() {
        return Stream.concat(Fixtures.CURRENT_NAMES.stream().map(Fixtures::current),
                Fixtures.LEGACY_NAMES.stream().map(Fixtures::legacy));
    }

    @ParameterizedTest
    @MethodSource("allFixtures")
    void optionsWithoutReconstructionReadLikeTheBooleanOverload(Path fixture) {
        try (MzPeakReader expected = MzPeakReader.open(fixture, false);
             MzPeakReader actual = MzPeakReader.open(fixture, Options.defaults().withReconstructProfile(false))) {
            assertThat(TestSources.content(actual)).containsExactlyElementsOf(TestSources.content(expected));
            assertThat(actual.options()).isEqualTo(expected.options());
            assertThat(actual.options().reconstructProfile()).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("allFixtures")
    void defaultOptionsReadLikeThePathOverload(Path fixture) {
        try (MzPeakReader expected = MzPeakReader.open(fixture);
             MzPeakReader actual = MzPeakReader.open(fixture, Options.defaults())) {
            assertThat(TestSources.content(actual)).containsExactlyElementsOf(TestSources.content(expected));
            assertThat(expected.options()).isEqualTo(Options.defaults());
        }
    }

    @Test
    void reconstructProfileReachesTheDecoder() {
        // spectrum 0 of small is a null-marked profile: 13589 points reconstructed, fewer stored
        Path fixture = Fixtures.legacy("small.unpacked.mzpeak");
        try (MzPeakReader filled = MzPeakReader.open(fixture, Options.defaults());
             MzPeakReader stored = MzPeakReader.open(fixture, Options.defaults().withReconstructProfile(false));
             MzPeakReader storedFromSource = MzPeakReader.open(TestSources.memory(fixture),
                     Options.defaults().withReconstructProfile(false))) {
            assertThat(filled.getSpectrumAt(0).pointCount()).isEqualTo(13589);
            int storedPoints = stored.getSpectrumAt(0).pointCount();
            assertThat(storedPoints).isLessThan(13589);
            assertThat(storedFromSource.getSpectrumAt(0).pointCount()).isEqualTo(storedPoints);
            assertThat(storedFromSource.options().reconstructProfile()).isFalse();
            // the content comparison of the parameterized tests sees the difference the option makes
            assertThat(TestSources.content(stored)).isNotEqualTo(TestSources.content(filled));
        }
    }

    @Test
    void openReportsTheOptionsItWasGiven() {
        Path fixture = Fixtures.legacy("small.unpacked.mzpeak");
        Options stored = Options.defaults().withReconstructProfile(false);
        try (MzPeakReader byPath = MzPeakReader.open(fixture);
             MzPeakReader byBoolean = MzPeakReader.open(fixture, true);
             MzPeakReader bySource = MzPeakReader.open(TestSources.memory(fixture));
             MzPeakReader withOptions = MzPeakReader.open(TestSources.memory(fixture), stored)) {
            assertThat(byPath.options()).isEqualTo(Options.defaults());
            assertThat(byBoolean.options()).isEqualTo(Options.defaults());
            assertThat(bySource.options()).isEqualTo(Options.defaults());
            assertThat(withOptions.options()).isEqualTo(stored);
        }
    }

    @Test
    void defaultsReconstructProfiles() {
        assertThat(Options.defaults().reconstructProfile()).isTrue();
        assertThat(Options.defaults()).isEqualTo(Options.defaults()).hasSameHashCodeAs(Options.defaults());
    }

    @Test
    void withersReturnNewInstancesAndLeaveTheReceiverUnchanged() {
        Options defaults = Options.defaults();
        Options stored = defaults.withReconstructProfile(false);
        Options same = defaults.withReconstructProfile(true);

        assertThat(stored).isNotSameAs(defaults);
        assertThat(same).isNotSameAs(defaults);
        assertThat(defaults.reconstructProfile()).isTrue();
        assertThat(stored.reconstructProfile()).isFalse();
        assertThat(same.reconstructProfile()).isTrue();
        assertThat(stored.withReconstructProfile(true)).isEqualTo(defaults);
    }

    @Test
    void equalityIsValueBased() {
        Options a = Options.defaults().withReconstructProfile(false);
        Options b = Options.defaults().withReconstructProfile(true).withReconstructProfile(false);

        assertThat(a).isEqualTo(b).isNotSameAs(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(Options.defaults());
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("Options[reconstructProfile=false]")).isFalse();
        assertThat(a.toString()).contains("reconstructProfile=false");
        assertThat(Options.defaults().toString()).contains("reconstructProfile=true");
    }

    @Test
    void optionsIsAFinalClassWithoutPublicConstructors() throws NoSuchMethodException {
        assertThat(Modifier.isFinal(Options.class.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(Options.class.getModifiers())).isTrue();
        assertThat(Modifier.isPublic(Options.class.getModifiers())).isTrue();
        assertThat(Options.class.getConstructors()).isEmpty();
        assertThat(Options.class.isRecord()).isFalse();
        assertThat(Modifier.isStatic(Options.class.getMethod("defaults").getModifiers())).isTrue();
    }

    @Test
    void existingOpenOverloadsAreKept() throws NoSuchMethodException {
        for (Class<?>[] parameters : List.of(
                new Class<?>[] {Path.class},
                new Class<?>[] {Path.class, boolean.class},
                new Class<?>[] {Path.class, Options.class},
                new Class<?>[] {MzPeakSource.class},
                new Class<?>[] {MzPeakSource.class, Options.class})) {
            Method open = MzPeakReader.class.getMethod("open", parameters);
            assertThat(Modifier.isStatic(open.getModifiers())).isTrue();
            assertThat(open.getReturnType()).isEqualTo(MzPeakReader.class);
        }
    }
}
