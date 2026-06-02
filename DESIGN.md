# mzPeakJ — Design & Format Notes

This document captures how the [mzPeak](https://www.psidev.info/mzpeak) format actually works (as observed in
the HUPO-PSI example files) and the decisions behind this Java reader/writer. It's the map for anyone extending
mzPeakJ or porting the format elsewhere.

mzPeakJ is a **demonstrator**, vibe-coded by following the existing mzPeak reference implementations (the Rust
crates `mzpeaks` / `mzdata` / `mzpeak_prototyping` and the HUPO-PSI spec). Ground truth = the upstream
`doc/index.md` **plus the actual example Parquet files** (the published paper has no schema detail). The
format is an unstable prototype; this project is pinned to upstream commit `03ccdb7`
(see `src/test/resources/mzpeak/.../UPSTREAM_COMMIT.txt`).

## 1. What an mzPeak dataset is

A set of Apache Parquet files plus a `mzpeak_index.json` manifest, delivered as **either**:

- an **unpacked directory** `run.mzpeak/`, or
- a **single file** `run.mzpeak` = an **uncompressed (STORED) ZIP** of the same members.

`mzPeakJ` reads both via the `MzPeakSource` abstraction (`DirectorySource` / `ZipSource`). The manifest maps
each member to a role `(entity_type, data_kind)` — **resolve files by role, never by hardcoded filename**:

| entity_type | data_kind | file (conventional) | mzPeakJ store |
|---|---|---|---|
| `spectrum` | `metadata` | `spectra_metadata.parquet` | `SpectrumMetadataDecoder` |
| `spectrum` | `data arrays` | `spectra_data.parquet` (profile) | `SpectrumArrayStore` |
| `spectrum` | `peaks` | `spectra_peaks.parquet` (centroids) | `SpectrumArrayStore` |
| `chromatogram` | `metadata` / `data arrays` | `chromatograms_*.parquet` | `ChromatogramStore` |
| `wavelength spectrum` | `metadata` / `data arrays` | `wavelength_spectra_*.parquet` | `WavelengthSpectrumStore` |

## 2. The metadata "packed parallel tables" trap (most important)

`spectra_metadata.parquet` has **four independent top-level struct columns** — `spectrum`, `scan`,
`precursor`, `selected_ion` — that behave like relational tables joined by integer keys, **not** like fields
of one row. They are **NOT row-aligned**:

- `spectrum.index` (uint64) is the primary key.
- `scan` / `precursor` / `selected_ion` each carry their own `source_index` foreign key pointing back to the
  owning spectrum, and are **densely packed**: the facet at row *r* usually does **not** describe spectrum *r*.
- Placeholder facet rows have a **null `source_index`** and must be skipped.

Concretely in `small.unpacked.mzpeak`: at metadata row 0 (spectrum index 0, an MS1), the `selected_ion`
column holds an entry with `source_index = 2` — i.e. it belongs to spectrum **2**, not 0. Zipping facets by
row position silently mis-assigns precursors.

**Rule (`SpectrumMetadataDecoder`):** build `Map<source_index → facet>` by iterating each facet column,
skipping null `source_index`, then attach to the spectrum with the matching `index`. Never align by row.

## 3. Signal layouts: `point` vs `chunk`

Each signal file's top-level struct is named `point` **or** `chunk`. `SpectrumArrayStore` detects which per
record and groups rows by the (sorted, contiguous) `spectrum_index`.

### point layout
`point: struct<spectrum_index: uint64, mz: double, intensity: float>` — one row per peak/point. Profile data
lives here; centroids in the parallel `spectra_peaks.parquet` with the identical schema.

### chunk layout (delta)
`chunk: struct<spectrum_index, mz_chunk_start: double, mz_chunk_end: double, mz_chunk_values: list<double>,
chunk_encoding: string, intensity: list<float>>` — one row per ~50-m/z chunk.

Decoding a chunk (`decodeChunkMz` + `acceptChunk`):
- m/z values are **delta-encoded** within the chunk: `acc = chunk_start; for v: acc += v; emit acc`
  (unless `chunk_encoding` is the "no compression" CURIE `MS:1003088`, where values are absolute).
- **The chunk_start-as-a-point rule (subtle, learned from the data):** a chunk emits `chunk_start` itself as
  a real point **iff its intensity list is one longer than its m/z values** (`intensity.length == values + 1`).
  In that case `intensity[0]` is the start point's intensity and the decoded values pair with `intensity[1..]`.
  When the lengths are equal, values pair 1:1 with intensity and there is no separate start point. This is
  *not* "first chunk only" — e.g. spectrum 1 in `small.chunked.mzpeak` has a later chunk with the +1.
- Validated: for centroid spectra (no null-marking) the chunk decode is **byte-identical** to the point layout;
  for profile spectra the **total ion signal** matches within 1e-6.

### numpress (supported)
A chunk may carry `mz_numpress_linear_bytes` (transform `MS:1002312`) and `intensity_numpress_slof_bytes`
(`MS:1002314`) instead of plain values. mzPeakJ decodes these with the bundled Apache-2.0
[MS-Numpress](https://github.com/ms-numpress/ms-numpress) Java decoders (`ms.numpress.MSNumpress`). Unlike the
delta path, the Numpress buffers encode the **full absolute arrays directly — no `chunk_start`, no delta, no
null-marking** (verified: the example file stores all 13589 points for spectrum 0), so decode-and-append 1:1
gives the right result without the §4 reconstruction. Numpress is lossy by design (linear m/z is near-lossless
fixed-point; SLOF intensity is log-scaled 16-bit), so values match the point layout only within tolerance.
Numpress **PIC** and *writing* Numpress are not implemented.

## 4. Null-marking & profile reconstruction

Profile spectra drop flanking zero-intensity points by storing them as **null m/z** (null-marking). So the
declared `MS_1003060_number_of_data_points` (e.g. 13589 for spectrum 0) exceeds the stored non-null points
(11213). The canonical reader reconstructs the dropped points from a per-spectrum `mz_delta_model` polynomial.

mzPeakJ reconstructs **by default** (`reconstructProfile=true`) but **approximately**: null m/z are filled by
**linear interpolation** between real anchors (intensity 0). This yields the correct point count (13589,
matching the reference) and exact total signal, but interpolated-point m/z are approximate and not positionally
bit-exact vs. the polynomial model. Pass `reconstructProfile=false` to get only stored non-null points. Exact
`mz_delta_model` reconstruction is the main remaining fidelity gap.

## 5. Hadoop-free Parquet (and the ZSTD problem)

mzPeak columns are **ZSTD-compressed**. parquet-java's default codec factory builds a Hadoop `Configuration`
(pulling the shaded Woodstox/Hadoop runtime) on every codec lookup. To avoid the Hadoop runtime entirely,
mzPeakJ:

- reads/writes via `LocalInputFile` / `ByteArrayInputFile` / `LocalOutputFile` + `PlainParquetConfiguration` +
  the parquet-column `Group` API (`ParquetGroups`), and
- supplies a custom `ZstdCompressionCodecFactory` backed by the self-contained `zstd-jni` jar, so the Hadoop
  `CodecFactory` (and `Configuration`) is **never** touched.

The result never uses a Hadoop FileSystem, `Configuration`, native library, or cluster setup. parquet-java's
classes do *statically reference* Hadoop types, however, so a single self-contained `hadoop-client-api` jar
must be on the classpath (it ships as a normal dependency). "No Hadoop runtime" — not "no Hadoop jar".

## 6. Type-variance discipline

mzPeak writers MAY emit a numeric column as FLOAT or DOUBLE (m/z f32/f64, intensity f32/i32/f64) and lists as
`list`/`large_list`, strings as `string`/`large_string`. **Never assume a physical type.** `ParquetGroups`
reads every numeric leaf through `optDouble`/`optLong`/`doubleList(Nullable)`, dispatching on the actual
`PrimitiveTypeName`, and navigates list structure by field *position* (so `element`/`item` naming is irrelevant).
`spectrum_index` is uint64 but represented as a signed Java `long`; values with the high bit set are rejected.

## 7. Architecture

```
org.mzpeak.model        pure data — no I/O deps
  peaks:    CoordinateLike / IntensityMeasurement / Indexed / KnownCharge; CentroidPeak; DeconvolutedPeak
  spectra:  Spectrum (double[] mz, double[] intensity, List<CentroidPeak> peaks) + SpectrumDescription
            Precursor / SelectedIon / IsolationWindow / ScanEvent / ScanWindow; Polarity / SignalContinuity
  other:    Chromatogram; WavelengthSpectrum; Tolerance(PPM|Da)
org.mzpeak.io           reading
  MzPeakReader          open(dir|zip) · size · getMetadata · getSpectrum · by index/id/scan#/RT · iterator
                        chromatograms() · wavelengthSpectra()
  MzPeakSource          DirectorySource | ZipSource
  decoders/stores       MzPeakManifest · SpectrumMetadataDecoder · SpectrumArrayStore · ChromatogramStore
                        · WavelengthSpectrumStore
  MzPeakWriter          writeDirectory / writeArchive (ZSTD point-layout Parquet + manifest; STORED zip)
  org.mzpeak.io.parquet ParquetGroups (Group API + typed/list accessors) · ZstdCompressionCodecFactory
                        · ByteArrayInputFile
org.mzpeak.cli          MzPeakInfo     (dataset summary)
org.mzpeak.examples     ExtractSpectra · ConvertToDta · ExtractXic · MzPeakFileInfo
```

Intensity is `double[]` throughout (so consumers get arrays directly, no widening copy). Signal files are
read fully and cached on first access — fine at example scale; row-group/page **predicate pushdown** for
large files is future work.

## 8. Testing approach

Tests run against vendored upstream fixtures (`small.unpacked` / `small` / `small.chunked` /
`small.numpress` / `has_uv`). Golden values were extracted independently with **pyarrow**, and the reader is
**cross-validated against the Rust `mzpeak_prototyping` reference test** (spectrum 0 → 13589 points; 5 → 650
peaks; 25 → 789 peaks) across the unpacked / ZIP / chunked variants, plus byte-exact centroid array
equivalence and profile total-signal equivalence across containers. 21 tests; `mvn verify` builds the jar.

## 9. Known limitations / deferred

See README "Scope & limitations" and `.planning/REQUIREMENTS.md`. In short: exact `mz_delta_model`
reconstruction, footer key-value **metadata modeling** (instrument/software/activation — see roadmap),
predicate pushdown/streaming for large files, detail-level (metadata-only) loading, tolerance-based peak
search, multi-precursor selected-ion partitioning, Numpress **PIC**, and writing of `chunk`/Numpress layouts
and wavelength spectra (writing of point-layout spectra + chromatograms is supported).

## 10. Tooling notes

- Build/run needs a JDK; this project used Homebrew `openjdk@25` → `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`.
- `mvn verify` (tests + jar); `mvn javadoc:javadoc` (API docs under `target/reports/apidocs`).
- Inspect a fixture: `java --enable-native-access=ALL-UNNAMED -cp "target/classes:<deps>" org.mzpeak.cli.MzPeakInfo <path>`.
- Re-deriving golden values: `python3` + `pyarrow` on the unpacked Parquet files (see git history for the probes).
