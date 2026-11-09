package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakManifest.CvEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

        assertThat(KnownCvs.declare(used, List.of(), report)).extracting(CvEntry::id)
                .containsExactly("MS", "UO", "NCIT", "IMS");
        assertThat(report.warnings()).containsExactly("the CV prefix XX (used by XX:0001) is not a controlled "
                + "vocabulary mzPeakJ knows; its terms are written as given, but metadata.cv_list does not "
                + "declare it");
        assertThat(KnownCvs.declare(Map.of(), List.of(), new WriteReport())).containsExactly(KnownCvs.ALL.get(0),
                KnownCvs.ALL.get(1));
    }

    /**
     * MZ-8: a caller entry replaces the built-in one of its id and declares an unknown prefix in use after the known
     * ones, in the caller's order and without a warning; an entry for an unused prefix is not declared, nor is one
     * without an id, which is warned about.
     */
    @Test
    void declaresTheCallersEntriesForThePrefixesInUse() {
        Map<String, String> used = new LinkedHashMap<>();
        used.put("YY", "YY:0001");
        used.put("XX", "XX:0001");
        used.put("ZZ", "ZZ:0001");
        used.put("NCIT", "NCIT:C25464");
        CvEntry xx = new CvEntry("XX", "XX ontology", "https://example.org/xx.obo", "1");
        CvEntry yy = new CvEntry("YY", "YY ontology", "https://example.org/yy.obo", "2");
        CvEntry ms = new CvEntry("MS", "PSI-MS", "https://example.org/ms.obo", "4.1.117");
        List<CvEntry> caller = List.of(xx, new CvEntry(null, "no id", null, null), ms,
                new CvEntry("BTO", "BTO", "https://example.org/bto.owl", "1"), yy);
        WriteReport report = new WriteReport();

        assertThat(KnownCvs.declare(used, caller, report)).containsExactly(ms, KnownCvs.ALL.get(1),
                KnownCvs.ALL.get(6), xx, yy);
        assertThat(report.warnings()).containsExactly("a cv list entry without an id (CvEntry[id=null, fullName=no"
                + " id, uri=null, version=null]) is not declared in metadata.cv_list", "the CV prefix ZZ (used by"
                + " ZZ:0001) is not a controlled vocabulary mzPeakJ knows; its terms are written as given, but"
                + " metadata.cv_list does not declare it");
    }

    /**
     * mzML's cv@version is optional: a caller entry of a known CV that lacks fields is declared with the built-in
     * entry's values for them, one warning per CV naming the fields; one of an unused CV is neither declared nor
     * warned about.
     */
    @Test
    void aCallerEntryOfAKnownCvTakesTheFieldsItLacksFromTheBuiltInEntry() {
        CvEntry uo = new CvEntry("UO", "Unit Ontology", "https://example.org/unit.obo", null);
        CvEntry ms = new CvEntry("MS", "PSI-MS", "https://example.org/ms.obo", null);
        CvEntry bto = new CvEntry("BTO", null, null, "2019");
        CvEntry ncit = new CvEntry("NCIT", null, null, null);
        WriteReport report = new WriteReport();

        List<CvEntry> declared = KnownCvs.declare(Map.of("BTO", "BTO:0000142"), List.of(uo, ms, bto, ncit), report);

        assertThat(declared).containsExactly(new CvEntry("MS", "PSI-MS", "https://example.org/ms.obo", "4.1.249"),
                new CvEntry("UO", "Unit Ontology", "https://example.org/unit.obo", "2026-01-16"),
                new CvEntry("BTO", "The BRENDA Tissue Ontology (BTO)",
                        "http://purl.obolibrary.org/obo/bto/releases/2021-10-26/bto.owl", "2019"));
        assertThat(report.warnings()).containsExactly(
                "the cv list entry of MS has no version; metadata.cv_list fills in the missing fields from"
                        + " mzPeakJ's entry of MS",
                "the cv list entry of UO has no version; metadata.cv_list fills in the missing fields from"
                        + " mzPeakJ's entry of UO",
                "the cv list entry of BTO has no full_name, uri; metadata.cv_list fills in the missing fields from"
                        + " mzPeakJ's entry of BTO");
    }

    /**
     * A caller entry of a CV mzPeakJ does not know that lacks a field is not declared, with the warning of an unknown
     * CV without an entry naming the fields; a complete one is declared.
     */
    @Test
    void anIncompleteCallerEntryOfAnUnknownCvIsNotDeclared() {
        Map<String, String> used = new LinkedHashMap<>();
        used.put("FOO", "FOO:0000001");
        used.put("BAR", "BAR:0000001");
        CvEntry foo = new CvEntry("FOO", "Foo ontology", "https://example.org/foo.obo", null);
        CvEntry bar = new CvEntry("BAR", "Bar ontology", "https://example.org/bar.obo", "1");
        WriteReport report = new WriteReport();

        assertThat(KnownCvs.declare(used, List.of(foo, bar), report)).containsExactly(KnownCvs.ALL.get(0),
                KnownCvs.ALL.get(1), bar);
        assertThat(report.warnings()).containsExactly("the CV prefix FOO (used by FOO:0000001) is not a controlled"
                + " vocabulary mzPeakJ knows, and its cv list entry has no version; its terms are written as given,"
                + " but metadata.cv_list does not declare it");
    }

    /** MZ-8 notes 2 and 3: an entry without an id is skipped with a warning, and its id is never looked up. */
    @Test
    void aCallerEntryWithoutIdIsSkippedWithAWarning() {
        WriteReport report = new WriteReport();
        CvEntry noId = new CvEntry(null, "No id", "https://example.org/x.obo", "1");

        // Map.of rejects a null key in containsKey
        assertThat(KnownCvs.declare(Map.of("MS", "MS:1000511"), List.of(noId), report))
                .containsExactly(KnownCvs.ALL.get(0), KnownCvs.ALL.get(1));
        assertThat(report.warnings()).containsExactly("a cv list entry without an id (CvEntry[id=null, fullName=No"
                + " id, uri=https://example.org/x.obo, version=1]) is not declared in metadata.cv_list");
    }

    /** Every declared entry has all four fields, whatever the caller's entries lack. */
    @Test
    void everyDeclaredEntryHasAllFourFields() {
        Map<String, String> used = new LinkedHashMap<>();
        for (CvEntry cv : KnownCvs.ALL) {
            used.put(cv.id(), cv.id() + ":0000001");
        }
        used.put("FOO", "FOO:0000001");
        List<CvEntry> caller = new ArrayList<>();
        for (CvEntry cv : KnownCvs.ALL) {
            caller.add(new CvEntry(cv.id(), null, null, null));
        }
        caller.add(new CvEntry("FOO", null, "https://example.org/foo.obo", "1"));

        List<CvEntry> declared = KnownCvs.declare(used, caller, new WriteReport());

        assertThat(declared).containsExactlyElementsOf(KnownCvs.ALL);
        assertThat(declared).allSatisfy(cv -> assertThat(cv).hasNoNullFieldsOrProperties());
    }
}
