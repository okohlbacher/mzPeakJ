# mzPeakJ

A minimal **Java reader** for the [HUPO-PSI mzPeak](https://www.psidev.info/mzpeak) mass-spectrometry data
format (Apache Parquet–based). Ported from the Rust reference implementation
([`mzpeaks`](https://github.com/mobiusklein/mzpeaks) / `mzdata` /
[`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping)), with a converter into
[FragPipe](https://github.com/Nesvilab/FragPipe)/MSFragger's I/O layer (MSFTBX).

> Milestone 1 — reader-only, unpacked-directory, `point`-layout, spectra only. See **Scope & limitations**.

## Requirements

- A JDK (built/tested with Homebrew `openjdk@25`). Set `JAVA_HOME` before building:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25
  ```
- Maven 3.9+.

```bash
mvn verify
```

## Usage

```java
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.unpacked.mzpeak"))) {
    System.out.println(reader.size() + " spectra");

    // Random access by mzPeak spectrum index
    Spectrum s = reader.getSpectrum(0).orElseThrow();
    double[] mz = s.mz();          // m/z array
    double[] intensity = s.intensity();
    System.out.println(s.description().msLevel() + " @ RT " + s.description().retentionTime());

    // Iterate all spectra
    for (Spectrum spec : reader) {
        // ...
    }
}
```

### FragPipe / MSFragger conversion

```java
import org.mzpeak.fragpipe.MsftbxAdapter;
import umich.ms.datatypes.scan.IScan;

IScan scan = MsftbxAdapter.toScan(spectrum); // umich.ms.datatypes IScan + attached ISpectrum
```

The core reader has **no** dependency on FragPipe; the `msftbx` dependency is `optional` and used only by
`org.mzpeak.fragpipe`. mzPeakJ stores intensities as `double[]`, so conversion into MSFTBX's
`ISpectrum` is a zero-copy array passthrough.

## Architecture

| Package | Responsibility |
|---|---|
| `org.mzpeak.model` | Pure data: peak primitives (`CentroidPeak`, `DeconvolutedPeak`), `Spectrum`, `SpectrumDescription`, `Precursor`, `Tolerance`, ... |
| `org.mzpeak.io` | `MzPeakReader`, `MzPeakManifest`, metadata decoder, point-array store |
| `org.mzpeak.io.parquet` | Hadoop-free Parquet reading (`LocalInputFile` + `PlainParquetConfiguration` + a zstd-jni `CompressionCodecFactory`) |
| `org.mzpeak.fragpipe` | `MsftbxAdapter` (isolated FragPipe/MSFTBX bridge) |

**Parquet without Hadoop:** mzPeak columns are ZSTD-compressed. parquet-java's default codec factory builds a
Hadoop `Configuration`; we instead supply `ZstdCompressionCodecFactory` (backed by the self-contained
`zstd-jni` jar), so no Hadoop runtime/`FileSystem` is ever touched. `hadoop-client-api` is a `provided`
compile-only dependency (parquet-java's API signatures reference `Configuration`) and is **not** shipped.

## Format notes (for maintainers)

- An unpacked `*.mzpeak/` directory holds Parquet files + a `mzpeak_index.json` manifest; files are resolved
  by `(entity_type, data_kind)`, not by filename.
- `spectra_metadata.parquet` uses **packed parallel tables**: `spectrum` / `scan` / `precursor` /
  `selected_ion` struct columns that are **not row-aligned** — each facet carries its own `source_index`.
  mzPeakJ joins strictly by `source_index` (placeholder rows with a null `source_index` are skipped).
- Signal is long/tall `point{spectrum_index, mz, intensity}` rows in `spectra_data.parquet` (profile) and
  `spectra_peaks.parquet` (centroids).
- Tested against the vendored HUPO-PSI example `small.unpacked.mzpeak` (upstream commit pinned in
  `src/test/resources/mzpeak/.../UPSTREAM_COMMIT.txt`). The format is an unstable prototype.

## Scope & limitations (milestone 1)

Implemented: unpacked directory, `point` layout, spectra (MS1 + MSn), metadata + precursor facet joins,
`double[]` m/z+intensity materialization, random access by index, iteration, MSFTBX adapter.

**Deferred** (tracked in `.planning/REQUIREMENTS.md`):

- **Single-file `.mzpeak` ZIP** random access — only the unpacked directory form is read.
- **`chunk` layout + MS-Numpress / delta** encodings — only `point` layout.
- **Profile reconstruction.** Null-marked points (null m/z flanks) are **dropped**, so a profile spectrum's
  materialized `pointCount()` may be less than its declared `numberOfDataPoints` (e.g. 11213 vs 13589 for
  spectrum 0 of the fixture). Reconstruction via `mz_delta_model` is future work.
- **Predicate/row-group pushdown.** Point files are read fully once and cached in memory (fine for the example
  data; streaming/pushdown for large files is future work — `PEAK-03`).
- **Chromatograms / wavelength spectra**, and **writing** mzPeak.
- **Multi-precursor spectra**: the decoder keeps the first precursor per spectrum (the example format has one);
  multi-precursor support is future work.
- **Scan-number / RT lookup** maps beyond addressing by integer index.

## Development

This project is scaffolded with the GSD workflow harness (`.planning/`). Each phase boundary runs a `codex`
CLI adversarial review; findings drove the hardening in `ZstdCompressionCodecFactory`, `PointArrayStore`, and
`MsftbxAdapter`.
