package org.mzpeak.io;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mzpeak.io.MzPeakManifest.ColumnMapping;
import org.mzpeak.io.MzPeakManifest.CvEntry;
import org.mzpeak.io.MzPeakManifest.DataKind;
import org.mzpeak.io.MzPeakManifest.EntityType;
import org.mzpeak.io.WritePlan.Entry;
import org.mzpeak.io.WritePlan.ExtraColumn;
import org.mzpeak.io.WritePlan.IntensityColumn;
import org.mzpeak.io.WritePlan.SpectrumEntry;
import org.mzpeak.model.Chromatogram;
import org.mzpeak.model.Param;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.ScanEvent;
import org.mzpeak.model.SelectedIon;
import org.mzpeak.model.SpectrumDescription;
import org.mzpeak.model.WavelengthSpectrum;
import org.mzpeak.model.meta.ScanSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the {@code mzpeak_index.json} manifest of a dataset written by {@link MzPeakWriter}: the member list,
 * the CV list, the scan settings and the file-level metadata. Every member entry carries its role in the
 * {@link Dialect}'s spelling, its {@code column_mapping}, an empty {@code parameters} list and the SHA-512
 * {@code checksum} of the member's bytes. The CV list declares every CV prefix the dataset uses that
 * {@link KnownCvs} knows or the caller's cv list ({@link MzPeakWriter.Options#cvList()}) holds.
 */
final class ManifestWriter {

    /** The name of the manifest at the dataset root. */
    static final String INDEX = "mzpeak_index.json";

    /** The manifest keys whose values are CURIEs: the accessions and units of params and column mappings. */
    private static final List<String> CURIE_KEYS = List.of("accession", "unit", "unit_accession");

    /**
     * Jackson's default pretty printer with {@code "\n"} as the line feed of its object indenter, which by default
     * follows the {@code line.separator} property; the array indenter is a single space and has no line feed. So
     * the manifest bytes are the same on every platform.
     */
    private static final DefaultPrettyPrinter PRETTY_PRINTER = new DefaultPrettyPrinter()
            .withObjectIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE.withLinefeed("\n"));

    /**
     * How the manifest spells the roles of its members and the scan settings (design §11.4). Both dialects write the
     * same member keys; the column mappings come with the members.
     */
    enum Dialect {
        /**
         * The spellings of the 29e59b2 pin, {@code data arrays} and {@code wavelength spectrum}: the pin-era
         * reference deserialises only these, and mzPeakJ 0.10.3 and earlier match roles by exact string. A scan
         * settings param names its unit {@code unit_accession}, and absent values are left out, as are a scan
         * settings entry's absent id and empty {@code source_file_refs} and {@code targets}. Written with the packed
         * metadata layout.
         */
        PIN_29E59B2,
        /**
         * The current spec's spellings, {@link EntityType#canonical()} and {@link DataKind#canonical()}. Scan settings
         * are written as the reference serialises them: every entry with its {@code id}, {@code source_file_refs},
         * {@code targets} and {@code parameters}, and every param with {@code name}, {@code accession},
         * {@code value} and {@code unit}, absent values as {@code null}. Written with the split metadata layout.
         */
        CURRENT;

        /** The {@code entity_type} string of {@code entity} in this dialect. */
        String entityType(EntityType entity) {
            return this == PIN_29E59B2 && entity == EntityType.WAVELENGTH_SPECTRUM
                    ? "wavelength spectrum" : entity.canonical();
        }

        /** The {@code data_kind} string of {@code kind} in this dialect. */
        String dataKind(DataKind kind) {
            return this == PIN_29E59B2 && kind == DataKind.DATA_ARRAYS ? "data arrays" : kind.canonical();
        }
    }

    private ManifestWriter() {
    }

    /**
     * Write {@link #INDEX} into {@code directory}: the {@code members} in the given order, each of which must already
     * be written and closed in {@code directory} because its checksum is computed from its file here, then the CV
     * list, the plan's scan settings and file metadata (the footer key-values).
     *
     * <p>The CV list holds MS and UO, then every other CV of {@link KnownCvs} that a written CURIE uses (design
     * §11.6): a CURIE of the plan's spectra, chromatograms and wavelength spectra ({@link #prefixesOf}) or one of
     * this manifest, that is of a column mapping, a scan-settings param or a file-metadata document; then every other
     * CV in use that the caller's cv list of the plan's options holds, in that list's order. A CV the caller's list
     * holds is declared with the list's entry ({@link KnownCvs#declare}). A prefix neither {@link KnownCvs} nor the
     * caller's list knows is not declared and gets one warning in the plan's report.
     */
    static void writeManifest(Path directory, List<WrittenMember> members, WritePlan plan, Dialect dialect)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ArrayNode files = root.putArray("files");
        for (WrittenMember member : members) {
            addManifestEntry(files, member, dialect, Sha512.hex(directory.resolve(member.name())));
        }
        ObjectNode meta = root.putObject("metadata");
        // Match the reference writer: the spec example declares version 0.9.0.
        meta.put("version", "0.9.0");
        // Filled once the rest of the manifest is built, so that the manifest's own CURIEs count.
        ArrayNode cvList = meta.putArray("cv_list");
        writeScanSettingsList(meta.putArray("scan_settings_list"), plan.scanSettings(), dialect);
        for (var kv : plan.footer().entrySet()) {
            meta.set(kv.getKey(), mapper.readTree(kv.getValue()));
        }
        Map<String, String> used = prefixesOf(plan);
        addJsonPrefixes(root, used);
        for (CvEntry cv : KnownCvs.declare(used, plan.options().cvList(), plan.report())) {
            addCvEntry(cvList, cv);
        }
        Files.write(directory.resolve(INDEX), mapper.writer(PRETTY_PRINTER).writeValueAsBytes(root));
    }

    /**
     * Serialize imaging scan settings, with their source file references and targets, into the manifest in
     * {@code dialect}'s form, matching {@code MzPeakManifest.parseScanSettings}, which reads either.
     */
    private static void writeScanSettingsList(ArrayNode arr, List<ScanSettings> scanSettings, Dialect dialect) {
        if (scanSettings == null) {
            return;
        }
        boolean current = dialect == Dialect.CURRENT;
        for (ScanSettings ss : scanSettings) {
            ObjectNode n = arr.addObject();
            // The reference's ScanSettings has no serde defaults: it needs all four keys to read the entry. The pin
            // dialect leaves out an absent id and an empty list.
            if (current || ss.id() != null) {
                n.put("id", ss.id());
            }
            if (current || !ss.sourceFileRefs().isEmpty()) {
                ArrayNode refs = n.putArray("source_file_refs");
                for (String ref : ss.sourceFileRefs()) {
                    refs.add(ref);
                }
            }
            if (current || !ss.targets().isEmpty()) {
                ArrayNode targets = n.putArray("targets");
                for (List<Param> target : ss.targets()) {
                    writeScanSettingsParams(targets.addArray(), target, current);
                }
            }
            writeScanSettingsParams(n.putArray("parameters"), ss.parameters(), current);
        }
    }

    /** Adds {@code params} to {@code arr} in the form of the current dialect or, if not {@code current}, the pin's. */
    private static void writeScanSettingsParams(ArrayNode arr, List<Param> params, boolean current) {
        for (Param p : params) {
            ObjectNode pn = arr.addObject();
            if (current) {
                pn.put("name", p.name());
                pn.put("accession", p.accession());
                pn.putNull("value");
            } else {
                if (p.name() != null) pn.put("name", p.name());
                if (p.accession() != null) pn.put("accession", p.accession());
            }
            Object v = p.value();
            if (v instanceof Long l) pn.put("value", l);
            else if (v instanceof Integer i) pn.put("value", i.longValue());
            else if (v instanceof Double dd) pn.put("value", dd);
            else if (v instanceof Boolean b) pn.put("value", b);
            else if (v != null) pn.put("value", v.toString());
            if (current) {
                pn.put("unit", p.unit());
            } else if (p.unit() != null) {
                pn.put("unit_accession", p.unit());
            }
        }
    }

    /**
     * One {@code files} entry, with its keys in the reference's order. A column mapping item is written as the
     * reference serialises it: {@code path} as a dotted string, a missing accession or unit as {@code null}, a
     * {@link ColumnMapping#unitPath() unit path} as the JSON array of its segments (a Rust {@code PathOrCURIE} path),
     * and {@code term_marker} only when it is set.
     */
    static void addManifestEntry(ArrayNode files, WrittenMember member, Dialect dialect, String checksum) {
        ObjectNode e = files.addObject();
        e.put("name", member.name());
        e.put("entity_type", dialect.entityType(member.entity()));
        e.put("data_kind", dialect.dataKind(member.kind()));
        ArrayNode mapping = e.putArray("column_mapping");
        for (ColumnMapping c : member.columnMapping()) {
            ObjectNode m = mapping.addObject();
            m.put("name", c.name());
            m.put("path", c.path());
            m.put("accession", c.accession());
            if (c.unitPath() != null) {
                ArrayNode unitPath = m.putArray("unit");
                for (String segment : c.unitPath().split("\\.")) {
                    unitPath.add(segment);
                }
            } else {
                m.put("unit", c.unit());
            }
            if (Boolean.TRUE.equals(c.termMarker())) {
                m.put("term_marker", true);
            }
        }
        e.putArray("parameters");
        e.put("checksum", checksum);
    }

    /**
     * Every CURIE prefix of the plan's spectra, chromatograms and wavelength spectra, mapped to the first CURIE that
     * uses it: the accession and unit of every param of a spectrum, of each of its scans, and of each precursor's
     * isolation window, activation and selected ions; the array type and unit of every extra column of the spectrum
     * signal files, that is every CURIE key of the written spectra's {@code arrays()} and {@code peakArrays()} (a key
     * that is not a CURIE is written as MS:1000786); the chromatogram types, the accession and unit of every param
     * of a chromatogram, and of each of its precursors' isolation window, activation and selected ions; the time unit
     * of the chromatograms; the unit of every intensity column of chromatograms_data, that is every intensity unit
     * of a chromatogram; the array type and unit of every extra column of chromatograms_data, that is every CURIE
     * key of the chromatograms' {@code arrays()}; and the accession and unit of every param of a wavelength
     * spectrum. The packed layout writes
     * only a spectrum's first scan and primary precursor and selected ion, and no chromatogram precursor or
     * chromatogram or wavelength-spectrum param, but the prefixes of the others count too. The other CURIEs the
     * writer puts into a member are MS and UO terms, and the IMS position columns, which only a scan's IMS params give
     * rise to.
     */
    private static Map<String, String> prefixesOf(WritePlan plan) {
        Map<String, String> used = new LinkedHashMap<>();
        for (SpectrumEntry entry : plan.spectra()) {
            SpectrumDescription d = entry.description();
            addParamPrefixes(d.parameters(), used);
            for (ScanEvent scan : d.scans()) {
                addParamPrefixes(scan.parameters(), used);
            }
            addPrecursorPrefixes(d.precursors(), used);
        }
        for (ExtraColumn extra : plan.spectrumExtras()) {
            addPrefix(extra.arrayType(), used);
            addPrefix(extra.unit(), used);
        }
        for (Entry<Chromatogram> entry : plan.chromatograms()) {
            addPrefix(entry.source().typeCurie(), used);
            addParamPrefixes(entry.source().parameters(), used);
            addPrecursorPrefixes(entry.source().precursors(), used);
        }
        if (plan.writesChromatograms()) {
            addPrefix(plan.chromatogramTimeUnit(), used);
        }
        for (IntensityColumn intensity : plan.chromatogramIntensities()) {
            addPrefix(intensity.unit(), used);
        }
        for (ExtraColumn extra : plan.chromatogramExtras()) {
            addPrefix(extra.arrayType(), used);
            addPrefix(extra.unit(), used);
        }
        for (Entry<WavelengthSpectrum> entry : plan.wavelengthSpectra()) {
            addParamPrefixes(entry.source().parameters(), used);
        }
        return used;
    }

    /** Adds the prefixes of the params of each precursor's isolation window, activation and selected ions. */
    private static void addPrecursorPrefixes(List<Precursor> precursors, Map<String, String> used) {
        for (Precursor precursor : precursors) {
            if (precursor.isolationWindow() != null) {
                addParamPrefixes(precursor.isolationWindow().parameters(), used);
            }
            addParamPrefixes(precursor.activation().parameters(), used);
            for (SelectedIon ion : precursor.selectedIons()) {
                addParamPrefixes(ion.parameters(), used);
            }
        }
    }

    private static void addParamPrefixes(List<Param> params, Map<String, String> used) {
        for (Param p : params) {
            addPrefix(p.accession(), used);
            addPrefix(p.unit(), used);
        }
    }

    /** Adds the prefix of every {@link #CURIE_KEYS} value in {@code node}, at any depth. */
    private static void addJsonPrefixes(JsonNode node, Map<String, String> used) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (CURIE_KEYS.contains(field.getKey()) && field.getValue().isTextual()) {
                    addPrefix(field.getValue().textValue(), used);
                } else {
                    addJsonPrefixes(field.getValue(), used);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                addJsonPrefixes(child, used);
            }
        }
    }

    private static void addPrefix(String curie, Map<String, String> used) {
        String prefix = KnownCvs.prefix(curie);
        if (prefix != null) {
            used.putIfAbsent(prefix, curie);
        }
    }

    private static void addCvEntry(ArrayNode cvList, CvEntry entry) {
        ObjectNode cv = cvList.addObject();
        cv.put("id", entry.id());
        cv.put("full_name", entry.fullName());
        cv.put("uri", entry.uri());
        cv.put("version", entry.version());
    }
}
