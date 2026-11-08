package org.mzpeak.io;

import org.mzpeak.io.MzPeakManifest.CvEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The controlled vocabularies {@link ManifestWriter} declares in {@code metadata.cv_list}: a verbatim copy of the
 * reference's {@code From<ControlledVocabulary> for ControlledVocabularyEntry} (param.rs:272-336 at 2ee2589), in its
 * order. Every URI names a fixed release, as conformance.md requires of a writer, except that of IMS, which is the
 * imzML repository's master branch in the reference too. Public as {@link CvEntry#known()}; a caller's cv list
 * ({@link MzPeakWriter.Options#withCvList}) takes precedence.
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
     */
    static List<CvEntry> declare(Map<String, String> used, List<CvEntry> callerCvs, WriteReport report) {
        Map<String, CvEntry> caller = new LinkedHashMap<>();
        for (CvEntry cv : callerCvs) {
            caller.putIfAbsent(cv.id(), cv);
        }
        List<CvEntry> declared = new ArrayList<>();
        for (CvEntry cv : ALL) {
            if (ALWAYS.contains(cv.id()) || used.containsKey(cv.id())) {
                declared.add(caller.getOrDefault(cv.id(), cv));
            }
        }
        for (CvEntry cv : caller.values()) {
            if (used.containsKey(cv.id()) && !isKnown(cv.id())) {
                declared.add(cv);
            }
        }
        for (Map.Entry<String, String> e : used.entrySet()) {
            if (!isKnown(e.getKey()) && !caller.containsKey(e.getKey())) {
                report.warn("the CV prefix " + e.getKey() + " (used by " + e.getValue() + ") is not a controlled "
                        + "vocabulary mzPeakJ knows; its terms are written as given, but metadata.cv_list does not "
                        + "declare it");
            }
        }
        return declared;
    }

    /** {@code true} if {@code id} is the id of an entry of {@link #ALL}. */
    private static boolean isKnown(String id) {
        return ALL.stream().anyMatch(cv -> cv.id().equals(id));
    }
}
