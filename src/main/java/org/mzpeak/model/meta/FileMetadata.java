package org.mzpeak.model.meta;

import org.mzpeak.model.Param;

import java.util.List;
import java.util.Optional;

/**
 * File/run-level metadata: the documents {@code file_description}, {@code instrument_configuration_list},
 * {@code software_list}, {@code data_processing_method_list}, {@code sample_list} and {@code run}. A reader takes each
 * document from the {@code metadata} object of the manifest ({@code mzpeak_index.json}) first, and from the JSON of
 * the same key in the Parquet footer key-value metadata of {@code spectra_metadata.parquet} when the manifest lacks
 * it; the {@code run} is merged field by field, the manifest's fields first.
 *
 * <p>The model mirrors the mzPeak schema / the Rust {@code mzpeak_prototyping} types: every object is a few
 * typed identity/reference fields plus a {@link Param} list carrying the controlled-vocabulary detail
 * (analyzer type, resolution, software name, etc. are all CV params, resolved by accession).
 *
 * <p>The params of these documents are stored as JSON, which has no non-finite numbers: a {@code Double} that is
 * infinite or NaN is written as the string {@code Infinity}, {@code -Infinity} or {@code NaN} and reads back as that
 * {@code String}.
 */
public record FileMetadata(FileDescription fileDescription,
                           List<InstrumentConfiguration> instrumentConfigurations,
                           List<DataProcessing> dataProcessingMethods,
                           List<Sample> samples,
                           List<Software> software,
                           MsRun run) {

    public static final FileMetadata EMPTY =
            new FileMetadata(null, List.of(), List.of(), List.of(), List.of(), null);


    public record SourceFile(String id, String name, String location, List<Param> parameters) {
    }

    /**
     * A contact person of the file, from {@code file_description.contacts}: the name ({@code MS:1000586} contact
     * name), the affiliation ({@code MS:1000590} contact affiliation), each {@code null} if not recorded, and the
     * other params, such as address, URL and email.
     */
    public record Contact(String name, String affiliation, List<Param> parameters) {

        /** Copies the list; a {@code null} list becomes an empty one. */
        public Contact {
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }

    /** The {@code file_description}: the file content params, the source files and the contacts. */
    public record FileDescription(List<Param> contents, List<SourceFile> sourceFiles, List<Contact> contacts) {

        /** Copies the contacts; a {@code null} list of them becomes an empty one. */
        public FileDescription {
            contacts = contacts == null ? List.of() : List.copyOf(contacts);
        }

        /** A file description without contacts. */
        public FileDescription(List<Param> contents, List<SourceFile> sourceFiles) {
            this(contents, sourceFiles, List.of());
        }
    }

    public enum ComponentType {
        ION_SOURCE, ANALYZER, DETECTOR, UNKNOWN;

        public static ComponentType parse(String s) {
            if (s == null) {
                return UNKNOWN;
            }
            return switch (s.toLowerCase()) {
                case "ionsource", "ion_source", "source" -> ION_SOURCE;
                case "analyzer", "mass_analyzer" -> ANALYZER;
                case "detector" -> DETECTOR;
                default -> UNKNOWN;
            };
        }
    }

    public record Component(ComponentType componentType, int order, List<Param> parameters) {
    }

    public record InstrumentConfiguration(int id, List<Component> components, String softwareReference,
                                          List<Param> parameters) {
    }

    public record Software(String id, String version, List<Param> parameters) {
        /** Best-effort human name: the first named CV param, else the id. */
        public String displayName() {
            for (Param p : parameters) {
                if (p.name() != null && !p.name().isBlank()) {
                    return p.name();
                }
            }
            return id;
        }
    }

    public record ProcessingMethod(int order, String softwareReference, List<Param> parameters) {
    }

    public record DataProcessing(String id, List<ProcessingMethod> methods) {
    }

    public record Sample(String id, String name, List<Param> parameters) {
    }

    public record MsRun(String id, Integer defaultInstrumentId, String defaultDataProcessingId,
                        String defaultSourceFileId, String startTime, List<Param> parameters) {
    }

    /** First parameter with the given accession in a list. */
    public static Optional<Param> param(List<Param> params, String accession) {
        if (params != null) {
            for (Param p : params) {
                if (p.hasAccession(accession)) {
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }
}
