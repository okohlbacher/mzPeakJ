# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-01)

**Core value:** Given an unpacked mzPeak dataset, return correct per-spectrum `double[]` m/z + intensity arrays and metadata that round-trip into FragPipe/MSFTBX.
**Current focus:** Milestone 1 prototype COMPLETE — all 5 phases delivered.

## Current Position

Phase: FROZEN & documented (reference-prototype exploration goal met)
Status: 21 JUnit tests green; `mvn verify` builds the jar; DESIGN.md written; scope frozen.
Last activity: 2026-06-01 — Added UV/wavelength spectra + multi-chromatogram coverage; wrote DESIGN.md;
froze scope per "reference prototype" goal.

Progress: [██████████] reads every container, layout, and entity type in the example data except Numpress

### What was built
- `mvn verify` green (JDK 25 / `JAVA_HOME=/opt/homebrew/opt/openjdk@25`).
- Hadoop-free Parquet read (LocalInputFile + PlainParquetConfiguration + custom zstd-jni codec factory);
  no `org.apache.hadoop` at compile/runtime scope.
- `org.mzpeak.model` (peaks + Spectrum/SpectrumDescription/Precursor/Tolerance, intensity `double[]`).
- `org.mzpeak.io` (MzPeakReader, MzPeakManifest, SpectrumMetadataDecoder with source_index facet joins,
  PointArrayStore) + `org.mzpeak.fragpipe.MsftbxAdapter`.
- 8 tests on `small.unpacked.mzpeak`: spectrum count/MS levels, MS1 profile arrays, MS2 centroid + precursor
  (facet-join correctness), iteration, manifest roles, MSFTBX round-trip (zero-copy).
- codex design review (pre-build) and adversarial review (post-build) both run; HIGH/MED findings fixed
  or documented (see README "Scope & limitations").

### Deferred backlog (autonomous extension)
Delivered: single-file STORED-ZIP container (MzPeakSource/ZipSource/ByteArrayInputFile), delta-`chunk`
layout (validated byte-exact vs point layout for centroids; total-signal-exact for profiles), chromatograms,
lookups by scan#/native-id/nearest-RT, approximate profile reconstruction (count matches reference 13589),
CLI (org.mzpeak.cli.MzPeakInfo), Javadoc. Cross-validated against the Rust mzpeak_prototyping test oracle
(spectrum 0=13589 pts, 5=650 peaks, 25=789 peaks) across unpacked/ZIP/chunked fixtures.
Still deferred (see REQUIREMENTS.md): MS-Numpress decoding (clear error + test), exact mz_delta_model
polynomial reconstruction, wavelength/UV spectra, predicate pushdown/streaming, writing.

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: — min
- Total execution time: 0.0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
|-------|-------|-------|----------|
| - | - | - | - |

**Recent Trend:**
- Last 5 plans: none yet
- Trend: —

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- [Setup]: Parquet backend = parquet-java 1.17.0, Hadoop-free, pure JVM (FragPipe-embeddable).
- [Setup]: Scope = unpacked directory + `point` layout only; ZIP/chunk/numpress deferred.
- [Setup]: Intensity stored as `double[]` throughout to match MSFTBX `ISpectrum` zero-copy.
- [Setup]: 3-layer model (model → io → fragpipe); msftbx isolated in adapter module.
- [Process]: codex CLI adversarial review is a mandatory gate at the start and end of every phase.

### Pending Todos

None yet.

### Blockers/Concerns

- JDK is not on PATH; export `JAVA_HOME=/opt/homebrew/opt/openjdk@25` before any `mvn` call.
- mzPeak is an unstable prototype — fixtures must be vendored and the upstream commit pinned (Phase 1).

## Deferred Items

Items acknowledged and carried forward:

| Category | Item | Status | Deferred At |
|----------|------|--------|-------------|
| Lookup | Scan-number / RT → index maps beyond basic | Deferred (v2) | Init |
| Container | Single-file STORED-ZIP random access | Deferred (v2) | Init |
| Layout | chunk layout + MS-Numpress / delta arrays | Deferred (v2) | Init |
| Data | Chromatograms / wavelength spectra | Deferred (v2) | Init |
| Data | Profile reconstruction (mz_delta_model) | Deferred (v2) | Init |
| Scope | Writing mzPeak files | Out of scope (M1) | Init |

## Session Continuity

Last session: 2026-06-01 18:14
Stopped at: ROADMAP.md and STATE.md created; REQUIREMENTS.md traceability filled.
Resume file: None
