# mzPeakJ

[![CI](https://github.com/okohlbacher/mzPeakJ/actions/workflows/ci.yml/badge.svg)](https://github.com/okohlbacher/mzPeakJ/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A pure-JVM **Java reader and writer** for [mzPeak](https://www.psidev.info/mzpeak) — a next-generation
mass-spectrometry data format built on Apache Parquet, under development by the
[HUPO-PSI](https://www.psidev.info/).

> **⚠️ Demonstrator / proof-of-concept.** mzPeakJ is a vibe-coded demonstrator, built by following the
> mzPeak reference implementations (primarily the Rust crate
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping) by Joshua Klein, plus the
> HUPO-PSI [spec](https://github.com/HUPO-PSI/mzPeak) and example files). It shows that a clean,
> dependency-light JVM reader/writer is feasible — it is **not** a hardened production library.
> mzPeak itself is explicitly an unstable prototype; no stability is guaranteed upstream.
> Expect rough edges and pin to a specific commit before depending on any behaviour.

It reads the unpacked `*.mzpeak/` directory **and** the single-file `.mzpeak` (STORED ZIP); the `point`,
delta-`chunk`, and MS-Numpress `chunk` layouts; spectra (MS1 + MSn), chromatograms, and UV/DAD wavelength
spectra; and it writes mzPeak back out. See [Scope & limitations](#scope--limitations) for what's deferred.

## About the mzPeak format

mzPeak ([paper DOI 10.1021/acs.jproteome.5c00435](https://doi.org/10.1021/acs.jproteome.5c00435)) stores
mass-spectrometry runs as a set of ZSTD-compressed Parquet files plus a `mzpeak_index.json` manifest,
delivered as either an unpacked directory or a single uncompressed (STORED) ZIP archive. It uses Apache
Parquet's columnar layout to enable granular random access along the spectrum index and m/z axes without
loading the whole file. File-level metadata (instrument configuration, software, run, sample, data
processing) is stored as JSON documents in the Parquet footer key-value map. CV/user params follow the
PSI-MS controlled-vocabulary model.

**Other implementations** include the primary Rust implementation
[`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping), plus official HUPO-PSI repos for
[C#/.NET](https://github.com/HUPO-PSI/mzPeak.NET), [TypeScript](https://github.com/HUPO-PSI/mzpeakts),
Python, and R. mzPeakJ is an unofficial demonstrator; an official Java implementation is planned.

## Requirements & build

- JDK 17+ and Maven 3.9+.
- Set `JAVA_HOME` if `java` is not on your PATH:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # adjust to your JDK
  ```
- Build and test:
  ```bash
  mvn verify
  ```

The build is self-contained: it never calls Hadoop FileSystem, Configuration, native libraries, or any
cluster setup (see [DESIGN.md](DESIGN.md) §5).

## Library usage

### Reading

```java
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.Chromatogram;

// Works for both an unpacked directory and a single-file .mzpeak ZIP:
try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"))) {
    System.out.println(reader.size() + " spectra");

    // Random access by spectrum index, native id, vendor scan number, or nearest RT
    Spectrum s = reader.getSpectrum(0).orElseThrow();
    reader.getSpectrumByScanNumber(3);
    reader.getSpectrumById("controllerType=0 controllerNumber=1 scan=3");
    reader.getSpectrumByTime(12.5);

    double[] mz = s.mz();           // m/z array (profile points or centroid m/z)
    double[] intensity = s.intensity();
    s.peaks();                       // CentroidPeak list (centroided spectra only)

    // Activation method + collision energy (from precursor.activation.parameters)
    s.description().primaryPrecursor()
        .ifPresent(p -> System.out.println(p.activation().summary()));

    // Chromatograms and UV/DAD wavelength spectra
    reader.getChromatogramById("TIC").ifPresent(tic -> System.out.println(tic.size() + " TIC points"));
    reader.wavelengthSpectra();     // returns List<WavelengthSpectrum>

    // File/run-level metadata from the Parquet footer
    var meta = reader.fileMetadata();
    System.out.println("Instrument: " + meta.instrumentConfigurations().get(0));

    // Tolerance-based centroid peak search
    s.findPeak(810.79, org.mzpeak.model.Tolerance.ppm(20));
    s.findPeaksBetween(800.0, 820.0);

    for (Spectrum spec : reader) { /* iterate all spectra in index order */ }
}
```

### Writing

```java
import org.mzpeak.io.MzPeakWriter;

// Point layout (default) — to a directory or STORED ZIP:
MzPeakWriter.writeDirectory(Path.of("out.mzpeak"), spectra, chromatograms);
MzPeakWriter.writeArchive(Path.of("out.mzpeak"), spectra, chromatograms);

// Numpress chunk layout (MS-Numpress linear m/z + SLOF intensity):
MzPeakWriter.writeDirectoryNumpress(dir, spectra, chromatograms);
MzPeakWriter.writeArchiveNumpress(archive, spectra, chromatograms);
// Integer ion-count arrays: use NumpressIntensityEncoding.PIC
MzPeakWriter.writeDirectoryNumpress(dir, spectra, chroms,
        fileMetadata, MzPeakWriter.NumpressIntensityEncoding.PIC);
```

Writer notes:
- ZSTD-compressed Parquet, fully Hadoop-free.
- Writes spectra (profile + centroid), chromatograms, and file/run footer metadata.
- Writes per-spectrum/scan/selected-ion CV param lists (read back by the reader).
- Wavelength spectra and writing of chunk/Numpress layouts from profile arrays are not supported.

## Running the CLI & examples

```bash
./run-example.sh <fully.qualified.MainClass> [args...]
```

The repository ships HUPO-PSI example datasets under `src/test/resources/mzpeak/`:

| Fixture | Form | Contents |
|---|---|---|
| `small.unpacked.mzpeak/` | unpacked directory | 48 spectra (14 MS1 + 34 MS2), 1 TIC chromatogram |
| `small.mzpeak` | single-file STORED ZIP | same, packed (per-entry zip64 extra fields) |
| `small.chunked.mzpeak` | single-file ZIP | same data, delta-`chunk` layout; secondary peaks for all spectra |
| `small.numpress.mzpeak` | single-file ZIP | same data, MS-Numpress linear+SLOF chunk encoding |
| `has_uv.mzpeak` | single-file ZIP | 212 MS spectra + 520 UV/DAD spectra + 2 chromatograms |

### Example tools

All tools share the filter options `--ms-level 1,2`, `--rt-min M`, `--rt-max M`.

#### `MzPeakFileInfo` — file summary (mzPeak port of OpenMS `FileInfo`)

Prints data ranges (RT / m/z / intensity), spectrum & peak counts per MS level, peak type, precursor charge
distribution, activation methods, instrument configuration, and software. `-s` adds a five-number intensity
summary.

```bash
./mzpeak-fileinfo.sh src/test/resources/mzpeak/small.mzpeak -s
./run-example.sh org.mzpeak.examples.MzPeakFileInfo src/test/resources/mzpeak/has_uv.mzpeak
```

#### `MzPeakInfo` — quick per-spectrum dump

```bash
./run-example.sh org.mzpeak.cli.MzPeakInfo src/test/resources/mzpeak/small.mzpeak 6
```

#### `ExtractSpectra` — filter into a new mzPeak file (reads + writes)

```bash
./run-example.sh org.mzpeak.examples.ExtractSpectra \
    src/test/resources/mzpeak/small.mzpeak /tmp/ms2.mzpeak --ms-level 2 --rt-max 0.1
```
Produces an unpacked directory or STORED ZIP depending on the output name.

#### `ConvertToDta` — MSn spectra → Sequest `.dta` files

```bash
./run-example.sh org.mzpeak.examples.ConvertToDta \
    src/test/resources/mzpeak/small.mzpeak /tmp/dta --ms-level 2 --default-charge 2
```
One `.dta` per spectrum (`<base>.<scan>.<scan>.<charge>.dta`); first line `<MH+> <charge>`.

#### `ExtractXic` — extracted-ion chromatogram for a target m/z

```bash
./run-example.sh org.mzpeak.examples.ExtractXic \
    src/test/resources/mzpeak/small.mzpeak /tmp/xic.csv --mz 810.79 --tol-ppm 20
```
Writes `retention_time,intensity` CSV rows (MS1 by default). Tolerance via `--tol-ppm` or `--tol-da`.

## Architecture

For format internals and decode decisions (the packed-parallel-tables facet-join trap, chunk decoding, the
chunk_start-as-a-point rule, null-marking & `mz_delta_model` reconstruction, the Hadoop-free Parquet path,
type variance, the in-place STORED-ZIP reader), see **[DESIGN.md](DESIGN.md)**.

| Package | Responsibility |
|---|---|
| `org.mzpeak.model` | Pure data — no I/O deps: `CentroidPeak`, `Spectrum`, `SpectrumDescription`, `Precursor`, `Activation`, `SelectedIon`, `IsolationWindow`, `ScanEvent`, `Chromatogram`, `WavelengthSpectrum`, `Param`, `Tolerance`, `CvTerms`, … |
| `org.mzpeak.model.meta` | File/run-level metadata records: `FileMetadata`, `InstrumentConfiguration`, `Software`, `MsRun`, `DataProcessing`, `Sample`, `FileDescription` |
| `org.mzpeak.io` | `MzPeakReader`, `MzPeakWriter`, `MzPeakManifest`, source abstraction (`DirectorySource` / `ZipSource`), metadata + array + chromatogram + wavelength stores |
| `org.mzpeak.io.parquet` | Hadoop-free Parquet I/O: `ParquetGroups` (type-variance-safe accessors), `ZstdCompressionCodecFactory`, `ByteArrayInputFile`, `FileSliceInputFile` |
| `org.mzpeak.cli` / `org.mzpeak.examples` | Runnable demonstrator tools |

**Parquet without Hadoop:** mzPeak Parquet files are ZSTD-compressed. Rather than using parquet-java's
default Hadoop-based codec factory, mzPeakJ supplies a `ZstdCompressionCodecFactory` backed by the
self-contained `zstd-jni` jar. No Hadoop FileSystem, Configuration, native libraries, or cluster setup is
ever used. A single self-contained `hadoop-client-api` jar must be on the classpath because parquet-java's
classes statically reference Hadoop types — but it is never actually invoked.

## Format notes

- An unpacked `*.mzpeak/` directory holds Parquet files plus a `mzpeak_index.json` manifest. Files are
  resolved by `(entity_type, data_kind)` pair — **never** by filename. The standard entity types are
  `"spectrum"`, `"chromatogram"`, and `"wavelength spectrum"` (exact strings including space).
- `spectra_metadata.parquet` uses **packed parallel tables**: `spectrum` / `scan` / `precursor` /
  `selected_ion` struct columns joined by `source_index` — **not row-aligned**. Each facet row carries its
  own `source_index` FK; rows with null `source_index` are placeholders and must be skipped. Joining on row
  position silently mis-assigns precursors.
- Signal layouts: **point** (one row per peak, `spectrum_index + mz + intensity`) and **chunk** (one row per
  m/z chunk: `spectrum_index + mz_chunk_start + mz_chunk_end + mz_chunk_values + chunk_encoding + intensity`).
  Delta encoding uses CV terms `MS:1003088`/`MS:1003089` (mzPeak-specific, not OBO PSI-MS); Numpress uses
  `MS:1002312` (linear), `MS:1002313` (PIC), `MS:1002314` (SLOF).
- `spectra_peaks.parquet` is **optional** — it stores centroid peaks separate from profile arrays, allowing
  both to coexist for the same spectrum. The chunked fixture includes secondary centroid peaks for all spectra
  (e.g. spectrum 0 has 1612 centroid peaks alongside its 13589-point profile array).
- File/run-level metadata is stored as JSON in the Parquet footer key-value map under six keys:
  `file_description`, `instrument_configuration_list`, `software_list`, `data_processing_method_list`,
  `sample_list`, and `run`.
- Tested against vendored HUPO-PSI example files (upstream commit pinned in
  `src/test/resources/mzpeak/UPSTREAM_COMMIT.txt`).

## Scope & limitations

**Implemented**

- Containers: unpacked `*.mzpeak/` directory **and** single-file STORED `.mzpeak` ZIP (in-place seekable
  reads from the STORED members, no whole-member buffering).
- Layouts: `point`, delta-encoded `chunk`, and MS-Numpress `chunk` (linear m/z + SLOF or PIC intensity).
  Numpress encoding is lossy; total ion current is preserved to <1%.
- Spectra (MS1 + MSn): metadata + precursor/selected-ion/activation params (facet join by `source_index`),
  `double[]` m/z + intensity, centroid peaks, and profile reconstruction of null-marked points using the
  `mz_delta_model` polynomial (linear interpolation fallback when no model is stored).
- Chromatograms (TIC, DAD/absorption, …) — point and chunk time-axis layouts both decoded.
- Wavelength (UV/DAD) spectra as a dedicated `WavelengthSpectrum` type.
- File/run metadata from Parquet footer: instrument configuration, software, run, source files, sample, data
  processing (read + written).
- Per-spectrum, per-scan, and per-selected-ion CV param lists (read + written).
- Activation methods and collision energy from `precursor.activation.parameters` (CID, HCD, ETD, …).
- Random access by index, native id, vendor scan number (Thermo/Waters/Agilent/Bruker/SCIEX), or nearest RT.
- Streaming reader — only the row groups covering a requested spectrum are decoded; bounded memory.
- Writing: ZSTD Parquet (point layout + Numpress chunk layout with SLOF or PIC intensity), directory and STORED ZIP.
- Runnable example tools: file summary, spectrum extractor, DTA converter, XIC extractor.
- **123 tests** (JDK 17 + 21 via GitHub Actions CI), cross-validated against Rust `mzpeak_prototyping`
  reference test suite and independently verified with pyarrow.

**Deferred / known limitations:**

- **Writing**: chunk/delta layout writing, wavelength spectra writing.
- **Chromatogram chunk writing** (chunk time-axis layout is decoded but not written).
- **Full zip64 EOCD** (per-entry zip64 extra fields are handled; a true zip64 EOCD would be rejected).
- **Predicate/page-level pushdown** within a row group (whole row groups are skipped by min/max stats, but all pages within a selected group are decoded).
- **Detail-level loading** (metadata-only vs. full spectrum).
- No **Maven Central** publication yet.

## Development

Scaffolded with the GSD workflow harness (`.planning/`). `codex` CLI adversarial reviews were run at each
phase boundary; findings drove hardening of the zip parser, ZSTD codec, chunk-intensity alignment, param
union decoding, and facet-join correctness.

Project site: <https://okohlbacher.github.io/mzPeakJ/>

## License

[MIT](LICENSE) © 2026 Oliver Kohlbacher. Bundles:
- Apache-2.0 [MS-Numpress](https://github.com/ms-numpress/ms-numpress) decoders (`src/main/java/ms/numpress/`)
- Apache-2.0 HUPO-PSI mzPeak example fixtures (`src/test/resources/`)

See [NOTICE](NOTICE).
