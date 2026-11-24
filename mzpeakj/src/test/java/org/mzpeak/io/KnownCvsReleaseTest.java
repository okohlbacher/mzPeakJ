package org.mzpeak.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.io.MzPeakManifest.CvEntry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W8 (design D13-15): conformance.md (l.39-40) requires the {@code uri} of a {@code cv_list} entry to identify a fixed
 * release. A caller's entry of a CV {@link KnownCvs} knows is declared only with positive evidence of one, else
 * {@link KnownCvs}'s entry is declared with one warning; {@link MzPeakWriter.Options#withCvList} rejects an entry of
 * an unknown CV whose URI moves or that has no version. The MS and UO entries are those of the converter fixtures
 * {@code tiny.pwiz.1.1.mzML} and {@code small.mzML.gz}.
 */
class KnownCvsReleaseTest {

    private static final CvEntry KNOWN_MS = KnownCvs.ALL.get(0);
    private static final CvEntry KNOWN_UO = KnownCvs.ALL.get(1);
    private static final String MS_NAME = "Proteomics Standards Initiative Mass Spectrometry Ontology";

    /** What {@link KnownCvs#declare} declares for MS and UO with only {@code caller} given, and its warnings. */
    private static List<CvEntry> declare(List<CvEntry> caller, WriteReport report) {
        return KnownCvs.declare(Map.of(), caller, report);
    }

    @Test
    void tinyPwizMsAtItsSourceForgeCheckoutUriIsReplacedByKnownCvsMsWithOneWarning() {
        String uri = "http://psidev.cvs.sourceforge.net/*checkout*/psidev/psi/psi-ms/mzML/controlledVocabulary/"
                + "psi-ms.obo";
        WriteReport report = new WriteReport();

        List<CvEntry> declared = declare(List.of(new CvEntry("MS", MS_NAME, uri, "2.26.0")), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(KNOWN_MS.uri()).isEqualTo("http://purl.obolibrary.org/obo/ms/4.1.249/psi-ms.obo");
        assertThat(report.warnings()).singleElement().asString().contains("MS", uri, "2.26.0",
                KNOWN_MS.uri(), "4.1.249");
    }

    @Test
    void tinyPwizUoAtItsSourceForgeCheckoutUriIsReplacedByKnownCvsUo() {
        String uri = "http://obo.cvs.sourceforge.net/*checkout*/obo/obo/ontology/phenotype/unit.obo";
        WriteReport report = new WriteReport();

        List<CvEntry> declared = declare(List.of(new CvEntry("UO", "Unit Ontology", uri, "14:07:2009")), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("UO", uri, "14:07:2009");
    }

    @Test
    void smallMzmlMsOnTheMasterBranchOfGitHubIsReplacedByKnownCvsMs() {
        String uri = "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/master/psi-ms.obo";
        WriteReport report = new WriteReport();

        List<CvEntry> declared = declare(List.of(new CvEntry("MS", MS_NAME, uri, "4.1.117")), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("MS", uri, "4.1.117");
    }

    @Test
    void anUnversionedPurlIsReplacedByKnownCvsMs() {
        String uri = "http://purl.obolibrary.org/obo/ms/psi-ms.obo";
        WriteReport report = new WriteReport();

        List<CvEntry> declared = declare(List.of(new CvEntry("MS", MS_NAME, uri, "4.1.249")), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("MS", uri, "4.1.249");
    }

    @Test
    void aPurlOfAnotherReleaseThanTheVersionIsReplacedByKnownCvsMs() {
        String uri = "http://purl.obolibrary.org/obo/ms/4.1.200/psi-ms.obo";
        WriteReport report = new WriteReport();

        List<CvEntry> declared = declare(List.of(new CvEntry("MS", MS_NAME, uri, "4.1.249")), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("MS", uri, "4.1.249");
    }

    @Test
    void knownCvsMsUriWithAnotherVersionIsReplacedByKnownCvsMs() {
        WriteReport report = new WriteReport();

        List<CvEntry> declared = declare(List.of(new CvEntry("MS", MS_NAME, KNOWN_MS.uri(), "4.1.117")), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("MS", KNOWN_MS.uri(), "4.1.117");
    }

    @Test
    void aPurlOfTheVersionsReleaseIsKept() {
        CvEntry ms = new CvEntry("MS", MS_NAME, "http://purl.obolibrary.org/obo/ms/4.1.200/psi-ms.obo", "4.1.200");
        WriteReport report = new WriteReport();

        assertThat(declare(List.of(ms), report)).containsExactly(ms, KNOWN_UO);
        assertThat(report.warnings()).isEmpty();
    }

    /**
     * Positive evidence: an EBI URI with the version or {@code v} + version as a path segment (host matched without
     * case, user info and port), or a raw GitHub URI whose ref is a commit hash; query and fragment are not path.
     */
    @Test
    void otherFixedReleaseUrisAreKept() {
        List<CvEntry> kept = List.of(
                new CvEntry("MS", MS_NAME, "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/"
                        + "0123456789abcdef0123456789ABCDEF01234567/psi-ms.obo", "4.1.117"),
                new CvEntry("UO", "Unit Ontology", "http://user@PURL.obolibrary.org:80/obo/uo/2020-03-20/uo.obo"
                        + "?format=obo#top", "2020-03-20"),
                new CvEntry("EFO", "EFO", "https://www.ebi.ac.uk/efo/releases/v3.80.0/efo.obo", "3.80.0"),
                new CvEntry("OBI", "OBI", "https://www.ebi.ac.uk/obi/2025-01-01/obi.obo", "2025-01-01"));
        WriteReport report = new WriteReport();

        List<CvEntry> declared = KnownCvs.declare(Map.of("EFO", "EFO:0000408", "OBI", "OBI:0000070"), kept, report);

        assertThat(declared).containsExactlyElementsOf(kept);
        assertThat(report.warnings()).isEmpty();
    }

    /**
     * Without positive evidence: a release segment only in the query or fragment, on another host, a GitHub branch
     * or a short ref, no URI scheme, no URI or no version.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://purl.obolibrary.org/obo/ms/psi-ms.obo?release=4.1.117",
            "http://purl.obolibrary.org/obo/ms/psi-ms.obo#4.1.117",
            "https://example.org/obo/ms/4.1.117/psi-ms.obo",
            "https://raw.githubusercontent.com/HUPO-PSI/psi-ms-CV/0123456/psi-ms.obo",
            "https://raw.githubusercontent.com/HUPO-PSI/0123456789abcdef0123456789abcdef01234567",
            "purl.obolibrary.org/obo/ms/4.1.117/psi-ms.obo",
            "-"})
    void aKnownCvWithoutEvidenceOfItsReleaseIsReplacedWithOneWarning(String uri) {
        CvEntry ms = new CvEntry("MS", MS_NAME, uri.equals("-") ? null : uri, "4.1.117");
        WriteReport report = new WriteReport();

        assertThat(declare(List.of(ms), report)).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("MS", String.valueOf(ms.uri()),
                "4.1.117");
    }

    @Test
    void aKnownCvWithoutAVersionIsReplacedWithOneWarning() {
        CvEntry ms = new CvEntry("MS", MS_NAME, "http://purl.obolibrary.org/obo/ms/4.1.117/psi-ms.obo", null);
        WriteReport report = new WriteReport();

        assertThat(declare(List.of(ms), report)).containsExactly(KNOWN_MS, KNOWN_UO);
        assertThat(report.warnings()).singleElement().asString().contains("MS", ms.uri(), "version null");
    }

    /** KnownCvs's own entries are trusted, the IMS one on the imzML master branch included (decision 14). */
    @Test
    void knownCvsOwnEntriesAreKeptWithoutAWarning() {
        Map<String, String> used = Map.of("IMS", "IMS:1000050", "NCIT", "NCIT:C25464");
        CvEntry ims = KnownCvs.ALL.get(9);
        CvEntry ncitWithoutName = new CvEntry("NCIT", null, KnownCvs.ALL.get(6).uri(), KnownCvs.ALL.get(6).version());
        WriteReport report = new WriteReport();

        List<CvEntry> declared = KnownCvs.declare(used, List.of(ims, ncitWithoutName), report);

        assertThat(declared).containsExactly(KNOWN_MS, KNOWN_UO, KnownCvs.ALL.get(6), ims);
        assertThat(report.warnings()).containsExactly("the cv list entry of NCIT has no full_name; metadata.cv_list"
                + " fills in the missing fields from mzPeakJ's entry of NCIT");
    }

    // ---- unknown CVs in Options.withCvList ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.org/xyz/master/xyz.obo",
            "http://xyz.cvs.sourceforge.net/*checkout*/xyz.obo",
            "http://xyz.cvs.sourceforge.net/xyz/1.0/xyz.obo",
            "https://example.org/*checkout*/xyz/1.0/xyz.obo",
            "https://example.org/xyz/main/xyz.obo",
            "https://example.org/xyz/HEAD/xyz.obo",
            "https://example.org/xyz/trunk/xyz.obo",
            "https://raw.githubusercontent.com/xyz/xyz/refs/heads/release/xyz.obo",
            "https://example.org/cvs/xyz/1.0/xyz.obo",
            "https://example.org/viewvc/xyz/1.0/xyz.obo",
            "https://example.org/svn/xyz/1.0/xyz.obo"})
    void withCvListRejectsAnUnknownCvAtAMovingUri(String uri) {
        CvEntry xyz = new CvEntry("XYZ", "XYZ ontology", uri, "1.0");

        assertThatThrownBy(() -> MzPeakWriter.Options.defaults().withCvList(List.of(KNOWN_MS, xyz)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("XYZ").hasMessageContaining(uri);
    }

    @Test
    void withCvListRejectsAnUnknownCvWithoutAVersion() {
        CvEntry xyz = new CvEntry("XYZ", "XYZ ontology", "https://example.org/xyz/1.0/xyz.obo", null);

        assertThatThrownBy(() -> MzPeakWriter.Options.defaults().withCvList(List.of(xyz)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the cv list entry of XYZ (URI https://example.org/xyz/1.0/xyz.obo, version null) does"
                        + " not identify a fixed release of XYZ, as conformance.md requires of metadata.cv_list:"
                        + " it has no version");
    }

    /**
     * An unknown CV at a URI of a fixed release is kept; so are, for {@link KnownCvs#declare} to handle, a known CV
     * at any URI, an unknown CV without a URI and an entry without an id.
     */
    @Test
    void withCvListKeepsAnUnknownCvAtAFixedUriAndLeavesTheRestToDeclare() {
        CvEntry xyz = new CvEntry("XYZ", "XYZ ontology", "https://example.org/xyz/1.0/xyz.obo", "1.0");
        CvEntry ms = new CvEntry("MS", MS_NAME, "https://example.org/master/psi-ms.obo", null);
        CvEntry noUri = new CvEntry("ABC", "ABC ontology", null, "2");
        CvEntry noId = new CvEntry(null, null, "https://example.org/master/x.obo", null);
        List<CvEntry> given = List.of(xyz, ms, noUri, noId);

        assertThat(MzPeakWriter.Options.defaults().withCvList(given).cvList()).containsExactlyElementsOf(given);
        assertThat(KnownCvs.declare(Map.of("XYZ", "XYZ:0000001"), List.of(xyz), new WriteReport()))
                .containsExactly(KNOWN_MS, KNOWN_UO, xyz);
    }
}
