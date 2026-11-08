package org.mzpeak.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link Param}: accession matching, the numeric value with its default, and the list lookups. */
class ParamTest {

    private static final Param ENERGY = new Param("collision energy", "MS:1000045", 35.0, "UO:0000266");
    private static final Param USER = new Param("ms level", null, 2L, null);

    @Test
    void hasAccessionNeverMatchesANullAccession() {
        assertThat(ENERGY.hasAccession("MS:1000045")).isTrue();
        assertThat(ENERGY.hasAccession("MS:1000046")).isFalse();
        assertThat(ENERGY.hasAccession(null)).isFalse();
        assertThat(USER.hasAccession(null)).isFalse();
        assertThat(USER.hasAccession("MS:1000045")).isFalse();
    }

    @Test
    void doubleValueFallsBackToTheDefaultForANonNumericValue() {
        assertThat(ENERGY.doubleValue(-1.0)).isEqualTo(35.0);
        assertThat(USER.doubleValue(-1.0)).isEqualTo(2.0);
        assertThat(new Param("filter string", "MS:1000512", "FTMS + p", null).doubleValue(-1.0)).isEqualTo(-1.0);
        assertThat(new Param("centroid spectrum", "MS:1000127", null, null).doubleValue(-1.0)).isEqualTo(-1.0);
        assertThat(new Param("flag", null, Boolean.TRUE, null).doubleValue(7.5)).isEqualTo(7.5);
    }

    @Test
    void findReturnsTheFirstMatchAndToleratesANullList() {
        Param second = new Param("collision energy", "MS:1000045", 40.0, "UO:0000266");
        List<Param> list = Arrays.asList(USER, ENERGY, second);
        assertThat(Param.find(list, "MS:1000045")).containsSame(ENERGY);
        assertThat(Param.contains(list, "MS:1000045")).isTrue();
        assertThat(Param.find(list, "MS:1000512")).isEmpty();
        assertThat(Param.contains(list, "MS:1000512")).isFalse();
        assertThat(Param.find(list, null)).isEmpty();
        assertThat(Param.find(null, "MS:1000045")).isEmpty();
        assertThat(Param.contains(null, "MS:1000045")).isFalse();
    }
}
