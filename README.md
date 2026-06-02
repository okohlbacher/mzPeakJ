# mzPeakJ

[![CI](https://github.com/okohlbacher/mzPeakJ/actions/workflows/ci.yml/badge.svg)](https://github.com/okohlbacher/mzPeakJ/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A pure-JVM **Java reader and writer** for the [HUPO-PSI mzPeak](https://www.psidev.info/mzpeak)
mass-spectrometry data format (Apache Parquet–based).

> **⚠️ Demonstrator / proof-of-concept.** mzPeakJ is a *vibe-coded* demonstrator, written by following the
> existing mzPeak reference implementations — the Rust crates
> [`mzpeaks`](https://github.com/mobiusklein/mzpeaks) / `mzdata` /
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping), and the HUPO-PSI
> [mzPeak](https://github.com/HUPO-PSI/mzPeak) spec + example files. It is meant for exploration and to show
> that a clean, dependency-light JVM reader/writer is feasible — **not** a hardened production library. mzPeak
> itself is an unstable prototype format; this project is pinned to one upstream commit. Expect rough edges.

It reads the unpacked `*.mzpeak/` directory **and** the single-file `.mzpeak` ZIP; the `point` and
delta-`chunk` layouts; spectra (MS1 + MSn), chromatograms, and UV/DAD wavelength spectra; and it can write
mzPeak back out. See **Scope & limitations** for what's deferred.

## Requirements & build

- A JDK 17+ (developed on Homebrew `openjdk@25`) and Maven 3.9+.
- Set `JAVA_HOME` if `java` is not on your `PATH`:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # adjust to your JDK
  ```
- Build and test:
  ```bash
  mvn verify
  ```

The build is self-contained: it never uses a Hadoop FileSystem, `Configuration`, native libraries, or any
cluster setup (see [DESIGN.md](DESIGN.md) §5).

## Library usage

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

    reader.getChromatogramById("TIC").ifPresent(tic -> System.out.println(tic.size() + " TIC points"));
    for (Spectrum spec : reader) { /* iterate all spectra */ }
}
```

Writing:

```java
import org.mzpeak.io.MzPeakWriter;

// ZSTD-compressed Parquet (point layout), to an unpacked directory or a single-file STORED ZIP:
MzPeakWriter.writeDirectory(Path.of("out.mzpeak"), spectra, chromatograms);
MzPeakWriter.writeArchive(Path.of("out.mzpeak"), spectra, chromatograms);   // round-trips through the reader
```

## Running the CLI & examples

A helper script builds the project and assembles the classpath for you:

```bash
./run-example.sh <fully.qualified.MainClass> [args...]
```

The repository ships small HUPO-PSI example datasets under `src/test/resources/mzpeak/` you can run against
immediately:

| Fixture | Form | Contents |
|---|---|---|
| `small.unpacked.mzpeak/` | unpacked directory | 48 spectra (14 MS1 + 34 MS2), 1 TIC chromatogram |
| `small.mzpeak` | single-file ZIP | same as above, packed |
| `small.chunked.mzpeak` | single-file ZIP | same data, delta-`chunk` layout |
| `small.numpress.mzpeak` | single-file ZIP | same data, MS-Numpress (linear m/z + SLOF intensity) — read (lossy) |
| `has_uv.mzpeak` | single-file ZIP | 212 MS spectra + 520 UV/DAD spectra + TIC & DAD chromatograms |

### `MzPeakInfo` — quick one-line-per-spectrum dump

```bash
./run-example.sh org.mzpeak.cli.MzPeakInfo src/test/resources/mzpeak/small.mzpeak 6
```

### Example tools (`org.mzpeak.examples`)

All tools share the filter options **`--ms-level 1,2`** (comma-separated or repeated), **`--rt-min M`**,
**`--rt-max M`** (retention time in the file's unit).

#### `MzPeakFileInfo` — file summary (an mzPeak-only port of OpenMS `FileInfo`)

Prints overall and per-MS-level ranges (RT / m/z / intensity), spectrum & peak counts per MS level, peak type
(profile/centroid), precursor charge distribution, and chromatogram breakdown. `-s` adds a five-number
intensity summary.

```bash
./run-example.sh org.mzpeak.examples.MzPeakFileInfo src/test/resources/mzpeak/small.mzpeak -s
# or the convenience wrapper:
./mzpeak-fileinfo.sh src/test/resources/mzpeak/small.mzpeak -s
```

#### `ExtractSpectra` — filter into a new mzPeak file (read **and** write)

```bash
# Keep only MS2 spectra in the 0–0.1 min window, write a new single-file .mzpeak
./run-example.sh org.mzpeak.examples.ExtractSpectra \
    src/test/resources/mzpeak/small.mzpeak /tmp/ms2.mzpeak --ms-level 2 --rt-max 0.1
```
If the output name ends in `.mzpeak` a single-file ZIP is written, otherwise an unpacked directory.

#### `ConvertToDta` — MSn spectra → Sequest `.dta` files

```bash
./run-example.sh org.mzpeak.examples.ConvertToDta \
    src/test/resources/mzpeak/small.mzpeak /tmp/dta --ms-level 2 --default-charge 2
```
One `.dta` per spectrum, named `<base>.<scan>.<scan>.<charge>.dta`; first line is `<MH+> <charge>`, then
`<m/z> <intensity>` rows. `--default-charge` is used when a precursor charge is missing.

#### `ExtractXic` — extracted-ion chromatogram for a target m/z

```bash
./run-example.sh org.mzpeak.examples.ExtractXic \
    src/test/resources/mzpeak/small.mzpeak /tmp/xic.csv --mz 810.79 --tol-ppm 20
```
Writes `retention_time,intensity` rows (one per matching spectrum; MS1 by default). Tolerance via
`--tol-ppm` (default 20) or `--tol-da`.

## Architecture

For format internals and the decode rationale (the facet-join trap, chunk decoding, null-marking, the
Hadoop-free Parquet path, type variance), see **[DESIGN.md](DESIGN.md)**.

| Package | Responsibility |
|---|---|
| `org.mzpeak.model` | Pure data: peak primitives (`CentroidPeak`, `DeconvolutedPeak`), `Spectrum`, `SpectrumDescription`, `Precursor`, `Chromatogram`, `WavelengthSpectrum`, `Tolerance`, ... |
| `org.mzpeak.io` | `MzPeakReader`, `MzPeakWriter`, `MzPeakManifest`, source abstraction (dir/ZIP), metadata + array stores |
| `org.mzpeak.io.parquet` | Hadoop-free Parquet I/O (`LocalInputFile`/`LocalOutputFile` + `PlainParquetConfiguration` + a zstd-jni `CompressionCodecFactory`) |
| `org.mzpeak.cli` / `org.mzpeak.examples` | Runnable demonstrator tools |

**Parquet without Hadoop:** mzPeak columns are ZSTD-compressed. parquet-java's default codec factory builds a
Hadoop `Configuration`; we instead supply `ZstdCompressionCodecFactory` (backed by the self-contained
`zstd-jni` jar), so no Hadoop FileSystem or `Configuration` is ever touched. parquet-java's classes still
statically reference Hadoop types, so a single self-contained `hadoop-client-api` jar is on the classpath —
but no Hadoop install, native libs, or config is needed.

## Format notes (for maintainers)

- An unpacked `*.mzpeak/` directory holds Parquet files + a `mzpeak_index.json` manifest; files are resolved
  by `(entity_type, data_kind)`, not by filename.
- `spectra_metadata.parquet` uses **packed parallel tables**: `spectrum` / `scan` / `precursor` /
  `selected_ion` struct columns that are **not row-aligned** — each facet carries its own `source_index`.
  mzPeakJ joins strictly by `source_index` (placeholder rows with a null `source_index` are skipped).
- Signal is long/tall `point{spectrum_index, mz, intensity}` rows in `spectra_data.parquet` (profile) and
  `spectra_peaks.parquet` (centroids).
- Tested against the vendored HUPO-PSI example files (upstream commit pinned in
  `src/test/resources/mzpeak/.../UPSTREAM_COMMIT.txt`). The format is an unstable prototype.

## Scope & limitations

**Implemented**

- Containers: unpacked `*.mzpeak/` directory **and** single-file (STORED) `.mzpeak` ZIP.
- Layouts: `point`, delta-encoded `chunk`, and **MS-Numpress** `chunk` (linear m/z + SLOF intensity; lossy).
- Spectra (MS1 + MSn) with metadata + precursor facet joins (by `source_index`), `double[]` m/z + intensity,
  centroid peaks, and **profile reconstruction** of null-marked points (default on) so the materialized point
  count matches the declared `number_of_data_points` (e.g. 13589 for spectrum 0 — matching the Rust reference).
- Chromatograms (TIC, DAD/absorption, ...) and wavelength (UV/DAD) spectra (`reader.wavelengthSpectra()`).
- **File/run metadata** from the Parquet footer (`reader.fileMetadata()`): instrument configuration,
  software, run, source files (CV-param model).
- Random access by index, native id, vendor scan number (multi-vendor nativeID parsing), and nearest
  retention time; iteration.
- **Streaming reader**: only the row group(s) covering a requested spectrum are decoded (bounded memory on
  large multi-row-group files). STORED (uncompressed) members of a single-file `.mzpeak` ZIP are read
  **in place** (seekable file slice, no whole-member buffering), so a single-file archive streams just like a
  directory.
- **Writing** mzPeak (ZSTD Parquet, point layout, + footer metadata) to a directory or STORED ZIP —
  round-trips through the reader.
- A self-contained **shaded jar** (`mzpeakj-<v>-all.jar`) for running the CLI/examples with just `-cp`.

Cross-validated against the Rust `mzpeak_prototyping` reference test values across the unpacked / ZIP / chunked
fixtures (spectrum 0 → 13589 points; 5 → 650 peaks; 25 → 789 peaks).

**Deferred / known limitations:**

- **MS-Numpress** linear (m/z) + SLOF (intensity) chunks are read (decoding is lossy, as the format intends).
  Numpress **PIC** and **writing** Numpress are not implemented.
- **Profile reconstruction** fills null-marked m/z by stepping with the stored `mz_delta_model` polynomial
  (linear interpolation only as a fallback when no model is present).
- **Writing** is point-layout only and does not encode `chunk`/Numpress layouts or wavelength spectra
  (metadata footer *is* written).
- **MS-Numpress PIC** (`MS:1002313`) is not decoded (linear + SLOF are).

## Development

Scaffolded with the GSD workflow harness (`.planning/`); each phase ran a `codex` CLI adversarial review whose
findings drove much of the hardening. CI (`mvn verify` on JDK 17 + 21) runs via GitHub Actions.

Project site: <https://okohlbacher.github.io/mzPeakJ/>

## License

[MIT](LICENSE) © 2026 Oliver Kohlbacher. Bundles the Apache-2.0 [MS-Numpress](https://github.com/ms-numpress/ms-numpress)
decoders (`src/main/java/ms/numpress/`) and the HUPO-PSI mzPeak example fixtures (Apache-2.0) under
`src/test/resources/`. See [NOTICE](NOTICE).
