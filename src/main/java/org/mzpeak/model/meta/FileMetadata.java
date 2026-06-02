package org.mzpeak.model.meta;

import java.util.List;
import java.util.Optional;

/**
 * File/run-level metadata stored as JSON in the Parquet footer key-value metadata of
 * {@code spectra_metadata.parquet} (keys {@code file_description}, {@code instrument_configuration_list},
 * {@code software_list}, {@code data_processing_method_list}, {@code sample_list}, {@code run}).
 *
 * <p>The model mirrors the mzPeak schema / the Rust {@code mzpeak_prototyping} types: every object is a few
 * typed identity/reference fields plus a {@link Param} list carrying the controlled-vocabulary detail
 * (analyzer type, resolution, software name, etc. are all CV params, resolved by accession).
 */
public record FileMetadata(FileDescription fileDescription,
                           List<InstrumentConfiguration> instrumentConfigurations,
                           List<DataProcessing> dataProcessingMethods,
                           List<Sample> samples,
                           List<Software> software,
                           MsRun run) {

    public static final FileMetadata EMPTY =
            new FileMetadata(null, List.of(), List.of(), List.of(), List.of(), null);

    /** A controlled-vocabulary or user parameter: {@code name}, optional {@code accession} CURIE + {@code unit}. */
    public record Param(String name, String accession, Object value, String unit) {
        public boolean hasAccession(String acc) {
            return acc.equals(accession);
        }
    }

    public record SourceFile(String id, String name, String location, List<Param> parameters) {
    }

    public record FileDescription(List<Param> contents, List<SourceFile> sourceFiles) {
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
