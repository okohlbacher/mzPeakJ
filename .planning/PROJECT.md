# mzPeakJ

## What This Is

mzPeakJ is a Java library that **reads** the [mzPeak](https://www.psidev.info/mzpeak) mass-spectrometry
data format — a HUPO-PSI prototype format built on Apache Parquet. It is a port of the Rust reference
implementation (`mzpeaks` / `mzdata` / `mzpeak_prototyping` by mobiusklein) and is designed so its in-memory
data structures convert near-mechanically into [FragPipe](https://github.com/Nesvilab/FragPipe)/MSFragger's
I/O layer (MSFTBX). The first milestone is reader-only and minimal: open an unpacked `*.mzpeak` directory,
list spectra, and materialize per-spectrum m/z + intensity arrays.

## Core Value

Given an unpacked mzPeak dataset, return correct per-spectrum **m/z + intensity arrays** and spectrum
metadata (index, MS level, RT, precursor) that round-trip into FragPipe/MSFTBX structures. If everything
else fails, reading a spectrum's peaks correctly must work.

## Requirements

### Validated

(None yet — ship to validate)

### Active

- [ ] Parse `mzpeak_index.json` manifest and dispatch files by `(entity_type, data_kind)` role
- [ ] Read `spectra_metadata.parquet` into a spectrum list with index, id, MS level, retention time, polarity, signal continuity
- [ ] Join `precursor` / `selected_ion` facets to expose precursor m/z, charge, isolation window for MSn
- [ ] Read `point`-layout `spectra_data.parquet` (and `spectra_peaks.parquet`) into `double[]` m/z + `double[]` intensity for a given spectrum index
- [ ] Use Parquet row-group/page statistics + binary search on `spectrum_index` for random access by index
- [ ] Public reader API: `open(path)` · `size()` · `getMetadata(i)` · `getSpectrum(i)` · `iterator()`
- [ ] In-memory model (interfaces + value classes) mirroring mzpeaks/mzdata, convertible to MSFTBX
- [ ] FragPipe adapter: `Spectrum → umich.ms.datatypes.IScan/ISpectrum` (msftbx dependency isolated)
- [ ] Build in-memory scan-number→index and RT→index maps from the metadata table
- [ ] JUnit tests driven by HUPO-PSI example data (`small.unpacked.mzpeak/`)

### Out of Scope

- Writing mzPeak files — milestone 1 is reader-only
- Single-file STORED-ZIP `.mzpeak` random access — deferred; unpacked directory only for v1
- `chunk` layout + MS-Numpress / delta-encoded arrays — `point` layout only for v1
- Chromatograms / wavelength (UV/DAD) spectra — deferred
- Profile reconstruction (null-marking / zero-run fill via `mz_delta_model`) — deferred; read stored points as-is
- Native dependencies (DuckDB/Arrow JNI) — rejected to keep FragPipe-embeddable (pure JVM only)

## Context

- **mzPeak is an unstable prototype** ("no stability guaranteed"). Ground truth is the HUPO-PSI repo's
  `doc/index.md` + the actual example Parquet files — the published paper (PMC12604042) has no schema detail.
  Pin to a specific upstream commit.
- **Format shape:** an mzPeak archive = a set of Parquet files + a `mzpeak_index.json` manifest, either
  unpacked in a directory or zipped (STORED). Metadata uses "packed parallel tables" (`spectrum`/`scan`/
  `precursor`/`selected_ion` struct columns joined by integer keys; `spectrum.index` is PK). Signal lives in
  long/tall `point` rows (`spectrum_index, mz, intensity`). There is **no built-in scan-number or RT index** —
  random access relies on Parquet row-group/page min-max stats on the sorted `spectrum_index` column.
- **Type variance:** writers may emit `list`/`large_list`, `string`/`large_string`; m/z as f32 or f64;
  intensity f32/i32/f64. The reader must drive decoding off logical types / array-index annotations, not assume physical types.
- **Reference mapping (3 layers):** `mzpeaks` (peak primitives + traits: CentroidPeak, DeconvolutedPeak,
  CoordinateLike, IntensityMeasurement, KnownCharge, Tolerance) → `mzdata` (SpectrumDescription, ScanEvent,
  Precursor, SelectedIon, BinaryArrayMap) → `mzpeak_prototyping` (the ZIP/Parquet reader; API `open → len →
  get_spectrum(i)` with ProjectionMask column projection + RowFilter predicate pushdown).
- **FragPipe target = MSFTBX** (`com.github.chhh:msftbx`, package `umich.ms.datatypes`, Apache-2.0):
  `IScan` (metadata: getNum/getMsLevel/getRt/getPrecursor) + `ISpectrum` (payload: **`double[] getMZs()`,
  `double[] getIntensities()`** — both double). Mirror the IScan/ISpectrum split so the adapter is mechanical.
- Full research + schema detail captured in `PROJECT-BRIEF.md` at the repo root.
- **Environment:** no JDK on PATH; use Homebrew `openjdk@25` (25.0.2) via
  `JAVA_HOME=/opt/homebrew/opt/openjdk@25`. Maven 3.9.15 present. `codex` CLI 0.128.0 (gpt-5.5) present.

## Constraints

- **Tech stack**: Java (Maven build), Parquet via `parquet-java` (org.apache.parquet) 1.17.0 Hadoop-free — Why: pure-JVM, no native libs, embeddable in FragPipe's cross-platform JAR; `InputFile` abstraction future-proofs in-ZIP reads.
- **Compatibility**: in-memory model must convert to MSFTBX `IScan`/`ISpectrum` with no widening copies — Why: FragPipe interop is a primary goal; store intensity as `double[]`.
- **Process**: run a `codex` CLI adversarial review at the **start** (plan/design) and **end** (diff) of every phase — Why: user-mandated external quality gate. `codex exec --skip-git-repo-check "<prompt>"` / `codex exec review`.
- **Testing**: tests must use the existing HUPO-PSI example `.mzpeak` data — Why: validate against real fixtures, not synthetic.
- **Dependencies**: avoid Hadoop transitive bloat and all native code — Why: keep the artifact lean and portable.

## Key Decisions

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Parquet backend = parquet-java 1.17.0 (Hadoop-free, pure JVM) | FragPipe-embeddable, no native libs, InputFile abstraction supports future in-ZIP reads | — Pending |
| Scope = unpacked directory + `point` layout only | Smallest correct slice; defer ZIP/chunk/numpress complexity | — Pending |
| Intensity stored as `double[]` (not float[]) | Matches MSFTBX `ISpectrum.getIntensities()` exactly; zero-copy adapter | — Pending |
| Mirror mzpeaks/mzdata 3-layer model; isolate msftbx in an adapter module | Faithful port + keep FragPipe dep optional/contained | — Pending |
| codex CLI as external adversarial review gate at each phase boundary | User-mandated; independent second-AI critique improves quality | — Pending |
| Build = Maven, JDK = Homebrew openjdk@25 | Maven already present; only JDK available is brew openjdk@25 | — Pending |

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd:complete-milestone`):
1. Full review of all sections
2. Core Value check — still the right priority?
3. Audit Out of Scope — reasons still valid?
4. Update Context with current state

---
*Last updated: 2026-06-01 after initialization*
