package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.CvEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The controlled vocabularies {@link ManifestWriter} declares in {@code metadata.cv_list}: a verbatim copy of the
 * reference's {@code From<ControlledVocabulary> for ControlledVocabularyEntry} (param.rs:272-336 at 2ee2589), in its
 * order. Every URI names a fixed release, as conformance.md requires of a writer, except that of IMS, which is the
 * imzML repository's master branch in the reference too. Public as {@link CvEntry#known()}; a caller's cv list
 * ({@link MzPeakWriter.Options#withCvList}) takes precedence, field by field, where its entry identifies a fixed
 * release (design D13-15).
 */
final class KnownCvs {

    /** The known CVs in the reference's order. */
    static final List<CvEntry> ALL = List.of(
            new CvEntry("MS", "Proteomics Standards Initiative Mass Spectrometry Ontology",
                    "http://purl.obolibrary.org/obo/ms/4.1.249/psi-ms.obo", "4.1.249"),
            new CvEntry("UO", "Units of measurement ontology",
                    "http://purl.obolibrary.org/obo/uo/releases/2026-01-16/uo.obo", "2026-01-16"),
            new CvEntry("EFO", "Experimental Factor Ontology",
                    "http://www.ebi.ac.uk/efo/releases/v3.90.0/efo.obo", "v3.90.0"),
            new CvEntry("OBI", "Ontology for Biomedical Investigations",
                    "http://purl.obolibrary.org/obo/obi/2026-05-08/obi.obo", "2026-05-08"),
            new CvEntry("HANCESTRO", "Human Ancestry Ontology",
                    "http://purl.obolibrary.org/obo/hancestro/releases/2025-10-14/hancestro.obo", "2025-10-14"),
            new CvEntry("BFO", "Basic Formal Ontology",
                    "http://purl.obolibrary.org/obo/bfo/2019-08-26/bfo.obo", "2019-08-26"),
            new CvEntry("NCIT", "NCI Thesaurus OBO Edition",
                    "http://purl.obolibrary.org/obo/ncit/releases/2026-03-19/ncit.obo", "26.02d"),
            new CvEntry("BTO", "The BRENDA Tissue Ontology (BTO)",
                    "http://purl.obolibrary.org/obo/bto/releases/2021-10-26/bto.owl", "2021-10-26"),
            new CvEntry("PRIDE", "Proteomics Identification Database Ontology",
                    "http://purl.obolibrary.org/obo/pride/releases/2026-06-01/pride.obo", "2026-06-01"),
            new CvEntry("IMS", "Imaging Mass Spectrometry Ontology",
                    "https://raw.githubusercontent.com/imzML/imzML/refs/heads/master/imagingMS.obo", "1.1.0"));

    /** The CVs declared whether or not they are used, as the reference writer declares them. */
    private static final List<String> ALWAYS = List.of("MS", "UO");

    /** The hosts whose URIs name a release by a path segment equal to the version (or {@code v} + version). */
    private static final Set<String> RELEASE_HOSTS = Set.of("purl.obolibrary.org", "www.ebi.ac.uk");
    /** A git commit hash, the ref segment of a raw GitHub URI of a fixed release. */
    private static final Pattern COMMIT = Pattern.compile("[0-9a-fA-F]{40}");
    /** The path segments, in lower case, of a URI that names a moving branch or a version-control view. */
    private static final Set<String> MOVING_SEGMENTS = Set.of("master", "main", "head", "trunk", "heads", "cvs",
            "viewvc", "svn");

    private KnownCvs() {
    }

    /**
     * The prefix of {@code curie}, the text before its first colon, or {@code null} if {@code curie} is
     * {@code null} or has no non-empty text before a colon.
     */
    static String prefix(String curie) {
        if (curie == null) {
            return null;
        }
        int colon = curie.indexOf(':');
        return colon > 0 ? curie.substring(0, colon) : null;
    }

    /**
     * The cv_list of a dataset that uses the prefixes {@code used}, each mapped to a CURIE that uses it, with the
     * caller's entries {@code callerCvs}: MS and UO, then every used prefix of {@link #ALL}, in the order of
     * {@link #ALL}, each as the first entry of {@code callerCvs} with its id, else as the entry of {@link #ALL}; then
     * every used prefix that is not in {@link #ALL} but in {@code callerCvs}, as its first entry there, in the order
     * of {@code callerCvs}. An entry of {@code callerCvs} whose prefix is not used is not declared. A used prefix in
     * neither list is not declared; {@code report} gets one warning for it, which names the CURIE.
     *
     * <p>conformance.md requires the URI of every entry to identify a fixed release: a caller's entry of a CV of
     * {@link #ALL} is declared only if it has the URI and version of the entry of {@link #ALL} or
     * {@link #namesItsRelease names its release}; else the entry of {@link #ALL} is declared, with one warning
     * naming the CV and the caller's URI and version. The caller's entries of other CVs are those
     * {@link #requireFixedReleases} accepts.
     *
     * <p>Every declared entry has all four fields, as the spec's cv_list schema requires: a caller's entry of a CV of
     * {@link #ALL} that is declared takes the full name it lacks (null) from the entry of {@link #ALL}, with one
     * warning per CV naming the field; a caller's entry of another CV in use that lacks a field is not declared, with
     * one warning naming the CURIE and the fields. A caller's entry without an id is skipped with one warning.
     */
    static List<CvEntry> declare(Map<String, String> used, List<CvEntry> callerCvs, WriteReport report) {
        Map<String, CvEntry> caller = new LinkedHashMap<>();
        for (CvEntry cv : callerCvs) {
            if (cv.id() == null) {
                report.warn("a cv list entry without an id (" + cv + ") is not declared in metadata.cv_list");
            } else {
                caller.putIfAbsent(cv.id(), cv);
            }
        }
        List<CvEntry> declared = new ArrayList<>();
        for (CvEntry cv : ALL) {
            if (ALWAYS.contains(cv.id()) || used.containsKey(cv.id())) {
                CvEntry given = caller.get(cv.id());
                declared.add(given == null ? cv : released(given, cv, report));
            }
        }
        for (CvEntry cv : caller.values()) {
            if (used.containsKey(cv.id()) && !isKnown(cv.id()) && missing(cv).isEmpty()) {
                declared.add(cv);
            }
        }
        for (Map.Entry<String, String> e : used.entrySet()) {
            CvEntry given = caller.get(e.getKey());
            if (!isKnown(e.getKey()) && (given == null || !missing(given).isEmpty())) {
                report.warn("the CV prefix " + e.getKey() + " (used by " + e.getValue() + ") is not a controlled "
                        + "vocabulary mzPeakJ knows" + (given == null ? "" : ", and its cv list entry has no "
                        + String.join(", ", missing(given))) + "; its terms are written as given, but"
                        + " metadata.cv_list does not declare it");
            }
        }
        return declared;
    }

    /**
     * {@code given}, the caller's entry of the known CV {@code known}, with each null field taken from it, if it has
     * the URI and version of {@code known} or names its release; else {@code known}, with a warning.
     */
    private static CvEntry released(CvEntry given, CvEntry known, WriteReport report) {
        boolean same = Objects.equals(given.uri(), known.uri()) && Objects.equals(given.version(), known.version());
        if (same || namesItsRelease(given)) {
            return filled(given, known, report);
        }
        report.warn("the cv list entry of " + given.id() + " (URI " + given.uri() + ", version " + given.version()
                + ") does not show that it identifies a fixed release of " + given.id() + ", as conformance.md"
                + " requires of metadata.cv_list; metadata.cv_list declares mzPeakJ's entry of " + known.id()
                + " (URI " + known.uri() + ", version " + known.version() + ")");
        return known;
    }

    /**
     * {@code true} if the URI of {@code cv} shows that it identifies the release {@code cv} names: a URI on
     * {@code purl.obolibrary.org} or {@code www.ebi.ac.uk} with a path segment equal to the version or to {@code v}
     * and the version, or a {@code raw.githubusercontent.com} URI whose ref segment (the third) is a 40-hex commit
     * hash. {@code false} without a URI or version.
     */
    static boolean namesItsRelease(CvEntry cv) {
        if (cv.uri() == null || cv.version() == null) {
            return false;
        }
        String host = host(cv.uri());
        List<String> segments = segments(cv.uri());
        if (RELEASE_HOSTS.contains(host)) {
            return segments.contains(cv.version()) || segments.contains("v" + cv.version());
        }
        return host.equals("raw.githubusercontent.com") && segments.size() > 3
                && COMMIT.matcher(segments.get(2)).matches();
    }

    /**
     * Rejects a caller's entry of a CV {@link #ALL} lacks whose URI cannot identify a fixed release: one without a
     * version, or whose URI has a path segment {@code master}, {@code main}, {@code HEAD}, {@code trunk},
     * {@code heads}, {@code cvs}, {@code viewvc} or {@code svn} (without case), a host ending in
     * {@code .cvs.sourceforge.net} or {@code *checkout*} in it. Entries without an id or URI are left to
     * {@link #declare}.
     *
     * @throws IllegalArgumentException naming the entry's id, URI and version, and why
     */
    static void requireFixedReleases(List<CvEntry> cvs) {
        for (CvEntry cv : cvs) {
            String why = cv.id() == null || isKnown(cv.id()) ? null : movingRelease(cv);
            if (why != null) {
                throw new IllegalArgumentException("the cv list entry of " + cv.id() + " (URI " + cv.uri()
                        + ", version " + cv.version() + ") does not identify a fixed release of " + cv.id()
                        + ", as conformance.md requires of metadata.cv_list: " + why);
            }
        }
    }

    /** Why the entry {@code cv} of an unknown CV cannot identify a fixed release, or {@code null}. */
    private static String movingRelease(CvEntry cv) {
        if (cv.version() == null) {
            return "it has no version";
        }
        String uri = cv.uri();
        if (uri == null) {
            return null;
        }
        if (uri.contains("*checkout*")) {
            return "its URI is a CVS checkout";
        }
        if (host(uri).endsWith(".cvs.sourceforge.net")) {
            return "its URI is on a SourceForge CVS host";
        }
        for (String segment : segments(uri)) {
            if (MOVING_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                return "its URI has the path segment " + segment + ", which names a moving branch or a"
                        + " version-control view";
            }
        }
        return null;
    }

    /** The host of {@code uri} in lower case, without user info and port; empty if it has no {@code ://}. */
    private static String host(String uri) {
        int scheme = uri.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        String authority = uri.substring(scheme + 3).split("[/?#]", 2)[0];
        String host = authority.substring(authority.lastIndexOf('@') + 1);
        int port = host.indexOf(':');
        return (port < 0 ? host : host.substring(0, port)).toLowerCase(Locale.ROOT);
    }

    /** The path segments of {@code uri}, without its authority, query and fragment; all of it without {@code ://}. */
    private static List<String> segments(String uri) {
        int scheme = uri.indexOf("://");
        String rest = scheme < 0 ? uri : uri.substring(scheme + 3);
        List<String> segments = List.of(rest.split("[?#]", 2)[0].split("/", -1));
        return scheme < 0 ? segments : segments.subList(1, segments.size());
    }

    /** {@code given}, the caller's entry of the known CV {@code known}, with each null field taken from it. */
    private static CvEntry filled(CvEntry given, CvEntry known, WriteReport report) {
        List<String> missing = missing(given);
        if (missing.isEmpty()) {
            return given;
        }
        report.warn("the cv list entry of " + given.id() + " has no " + String.join(", ", missing)
                + "; metadata.cv_list fills in the missing fields from mzPeakJ's entry of " + known.id());
        return new CvEntry(given.id(), Objects.requireNonNullElse(given.fullName(), known.fullName()),
                Objects.requireNonNullElse(given.uri(), known.uri()),
                Objects.requireNonNullElse(given.version(), known.version()));
    }

    /** The cv_list fields {@code cv} lacks (null), by their JSON names: full_name, uri, version. */
    private static List<String> missing(CvEntry cv) {
        List<String> missing = new ArrayList<>();
        if (cv.fullName() == null) {
            missing.add("full_name");
        }
        if (cv.uri() == null) {
            missing.add("uri");
        }
        if (cv.version() == null) {
            missing.add("version");
        }
        return missing;
    }

    /** {@code true} if {@code id} is the id of an entry of {@link #ALL}. */
    private static boolean isKnown(String id) {
        return ALL.stream().anyMatch(cv -> cv.id().equals(id));
    }
}
