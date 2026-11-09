package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.CvEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The controlled vocabularies {@link ManifestWriter} declares in {@code metadata.cv_list}: a verbatim copy of the
 * reference's {@code From<ControlledVocabulary> for ControlledVocabularyEntry} (param.rs:272-336 at 2ee2589), in its
 * order. Every URI names a fixed release, as conformance.md requires of a writer, except that of IMS, which is the
 * imzML repository's master branch in the reference too. Public as {@link CvEntry#known()}; a caller's cv list
 * ({@link MzPeakWriter.Options#withCvList}) takes precedence, field by field.
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
     * <p>Every declared entry has all four fields, as the spec's cv_list schema requires: a caller's entry of a CV of
     * {@link #ALL} takes each field it lacks (null) from the entry of {@link #ALL}, with one warning per CV naming
     * the fields; a caller's entry of another CV in use that lacks a field is not declared, with one warning naming
     * the CURIE and the fields. A caller's entry without an id is skipped with one warning.
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
                declared.add(given == null ? cv : filled(given, cv, report));
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
