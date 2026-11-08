package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.CvEntry;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link KnownCvs}: the CURIE prefix the writer collects, and the {@code cv_list} it declares for the collected
 * prefixes.
 */
class KnownCvsTest {

    /** The prefix is the non-empty text before the first colon; anything else has none. */
    @Test
    void prefixIsTheTextBeforeTheFirstColon() {
        assertThat(KnownCvs.prefix("MS:1000511")).isEqualTo("MS");
        assertThat(KnownCvs.prefix("UO:0000031")).isEqualTo("UO");
        assertThat(KnownCvs.prefix("a:b:c")).isEqualTo("a");
        assertThat(KnownCvs.prefix("1000511")).isNull();
        assertThat(KnownCvs.prefix(":1000511")).isNull();
        assertThat(KnownCvs.prefix("")).isNull();
        assertThat(KnownCvs.prefix(null)).isNull();
    }

    /** MS and UO always, then each used known CV in the table's order; an unknown prefix only warns. */
    @Test
    void declaresMsUoAndTheUsedKnownCvsInTableOrder() {
        Map<String, String> used = new LinkedHashMap<>();
        used.put("IMS", "IMS:1000050");
        used.put("XX", "XX:0001");
        used.put("NCIT", "NCIT:C25464");
        WriteReport report = new WriteReport();

        assertThat(KnownCvs.declare(used, report)).extracting(CvEntry::id)
                .containsExactly("MS", "UO", "NCIT", "IMS");
        assertThat(report.warnings()).containsExactly("the CV prefix XX (used by XX:0001) is not a controlled "
                + "vocabulary mzPeakJ knows; its terms are written as given, but metadata.cv_list does not "
                + "declare it");
        assertThat(KnownCvs.declare(Map.of(), new WriteReport())).containsExactly(KnownCvs.ALL.get(0),
                KnownCvs.ALL.get(1));
    }
}
