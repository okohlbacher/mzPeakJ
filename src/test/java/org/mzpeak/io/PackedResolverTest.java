package org.mzpeak.io;

import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packed layout read through {@link ColumnResolver#packed}: on every legacy fixture (HUPO-PSI/mzPeak 29e59b2),
 * the accession of each column name that the mzPeakJ 0.10.3 spectrum row decoders hard-code resolves to that name,
 * so the decoders can resolve by accession and leave the pin-era names to {@link ColumnTable}. Members are found by
 * role.
 */
class PackedResolverTest {

    /** A pin-era leaf name: a CURIE with ':' replaced by '_', then '_' and the term name. */
    private static final Pattern PIN_ERA_NAME = Pattern.compile("^(MS|IMS)_(\\d{7})_");

    /**
     * A column the 0.10.3 row decoders read by name.
     *
     * @param facet     the facet of the packed struct
     * @param scope     the struct or list of structs below the facet's packed struct, or {@code null} for the struct
     * @param accession the accession the decoders now resolve
     * @param name      the 0.10.3 constant
     */
    private record Constant(Facet facet, String scope, String accession, String name) {
    }

    private static final List<Constant> DECODER_CONSTANTS = List.of(
            new Constant(Facet.SPECTRUM, null, "MS:1000511", "MS_1000511_ms_level"),
            new Constant(Facet.SPECTRUM, null, "MS:1000465", "MS_1000465_scan_polarity"),
            new Constant(Facet.SPECTRUM, null, "MS:1000525", "MS_1000525_spectrum_representation"),
            new Constant(Facet.SPECTRUM, null, "MS:1003060", "MS_1003060_number_of_data_points"),
            new Constant(Facet.SPECTRUM, null, "MS:1003059", "MS_1003059_number_of_peaks"),
            new Constant(Facet.SPECTRUM_SCAN, null, "MS:1000016", "MS_1000016_scan_start_time_unit_UO_0000031"),
            new Constant(Facet.SPECTRUM_SCAN, null, "MS:1000927", "MS_1000927_ion_injection_time_unit_UO_0000028"),
            new Constant(Facet.SPECTRUM_SCAN, null, "MS:1000512", "MS_1000512_filter_string"),
            new Constant(Facet.SPECTRUM_SCAN, null, "MS:1000616", "MS_1000616_preset_scan_configuration"),
            new Constant(Facet.SPECTRUM_SCAN, "scan_windows", "MS:1000501",
                    "MS_1000501_scan_window_lower_limit_unit_MS_1000040"),
            new Constant(Facet.SPECTRUM_SCAN, "scan_windows", "MS:1000500",
                    "MS_1000500_scan_window_upper_limit_unit_MS_1000040"),
            new Constant(Facet.SPECTRUM_PRECURSOR, "isolation_window", "MS:1000827",
                    "MS_1000827_isolation_window_target_mz"),
            new Constant(Facet.SPECTRUM_PRECURSOR, "isolation_window", "MS:1000828",
                    "MS_1000828_isolation_window_lower_offset"),
            new Constant(Facet.SPECTRUM_PRECURSOR, "isolation_window", "MS:1000829",
                    "MS_1000829_isolation_window_upper_offset"),
            new Constant(Facet.SPECTRUM_SELECTED_ION, null, "MS:1000744",
                    "MS_1000744_selected_ion_mz_unit_MS_1000040"),
            new Constant(Facet.SPECTRUM_SELECTED_ION, null, "MS:1000041", "MS_1000041_charge_state"),
            new Constant(Facet.SPECTRUM_SELECTED_ION, null, "MS:1000042", "MS_1000042_intensity_unit_MS_1000131"));

    /** A struct, or list of structs, {@code scope} below the packed struct of {@code facet} ({@code null}: itself). */
    private record Scope(Facet facet, String scope) {
    }

    /** The scopes of the packed spectrum metadata that hold CV-named leaves. */
    private static final List<Scope> SCOPES = List.of(
            new Scope(Facet.SPECTRUM, null),
            new Scope(Facet.SPECTRUM_SCAN, null),
            new Scope(Facet.SPECTRUM_SCAN, "scan_windows"),
            new Scope(Facet.SPECTRUM_PRECURSOR, null),
            new Scope(Facet.SPECTRUM_PRECURSOR, "isolation_window"),
            new Scope(Facet.SPECTRUM_SELECTED_ION, null));

    static Stream<String> legacyFixtures() {
        return Fixtures.LEGACY_NAMES.stream();
    }

    // ---- legacy fixtures -------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFixtures")
    void eachDecoderAccessionResolvesToThe0103ConstantName(String fixture) {
        MessageType schema = legacySchema(fixture);
        for (Constant c : DECODER_CONSTANTS) {
            assertThat(resolver(schema, c.facet(), c.scope()).resolve(c.accession()))
                    .as("%s %s in %s", c.facet(), c.accession(), fixture).isEqualTo(c.name());
        }

        ColumnResolver spectrum = resolver(schema, Facet.SPECTRUM, null);
        assertThat(spectrum.resolve("MS:1003821")).isNull();
        assertThat(spectrum.resolve("MS:1003820", "coordinate_spacing_model", "mz_delta_model"))
                .isEqualTo("mz_delta_model");
        assertThat(spectrum.resolveName("median_delta")).isNull();

        ColumnResolver scan = resolver(schema, Facet.SPECTRUM_SCAN, null);
        assertThat(scan.resolveName("instrument_configuration_id", "instrument_configuration_ref"))
                .isEqualTo("instrument_configuration_ref");
        assertThat(scan.resolveName("ion_mobility_value", "ion_mobility_type")).isEqualTo("ion_mobility_value");
        // the 29e59b2 examples have no imaging positions
        assertThat(scan.resolve("IMS:1000050")).isNull();
        assertThat(scan.resolve("IMS:1000051")).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("legacyFixtures")
    void everyPinEraLeafResolvesBackToItselfByItsAccession(String fixture) {
        MessageType schema = legacySchema(fixture);
        List<String> resolved = new ArrayList<>();
        for (Scope scope : SCOPES) {
            ColumnResolver columns = resolver(schema, scope.facet(), scope.scope());
            for (Type field : scopeType(schema, scope.facet(), scope.scope()).getFields()) {
                Matcher m = PIN_ERA_NAME.matcher(field.getName());
                if (!field.isPrimitive() || !m.find()) {
                    continue;
                }
                String accession = m.group(1) + ":" + m.group(2);
                assertThat(columns.resolve(accession)).as("%s in %s", field.getName(), fixture)
                        .isEqualTo(field.getName());
                resolved.add(accession);
            }
        }
        // 11 spectrum, 4 scan, 2 scan window, 3 isolation window and 3 selected-ion leaves
        assertThat(resolved).hasSize(23).doesNotHaveDuplicates();
    }

    /** The scan struct the 0.10.3 writer emits adds the two imaging position columns to the fixture's. */
    @Test
    void theImagingPositionColumnsOfThe0103WriterResolve() {
        GroupType fixtureScan = legacySchema("small.unpacked.mzpeak").getType("scan").asGroupType();
        List<Type> fields = new ArrayList<>(fixtureScan.getFields());
        fields.add(Types.optional(PrimitiveTypeName.INT32).named("IMS_1000050_position_x"));
        fields.add(Types.optional(PrimitiveTypeName.INT32).named("IMS_1000051_position_y"));
        ColumnResolver scan = ColumnResolver.packed(new GroupType(Type.Repetition.OPTIONAL, "scan", fields),
                Facet.SPECTRUM_SCAN);

        assertThat(scan.resolve("IMS:1000050")).isEqualTo("IMS_1000050_position_x");
        assertThat(scan.resolve("IMS:1000051")).isEqualTo("IMS_1000051_position_y");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** The schema of the spectrum metadata member of the legacy {@code fixture}, found by role. */
    private static MessageType legacySchema(String fixture) {
        try (MzPeakSource src = MzPeakSource.open(Fixtures.legacy(fixture))) {
            MzPeakManifest.Entry entry = Facet.SPECTRUM.entry(MzPeakManifest.fromSource(src)).orElseThrow();
            return ParquetFooter.read(src.inputFile(entry.name())).schema();
        }
    }

    private static ColumnResolver resolver(MessageType schema, Facet facet, String scope) {
        ColumnResolver columns = ColumnResolver.packed(schema.getType(facet.packedStruct()).asGroupType(), facet);
        return scope == null ? columns : columns.child(scope);
    }

    /** The struct, or list element struct, that {@link #resolver} resolves in. */
    private static GroupType scopeType(MessageType schema, Facet facet, String scope) {
        GroupType struct = schema.getType(facet.packedStruct()).asGroupType();
        if (scope == null) {
            return struct;
        }
        GroupType child = struct.getType(scope).asGroupType();
        if (child.getLogicalTypeAnnotation() instanceof LogicalTypeAnnotation.ListLogicalTypeAnnotation) {
            // list -> repeated group list -> item
            return child.getType(0).asGroupType().getType(0).asGroupType();
        }
        return child;
    }
}
