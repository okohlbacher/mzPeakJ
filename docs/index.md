# mzPeakJ

A pure-JVM **Java reader and writer** for the [HUPO-PSI mzPeak](https://www.psidev.info/mzpeak)
mass-spectrometry data format (built on Apache Parquet).

> **Demonstrator / proof-of-concept** — vibe-coded by following the existing mzPeak reference
> implementations (the Rust crates [`mzpeaks`](https://github.com/mobiusklein/mzpeaks) / `mzdata` /
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping) and the HUPO-PSI spec + example
> files). Built for exploration, not production. mzPeak is an unstable prototype format.

[View on GitHub](https://github.com/okohlbacher/mzPeakJ){: .btn }
&nbsp;
[Design notes](https://github.com/okohlbacher/mzPeakJ/blob/main/DESIGN.md){: .btn }

## Highlights

- **Reads** unpacked `*.mzpeak/` directories and single-file (STORED) `.mzpeak` ZIPs.
- **`point` and delta-`chunk`** signal layouts; spectra (MS1 + MSn), chromatograms, and UV/DAD
  wavelength spectra.
- **Profile reconstruction** of null-marked points (point counts match the reference).
- **Writes** mzPeak (ZSTD-compressed Parquet, point layout) to a directory or a STORED ZIP.
- **No Hadoop runtime** — a custom zstd-jni codec means no Hadoop FileSystem, `Configuration`, or
  native libraries are ever touched.
- **Runnable example tools** — file summary (OpenMS-`FileInfo`-style), spectrum extractor, DTA
  converter, and XIC extractor.

## Quick start

```java
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"))) {
    System.out.println(reader.size() + " spectra");
    Spectrum s = reader.getSpectrum(0).orElseThrow();
    double[] mz = s.mz();
    double[] intensity = s.intensity();
}
```

Writing:

```java
import org.mzpeak.io.MzPeakWriter;

MzPeakWriter.writeArchive(Path.of("out.mzpeak"), spectra, chromatograms);
```

CLI:

```bash
java --enable-native-access=ALL-UNNAMED -cp "target/classes:<deps>" \
     org.mzpeak.cli.MzPeakInfo path/to/run.mzpeak
```

## Status

Reference prototype. Cross-validated against the Rust `mzpeak_prototyping` test oracle. See the
[README](https://github.com/okohlbacher/mzPeakJ#readme) for scope, limitations, and the deferred list
(MS-Numpress decoding, exact `mz_delta_model` reconstruction, predicate pushdown). MIT-licensed.
