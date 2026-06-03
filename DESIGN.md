# mzPeakJ — Design & Format Notes

This document captures how the [mzPeak](https://www.psidev.info/mzpeak) format works (as observed in the
HUPO-PSI example files and spec) and the decisions behind this Java reader/writer. It is the reference for
anyone extending mzPeakJ or porting the format to a new language.

> **Demonstrator.** mzPeakJ is vibe-coded by following the Rust reference implementation
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping) and the HUPO-PSI spec. Ground
> truth is the upstream `doc/index.md` **plus the actual example Parquet files** (the published paper
> [doi:10.1021/acs.jproteome.5c00435](https://doi.org/10.1021/acs.jproteome.5c00435) does not include
> schema detail). mzPeak is an unstable prototype; this project is pinned to upstream commit `03ccdb7`
> (see `src/test/resources/mzpeak/UPSTREAM_COMMIT.txt`).

---

## 1. What an mzPeak dataset is

A set of Apache Parquet files plus a `mzpeak_index.json` manifest, delivered as **either**:

- an **unpacked directory** `run.mzpeak/`, or
- a **single file** `run.mzpeak` — an **uncompressed (STORED) ZIP** of the same members.

TAR was explicitly rejected by the spec because it requires linear traversal and lacks per-file encryption.
ZIP was chosen because members can be accessed at arbitrary byte offsets.

`mzPeakJ` reads both via the `MzPeakSource` abstraction (`DirectorySource` / `ZipSource`). Because the ZIP
members are STORED (no ZIP-level compression — Parquet files use their own ZSTD), `ZipSource` parses the
central directory (`ZipIndex`) and reads each Parquet member **in place** from its byte range via a seekable
`FileSliceInputFile` over the archive's shared `FileChannel` — no copy into memory. The example archives use
per-entry zip64 extra fields for sizes even with a non-zip64 EOCD, which `ZipIndex` handles.

The manifest maps each member to a role `(entity_type, data_kind)`. **Resolve files by role, never by
hardcoded filename.** Standard entity types (exact strings, including space where present):

| `entity_type` | `data_kind` | Conventional file | mzPeakJ store |
|---|---|---|---|
| `"spectrum"` | `"metadata"` | `spectra_metadata.parquet` | `SpectrumMetadataDecoder` |
| `"spectrum"` | `"data arrays"` | `spectra_data.parquet` (profile signal) | `SpectrumArrayStore` |
| `"spectrum"` | `"peaks"` | `spectra_peaks.parquet` (centroid peaks) — **optional** | `SpectrumArrayStore` |
| `"chromatogram"` | `"metadata"` / `"data arrays"` | `chromatograms_*.parquet` | `ChromatogramStore` |
| `"wavelength spectrum"` | `"metadata"` / `"data arrays"` | `wavelength_spectra_*.parquet` | `WavelengthSpectrumStore` |

Note `"wavelength spectrum"` uses two words with a space — that is the exact `entity_type` string in the
manifest JSON schema.

---

## 2. The metadata "packed parallel tables" trap — the most important correctness rule

`spectra_metadata.parquet` has **four independent top-level struct columns** — `spectrum`, `scan`,
`precursor`, `selected_ion` — that act like relational tables joined by integer keys. They are
**NOT row-aligned** with each other.

### Join rule

- `spectrum.index` (uint64) is the primary key.
- `scan`, `precursor`, and `selected_ion` each carry their own `source_index` foreign key. A row where
  `source_index IS NULL` is a placeholder and must be skipped.
- Join: for target spectrum at `index = N`, collect all facet rows where `source_index = N`.

### Why this is dangerous

In `small.unpacked.mzpeak`: at metadata row 0 (spectrum index 0, an MS1), the `selected_ion` column holds a
row with `source_index = 2` — it belongs to spectrum 2, not 0. Zipping by row position silently mis-assigns
every precursor. Always join by `source_index`.

### Implementation (`SpectrumMetadataDecoder`)

Build `Map<Long, List<Group>>` for each facet by scanning all rows and grouping on `source_index`. Skip null
`source_index`. Then attach the matching facet list to each spectrum by `index`.

### Column-level metadata structures

**`spectrum` group** key fields:
- `index` — 0-based, incrementally increasing, sorted, primary key (uint64 stored as INT64)
- `id` — native ID string (MS:1000767 pattern; e.g. `controllerType=0 controllerNumber=1 scan=1`)
- `time` — acquisition start time
- `MS_1000511_ms_level` — integer MS stage (1, 2, 3…)
- `MS_1000525_spectrum_representation` — `"MS:1000127"` (centroid) or `"MS:1000128"` (profile)
- `MS_1000465_scan_polarity` — +1 / -1 / null
- `MS_1003060_number_of_data_points` — declared profile point count (may exceed stored count due to null-marking)
- `MS_1003059_number_of_peaks` — declared centroid peak count
- `parameters` — `large_list<item: struct<value-union, accession, name, unit>>` (CV/user params)
- `mz_delta_model` — polynomial coefficients for null-mark reconstruction (see §4)
- `auxiliary_arrays` — non-columnar binary arrays with encoding metadata

**`scan` group** — `source_index` FK + scan start time, filter string, injection time, instrument_configuration_ref, scan_windows, parameters.

**`precursor` group** — `source_index` FK + precursor_index, precursor_id, isolation_window struct
(`MS_1000827_isolation_window_target_mz`, `MS_1000828_isolation_window_lower_offset`,
`MS_1000829_isolation_window_upper_offset`), `activation` struct (parameters-only).

**`selected_ion` group** — `source_index` FK + selected ion m/z (MS:1000744), charge state (MS:1000041),
intensity (MS:1000042), ion mobility, parameters.

---

## 3. Signal layouts: `point` vs `chunk`

Each signal file's top-level struct is named `point` **or** `chunk`. `SpectrumArrayStore` dispatches per row.

### 3a. Point layout

```
point: struct<spectrum_index: uint64, mz: double, intensity: float>
```

One row per data point. `spectrum_index` is sorted non-decreasing. Profile data lives here; centroids in the
parallel `spectra_peaks.parquet` file (same schema). `spectra_peaks.parquet` is **optional** — it can store
centroid peaks alongside the profile arrays for the same spectrum (e.g. the chunked fixture has 1612
secondary centroid peaks for spectrum 0, which is a profile spectrum in `spectra_data.parquet`).

### 3b. Chunk layout (delta-encoded)

```
chunk: struct<spectrum_index: uint64, mz_chunk_start: double, mz_chunk_end: double,
              mz_chunk_values: large_list<double>, chunk_encoding: string,
              intensity: large_list<float>>
```

One row per fixed-width m/z chunk (typically ~50 Da wide). The `mz_chunk_start`/`mz_chunk_end` columns
remain accessible to the Parquet page index. The encoded values inside `mz_chunk_values` are opaque to the
index.

**Encoding CV terms** (mzPeak-specific, not OBO PSI-MS):
- No transform (absolute values): no `chunk_encoding` field value / `MS:1003088`
- Delta encoding: `MS:1003089` (differences from previous value, the start being `mz_chunk_start`)
- Numpress linear: `MS:1002312`
- Numpress PIC: `MS:1002313`
- Numpress SLOF: `MS:1002314`

**The chunk_start-as-a-point rule** (critical, learned from real data):

> A chunk whose `intensity.length == mz_chunk_values.length + 1` emits `mz_chunk_start` as its **first
> real point** (`intensity[0]` = its intensity); the decoded m/z values then pair with `intensity[1..]`. A
> chunk with equal lengths pairs values 1:1 with no separate start point.

This rule applies per-chunk, not just to the first chunk of a spectrum. Validated: for centroid spectra
(no null-marking) the chunk decode is byte-identical to the point layout.

### 3c. MS-Numpress chunk layout

When a chunk carries `mz_numpress_linear_bytes` (MS:1002312) and `intensity_numpress_slof_bytes`
(MS:1002314) or `intensity_numpress_pic_bytes` (MS:1002313), the arrays are decoded with the bundled
Apache-2.0 [MS-Numpress](https://github.com/ms-numpress/ms-numpress) Java decoders
(`ms.numpress.MSNumpress`). Unlike the delta path, Numpress encodes the **full absolute arrays directly**
with no chunk_start, no delta, no null-marking — decode-and-append 1:1. Numpress is lossy by design
(linear m/z is near-lossless; SLOF intensity is log-scaled uint16; PIC is integer-only).

**SLOF fixedPoint selection:** The SLOF encoder chooses `fixedPoint = floor(65535 / log(max_intensity + 1))`
per chunk so the encoded values fit in uint16. This is computed at write time and transparent to the reader.

---

## 4. Null-marking & profile reconstruction

Profile spectra drop flanking zero-intensity points. The stored point count (e.g. 11213 for spectrum 0)
is less than the declared `MS_1003060_number_of_data_points` (13589). The null-marked points have null
m/z values. The spec defines a reconstruction polynomial stored in the `mz_delta_model` column:

```
delta(mz) = β₀ + β₁·mz + β₂·mz²  (degree-2 least-squares polynomial on the non-null points)
```

**mzPeakJ reconstruction (default: `reconstructProfile=true`):**

1. Read the `mz_delta_model` coefficients from the `spectrum` group.
2. Step through null-marked m/z placeholders, advancing each by `predict(prev_mz)` using the polynomial.
3. Assign intensity 0 to all reconstructed points.
4. Result: correct point count (13589), exact anchor values, monotonic axis.

When no `mz_delta_model` is present, linear interpolation between real anchors is the fallback.
Pass `reconstructProfile=false` to get only the stored non-null points (e.g. 11213).

---

## 5. Parquet without Hadoop — the ZSTD problem

mzPeak Parquet files are ZSTD-compressed. parquet-java's default codec factory invokes a Hadoop
`Configuration` on every codec lookup, which pulls the shaded Woodstox/Hadoop runtime. mzPeakJ avoids this:

- **Reading/writing** use `LocalInputFile` / `ByteArrayInputFile` / `LocalOutputFile` +
  `PlainParquetConfiguration` + the parquet-column Group API (`ParquetGroups`).
- **Codec**: a custom `ZstdCompressionCodecFactory` backed by `zstd-jni` (self-contained native jar) is
  supplied to both `ParquetReadOptions.builder().withCodecFactory(...)` (reads) and
  `ParquetWriter.Builder.withCodecFactory(...)` (writes). The Hadoop `CodecFactory` and
  `Configuration` are **never** invoked.

**Caveat:** parquet-java's classes statically reference Hadoop types, so `hadoop-client-api` (a single
shaded jar with the Hadoop API stubs) must be on the classpath at compile and runtime. It is never used;
no Hadoop install, config, native library, or cluster setup is required.

---

## 6. Type-variance discipline

The spec allows physical-type variance:
- m/z: FLOAT or DOUBLE
- intensity: FLOAT, INT32, or DOUBLE
- Lists: `list` or `large_list`
- Strings: `string` or `large_string`

**mzPeakJ rule:** never assume a physical type. `ParquetGroups` dispatches on the actual
`PrimitiveTypeName` via `optDouble`/`optLong`/`doubleListNullable`, and navigates list structure by field
*position* so element names (`element`/`item`/`list`) are irrelevant. `spectrum_index` (uint64) is read
as signed Java `long`; values with the high bit set are rejected with a clear error.

---

## 7. Footer key-value metadata

File/run-level metadata is stored as JSON documents in the Parquet footer key-value map
(`getKeyValueMetaData()`) of `spectra_metadata.parquet`. Keys and their JSON shapes
(from `schema/*.json` in the HUPO-PSI repo):

| Footer key | JSON type | Key content |
|---|---|---|
| `file_description` | object | `contents: [Param]`, `source_files: [SourceFile]` |
| `instrument_configuration_list` | array | `[{id:int, components:[{component_type,order,parameters:[Param]}], parameters:[Param], software_reference:str}]` |
| `software_list` | array | `[{id:str, version:str, parameters:[Param]}]` |
| `data_processing_method_list` | array | `[{id:str, methods:[{order:int, software_reference:str, parameters:[Param]}]}]` |
| `sample_list` | array | `[{id:str, name:str, parameters:[Param]}]` |
| `run` | object | `{id:str, default_instrument_id:int, default_data_processing_id:str, default_source_file_id:str, start_time:RFC3339?, parameters:[Param]}` |

**Param record** (from `schema/param.json`):
- `name` (required string) — CV term name or user label
- `accession` (nullable string, format `\S+:\S+`) — e.g. `"MS:1000045"`; null = user param
- `value` (number | string | boolean | null)
- `unit` (nullable string, CURIE) — e.g. `"UO:0000266"` for electronvolt

`FooterMetadataReader` deserializes these into `org.mzpeak.model.meta.FileMetadata`. The reader is lenient:
missing keys yield empty/absent objects rather than errors.

---

## 8. Architecture

```
org.mzpeak.model             pure data — no I/O deps
  interfaces: CoordinateLike, IntensityMeasurement, Indexed, KnownCharge
  peaks:      CentroidPeak(mz, intensity, index)
  spectra:    Spectrum · SpectrumDescription · Precursor · SelectedIon
              Activation · IsolationWindow · ScanEvent · ScanWindow
              Polarity · SignalContinuity
  other:      Chromatogram · WavelengthSpectrum
  params:     Param · CvTerms · Tolerance(PPM|Da) · NativeId

org.mzpeak.model.meta        file/run-level metadata records
  FileMetadata · InstrumentConfiguration · Component · Software
  DataProcessing · Sample · MsRun · FileDescription · SourceFile

org.mzpeak.io                reading + writing
  MzPeakReader                open(dir|zip) · size · getMetadata · getSpectrum
                              by index/id/scan#/RT · iterator
                              chromatograms() · wavelengthSpectra() · fileMetadata()
  MzPeakSource                DirectorySource | ZipSource (in-place FileSliceInputFile)
  SpectrumMetadataDecoder     packed-parallel-tables → SpectrumDescription
  SpectrumArrayStore          streaming row-group-aware point+chunk+numpress decoder
  ChromatogramStore           point + chunk time-axis layouts
  WavelengthSpectrumStore     point layout
  FooterMetadataReader/Writer JSON ↔ FileMetadata
  MzPeakWriter                writeDirectory / writeArchive / writeDirectoryNumpress / writeArchiveNumpress

org.mzpeak.io.parquet         Hadoop-free Parquet I/O
  ParquetGroups               type-variance-safe accessors (optDouble, readParams, groupList, …)
  ZstdCompressionCodecFactory custom ZSTD compressor + decompressor
  ByteArrayInputFile          in-memory Parquet member (for ZIP fallback)
  FileSliceInputFile          seekable byte-range slice of a FileChannel (STORED ZIP)

org.mzpeak.cli                MzPeakInfo (dataset summary)
org.mzpeak.examples           ExtractSpectra · ConvertToDta · ExtractXic · MzPeakFileInfo
ms.numpress                   bundled MS-Numpress decoders (Apache-2.0, vendored)
```

**Streaming:** `SpectrumArrayStore` reads per-row-group `spectrum_index` min/max stats at open time.
`get(index)` decodes only the row group(s) that cover the requested index and caches the last block run.
`ZipSource` reads STORED members in place via `FileSliceInputFile` (positional `FileChannel` reads),
so a single-file archive streams identically to an unpacked directory.

---

## 9. Testing approach

- 123 tests against vendored HUPO-PSI fixtures (`small.unpacked` / `small` / `small.chunked` /
  `small.numpress` / `has_uv`).
- **Golden values** extracted independently with pyarrow, then cross-checked against the Rust reference.
- **Rust reference alignment**: `RustReferenceTest` ports the HUPO-PSI `reader.rs#[cfg(test)]` assertions
  directly (spectrum 0 → 13589 pts, spectrum 5 → 650 peaks, spectrum 25 → 789 peaks, TIC 48 pts).
- **Python reference alignment**: `CrossImplTest` + `EicAndCoverageTest` port `common_checks()` assertions
  from `mobiusklein/mzpeak_prototyping python/test/test_reader.py` (34 selected ions, spectrum 4 → 837
  peaks, EIC 96 centroid peaks, secondary peaks for chunked, native ID format, isolation window).
- **Byte-exact verification**: centroid arrays are byte-identical across point/ZIP/chunked containers.
- CI: JDK 17 + 21 via GitHub Actions.

---

## 10. Known limitations / deferred

- **Chromatogram chunk writing** — chunk time-axis decoding is implemented; writing is not.
- **MS-Numpress PIC decoding** (`MS:1002313`) — written by the writer but not decoded by the reader.
- **Writing** of `chunk`/delta layouts, wavelength spectra, and writing Numpress layout from profile arrays.
- **Predicate/page-level pushdown within a row group** — row groups are skipped by min/max statistics, but
  all pages within a selected group are decoded.
- **Detail-level loading** (metadata-only vs. full spectrum in a single open).
- **Full zip64 EOCD** — per-entry zip64 extra fields are handled; a true zip64 EOCD is rejected.
- **Maven Central** publication.

---

## 11. Tooling notes

- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && mvn verify`
- Run an example: `./run-example.sh org.mzpeak.examples.MzPeakFileInfo <path>`
- Inspect a fixture: `./run-example.sh org.mzpeak.cli.MzPeakInfo <path>`
- Re-deriving golden values: `python3` + `pyarrow` on the unpacked Parquet files (see git log for probes)
- Javadoc: `mvn javadoc:javadoc` → `target/reports/apidocs`
