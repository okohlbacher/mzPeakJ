# mzPeakJ

A minimal **Java reader** for the [HUPO-PSI mzPeak](https://www.psidev.info/mzpeak) mass-spectrometry data
format (Apache Parquet–based). Ported from the Rust reference implementation
([`mzpeaks`](https://github.com/mobiusklein/mzpeaks) / `mzdata` /
[`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping)), with a converter into
[FragPipe](https://github.com/Nesvilab/FragPipe)/MSFragger's I/O layer (MSFTBX).

> Reader-only. Reads the unpacked `*.mzpeak/` directory **and** the single-file `.mzpeak` ZIP; `point` and
> delta-`chunk` layouts; spectra **and** chromatograms. See **Scope & limitations** for what's deferred.

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

// Works for both an unpacked directory and a single-file .mzpeak ZIP:
try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"))) {
    System.out.println(reader.size() + " spectra");

    // Random access by mzPeak spectrum index, native id, vendor scan number, or retention time
    Spectrum s = reader.getSpectrum(0).orElseThrow();
    reader.getSpectrumByScanNumber(3);
    reader.getSpectrumById("controllerType=0 controllerNumber=1 scan=3");
    reader.getSpectrumByTime(12.5);

    double[] mz = s.mz();          // m/z array (profile points or centroid m/z)
    double[] intensity = s.intensity();
    s.peaks();                     // centroid peaks (for centroided spectra)
    System.out.println(s.description().msLevel() + " @ RT " + s.description().retentionTime());

    // Chromatograms (TIC, BPC, ...)
    reader.getChromatogramById("TIC").ifPresent(tic -> System.out.println(tic.size() + " TIC points"));

    for (Spectrum spec : reader) { /* iterate all spectra */ }
}
```

### CLI

```bash
java --enable-native-access=ALL-UNNAMED -cp "target/classes:$(deps)" \
     org.mzpeak.cli.MzPeakInfo path/to/run.mzpeak
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

For format internals and the decode rationale (the facet-join trap, chunk decoding, null-marking,
Hadoop-free Parquet, type variance), see **[DESIGN.md](DESIGN.md)**.

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

## Scope & limitations

**Implemented**

- Containers: unpacked `*.mzpeak/` directory **and** single-file (STORED) `.mzpeak` ZIP.
- Layouts: `point` and delta-encoded `chunk`.
- Spectra (MS1 + MSn) with metadata + precursor facet joins (by `source_index`), `double[]` m/z + intensity,
  centroid peaks, and **profile reconstruction** of null-marked points (default on) so the materialized point
  count matches the declared `number_of_data_points` (e.g. 13589 for spectrum 0 — matching the Rust reference).
- Chromatograms (TIC, DAD/absorption, ...).
- Wavelength (UV/DAD) spectra as a dedicated `WavelengthSpectrum` type (`reader.wavelengthSpectra()`).
- Random access by index, native id, vendor scan number, and nearest retention time; iteration.
- MSFTBX/FragPipe adapter.

Cross-validated against the Rust `mzpeak_prototyping` reference test values across the unpacked / ZIP / chunked
fixtures (spectrum 0 → 13589 points; 5 → 650 peaks; 25 → 789 peaks).

**Deferred / known limitations** (tracked in `.planning/REQUIREMENTS.md`):

- **MS-Numpress chunks** (`mz_numpress_linear_bytes` / `intensity_numpress_slof_bytes`) are detected and
  rejected with a clear error — full Numpress + delta-model reconstruction is future work.
- **Profile/chunk reconstruction is approximate**: null-marked m/z are filled by linear interpolation between
  real anchors (not the exact `mz_delta_model` polynomial), and chunk decode prepends the first chunk's
  `chunk_start`. Point counts and anchor values match the reference; interpolated-point m/z are approximate.
- **No predicate/row-group pushdown**: signal files are read fully once and cached (fine for example-scale
  data; streaming for large files is future work — `PEAK-03`).
- **Writing** mzPeak is out of scope (reader only).
- **Multi-precursor**: all precursor records are preserved, but selected ions are attached to the first
  precursor (the example format has one precursor per MSn spectrum).

## Development

This project is scaffolded with the GSD workflow harness (`.planning/`). Each phase boundary runs a `codex`
CLI adversarial review; findings drove the hardening in `ZstdCompressionCodecFactory`, `PointArrayStore`, and
`MsftbxAdapter`.
