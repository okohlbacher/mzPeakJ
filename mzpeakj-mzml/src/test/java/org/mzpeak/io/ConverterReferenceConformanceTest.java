package org.mzpeak.io;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mzpeak.mzml.MzMLConversion;
import org.mzpeak.mzml.MzMLFixtures;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WR-9 (D-17), case (e): the datasets the converter writes from the mzML fixtures tiny.pwiz.1.1 and small carry file
 * metadata documents, in the manifest and in every member's footer, that pass the specification's schemas and whose
 * references all resolve ({@link SpecSchemaCheck}, in this package of the core library's tests).
 */
class ConverterReferenceConformanceTest {

    @ParameterizedTest
    @ValueSource(strings = {MzMLFixtures.TINY, MzMLFixtures.SMALL})
    void convertedFileMetadataConformsAndResolves(String name, @TempDir Path dir) throws IOException {
        Path mzml = MzMLFixtures.extract(name, dir);
        Path out = dir.resolve("out.mzpeak");
        MzMLConversion.toMzPeak(mzml, out, true, MzPeakWriter.Options.defaults());

        Map<String, Map<String, String>> places = SpecSchemaCheck.documents(out);
        assertThat(places).containsKey("mzpeak_index.json");
        List<String> problems = new ArrayList<>();
        places.forEach((place, documents) -> {
            assertThat(documents).as(place).containsKeys("run", "software_list", "instrument_configuration_list",
                    "data_processing_method_list", "file_description");
            SpecSchemaCheck.violations(documents).forEach(v -> problems.add(place + ": " + v));
            SpecSchemaCheck.unresolved(documents).forEach(u -> problems.add(place + ": " + u));
        });
        assertThat(problems).isEmpty();
    }
}
