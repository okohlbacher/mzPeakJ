# mzPeakJ — Project Brief

Java bindings (reader-only, minimal first cut) for the **mzPeak** mass-spectrometry data
format. Ported from the Rust reference implementation, aligned with HUPO-PSI mzPeak.
Data structures are designed to convert easily into FragPipe/MSFragger (MSFTBX) structures.

## Goal & scope (MVP)
- **Reader only.** No writing in the first milestone.
- Parse the **unpacked `*.mzpeak` directory** form (plain Parquet on disk). Single-file STORED-ZIP
  random access is **deferred**.
- Support the **`point` layout** (long/tall, one row per peak). `chunk`/numpress/delta layouts deferred.
- Read **spectra only** (MS1 + MSn). Chromatograms / wavelength spectra deferred.
- Surface, per spectrum: `index`, `id`, `msLevel`, retention time, polarity, signal continuity,
  precursor (selected-ion m/z, charge, isolation window), and the `double[]` m/z + `double[]` intensity arrays.

## Key decisions (locked)
- **Parquet backend:** `parquet-java` (org.apache.parquet) **1.17.0**, Hadoop-free path
  (`PlainParquetConfiguration` + custom `InputFile`). Pure JVM — no native libs — so the result can be
  embedded in FragPipe's cross-platform JAR. Reads Parquet via an `InputFile` abstraction (future-proof for
  in-ZIP slicing).
- **Container scope:** unpacked directory only for MVP.
- **Build:** Maven (3.9.15 present). **JDK:** Homebrew `openjdk@25` (25.0.2) —
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25`. Target bytecode level TBD in Phase 0 (consider 17 for FragPipe compat).
- **Intensity stored as `double[]`** (NOT float[]) to match MSFTBX `ISpectrum.getIntensities()` exactly and avoid widening copies.

## Format facts (ground truth = HUPO-PSI repo `doc/index.md` + actual example files; the paper has no schema detail)
mzPeak archive = set of Parquet files + `mzpeak_index.json` manifest. Pin to a specific upstream commit (unstable prototype).

Files (unpacked dir):
- `mzpeak_index.json` — manifest; maps each file to `(entity_type, data_kind)`. **Read first**; dispatch by role, not filename.
- `spectra_metadata.parquet` — "packed parallel tables": top-level struct columns `spectrum`, `scan`, `precursor`, `selected_ion`, joined by integer keys. `spectrum.index` (uint64) is PK; `scan/precursor/selected_ion.source_index` are FKs to it.
- `spectra_data.parquet` — profile signal. `point` struct: `spectrum_index uint64` (first col), `mz double`, `intensity float`.
- `spectra_peaks.parquet` — centroids, same `point` schema (present only if manifest lists `data_kind: "peaks"`).
- `chromatograms_*.parquet` — deferred.

Selected `spectrum` struct fields (column names are CV-inflected):
- `index` uint64 (PK, 0-based, contiguous, time-sorted) · `id` large_string (nativeID; vendor scan no. is embedded here) ·
  `MS_1000511_ms_level` uint8 · `time` double (RT) · `MS_1000465_scan_polarity` int8 ·
  `MS_1000525_spectrum_representation` string (centroid/profile CURIE) ·
  `MS_1003060_number_of_data_points` uint64 · `MS_1003059_number_of_peaks` uint64.
`selected_ion`: `source_index` uint64, `MS_1000744_selected_ion_mz_unit_MS_1000040` double, `MS_1000041_charge_state` int32.
`precursor`: `source_index`, `precursor_index`, `isolation_window{MS_1000827_target_mz, _lower_offset, _upper_offset}`.

Reader mechanics:
- **No built-in scan-number or RT index.** Random access = Parquet row-group/page min-max stats on the sorted
  `spectrum_index` column + binary search for the contiguous block `== i`. Build scan#→index and RT→index maps
  in-memory from the metadata `time`/`id` columns.
- **Type variance:** writers may emit `list`/`large_list`, `string`/`large_string`; m/z as f32 *or* f64, intensity f32/i32/f64.
  Do NOT hardcode physical types — drive off the field's logical type / array-index annotation.

Minimal read paths:
- (a) Spectrum list → parse only `spectra_metadata.parquet`; join `selected_ion`/`precursor` facets on `source_index == spectrum.index`.
- (b) Peaks for spectrum *i* → filter `point.spectrum_index == i` in `spectra_data.parquet` (centroids in `spectra_peaks.parquet`),
  using row-group/page pushdown.

## Reference implementations (Rust) — mirror the 3 layers
- **`mzpeaks`** (https://docs.rs/mzpeaks, github.com/mobiusklein/mzpeaks) — peak primitives + traits:
  `CentroidPeak{mz:f64, intensity:f32, index:u32}`, `DeconvolutedPeak{neutral_mass, intensity, charge:i32, index}`;
  traits `CoordinateLike<T>`, `IndexedCoordinate`, `IntensityMeasurement`, `KnownCharge`, `CentroidLike`; `Tolerance::{PPM,Da}`.
- **`mzdata`** spectrum model (returned by the reader): `MultiLayerSpectrum{description, arrays:BinaryArrayMap, peaks, deconvoluted_peaks}`,
  `SpectrumDescription{id, index, ms_level, polarity, signal_continuity, params, acquisition, precursor}`,
  `ScanEvent{start_time, injection_time, scan_windows, ...}`, `Precursor{ions, isolation_window, activation}`,
  `SelectedIon{mz, intensity, charge}`, `BinaryArrayMap.mzs()/.intensities()`.
- **`mzpeak_prototyping`** (github.com/mobiusklein/mzpeak_prototyping) — the actual ZIP/Parquet reader; returns mzdata types.
  API to mirror: `open(path) → len() → get_spectrum(i)`, plus cheap `get_spectrum_metadata(i)` / `get_spectrum_arrays(i)`.
  Uses Rust `parquet`/`arrow` with `ProjectionMask` (column projection) + `RowFilter` (predicate pushdown) + row-group cache.

## FragPipe interop target — MSFTBX
FragPipe/MSFragger I/O = **MSFTBX** (`com.github.chhh:msftbx`, package `umich.ms.datatypes`, Apache-2.0).
- `IScan`: `int getNum()`, `Integer getMsLevel()`, `Double getRt()`, `Double getIm()`, `Polarity getPolarity()`,
  `Boolean isCentroided()`, `ISpectrum getSpectrum()/fetchSpectrum()`, `PrecursorInfo getPrecursor()`,
  `Double getBasePeakMz()/getBasePeakIntensity()/getTic()`.
- `ISpectrum`: **`double[] getMZs()`**, **`double[] getIntensities()`** (double!), `double getMinMZ()/getMaxMZ()`, binary-search helpers.
  Impl: `SpectrumDefault` (parallel double[] arrays).
- `PrecursorInfo`: `getMzTarget()`, `getMzTargetMono()`, `getCharge()` (nullable Integer), `getIntensity()`,
  isolation window lo/hi, `getParentScanNum()`, `getActivationInfo()`.
→ Design the mzPeakJ model to mirror the IScan(metadata)/ISpectrum(payload) split so a `Spectrum → IScan/ISpectrum`
  adapter is near-mechanical. Keep the `msftbx` dependency isolated in an `org.mzpeak.fragpipe` adapter module.

## Proposed Java model (`mzPeakJ`)
```
org.mzpeak.model    CoordinateLike · IntensityMeasurement · Indexed · KnownCharge (interfaces)
                    CentroidPeak(mz,intensity,index) · DeconvolutedPeak(neutralMass,intensity,charge,index)
                    Spectrum{SpectrumDescription desc; double[] mz; double[] intensity; List<CentroidPeak> peaks}
                    SpectrumDescription{id,index,msLevel,polarity,signalContinuity,scans,precursors}
                    Precursor · SelectedIon · IsolationWindow · ScanEvent · ScanWindow · Tolerance(PPM|Da)
org.mzpeak.io       MzPeakReader.open() · size() · getMetadata(i) · getSpectrum(i) · iterator()
                    MzPeakManifest (mzpeak_index.json) · parquet decoding internals
org.mzpeak.fragpipe MsftbxAdapter: Spectrum → IScan/ISpectrum   (isolates msftbx dep)
```

## Test data
HUPO-PSI repo ships example files: `small.unpacked.mzpeak/`, `small.chunked.mzpeak`, `small.mzpeak`.
Acquire from github.com/HUPO-PSI/mzPeak (pin commit). Use `small.unpacked.mzpeak/` as the primary fixture.

## Roadmap (phases) — codex adversarial review gate at start (review plan/design) AND end (review diff) of each
- **P0 Scaffold:** Maven project, JDK/JAVA_HOME, parquet-java Hadoop-free deps, git init, fetch test fixtures.
- **P1 Data model:** interfaces + value classes (above) + unit tests.
- **P2 Metadata reader:** manifest + `spectra_metadata.parquet` → spectrum list (index/msLevel/RT/precursor). Tests vs small.mzpeak.
- **P3 Array reader:** point-layout `spectra_data`/`spectra_peaks` → double[] m/z+intensity per index, row-group pushdown; `getSpectrum(i)`. Tests.
- **P4 FragPipe adapter:** Spectrum → MSFTBX IScan/ISpectrum + round-trip test.
- **Deferred:** in-ZIP random access, chunked/numpress/delta, chromatograms, RT/scan# indices beyond basic maps, writing.

## Toolchain reminders
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` before any `mvn` call.
- Adversarial review: `codex exec --skip-git-repo-check "<prompt>"` (gpt-5.5, read-only) or `codex exec review` for diffs.
