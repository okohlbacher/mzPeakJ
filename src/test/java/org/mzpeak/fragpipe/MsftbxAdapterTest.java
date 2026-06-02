package org.mzpeak.fragpipe;

import org.junit.jupiter.api.Test;
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;
import umich.ms.datatypes.scan.IScan;
import umich.ms.datatypes.scan.props.PrecursorInfo;
import umich.ms.datatypes.spectrum.ISpectrum;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Round-trips mzPeakJ spectra into MSFTBX (FragPipe) IScan/ISpectrum. */
class MsftbxAdapterTest {

    private static final Path FIXTURE = Path.of("src/test/resources/mzpeak/small.unpacked.mzpeak");

    @Test
    void convertsMs1ProfileToIScan() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            Spectrum s = reader.getSpectrum(0).orElseThrow();
            IScan scan = MsftbxAdapter.toScan(s);

            assertThat(scan.getNum()).isEqualTo(1); // vendor scan number parsed from nativeID "scan=1"
            assertThat(scan.getMsLevel()).isEqualTo(1);
            assertThat(scan.getRt()).isCloseTo(0.004935, within(1e-5));
            assertThat(scan.getPrecursor()).isNull();

            ISpectrum spectrum = scan.getSpectrum();
            assertThat(spectrum.getMZs()).hasSize(13589);
            assertThat(spectrum.getIntensities()).hasSize(13589);
            // zero-copy: the adapter passes mzPeakJ's arrays straight through
            assertThat(spectrum.getMZs()).isSameAs(s.mz());
        }
    }

    @Test
    void convertsMs2WithPrecursor() {
        try (MzPeakReader reader = MzPeakReader.open(FIXTURE)) {
            Spectrum s = reader.getSpectrum(2).orElseThrow();
            IScan scan = MsftbxAdapter.toScan(s);

            assertThat(scan.getMsLevel()).isEqualTo(2);
            assertThat(scan.getNum()).isEqualTo(3); // nativeID "scan=3"

            PrecursorInfo precursor = scan.getPrecursor();
            assertThat(precursor).isNotNull();
            assertThat(precursor.getMzTarget()).isCloseTo(810.789428710938, within(1e-6));

            assertThat(scan.getSpectrum().getMZs()).hasSize(485);
        }
    }
}
