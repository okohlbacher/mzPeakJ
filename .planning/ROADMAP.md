# Roadmap: mzPeakJ

## Overview

mzPeakJ is a reader-only Java port of the HUPO-PSI mzPeak format (Parquet-based MS data),
designed to round-trip into FragPipe/MSFTBX. The journey runs through layered components:
first a Hadoop-free Maven scaffold with vendored example fixtures, then a pure-data model
mirroring mzpeaks/mzdata, then the manifest + metadata reader (the spine), then the
point-layout peak/array reader with random access and the public API, and finally the
isolated MSFTBX adapter with end-to-end tests on real example data. Each phase tests its
own deliverables against the vendored `small.unpacked.mzpeak/` fixture.

## Process

This is a **library port with horizontal component layers** (model → io → adapter). The
build order is dictated by dependency direction, not by user-facing features.

**Adversarial review gate (user-mandated, applies to EVERY phase — a gate, not a phase):**
- **At phase START:** run a codex CLI design/plan review —
  `codex exec --skip-git-repo-check "<plan/design review prompt>"` (read-only, gpt-5.5).
- **At phase END:** run a codex CLI diff review — `codex exec review`.
- A phase is not complete until the end-of-phase codex review passes **with no unresolved
  HIGH findings**. This appears as the final success criterion on every phase.

**Toolchain:** export `JAVA_HOME=/opt/homebrew/opt/openjdk@25` before any `mvn` call.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

- [x] **Phase 1: Scaffold & Fixtures** - Hadoop-free Maven build on JDK 25 + vendored, commit-pinned example fixtures
- [x] **Phase 2: Data Model** - Pure-data peak primitives + Spectrum model (intensity as `double[]`), zero I/O deps
- [x] **Phase 3: Manifest & Metadata Reader** - Parse `mzpeak_index.json`; read `spectra_metadata.parquet` into a spectrum list with joined precursor facets
- [x] **Phase 4: Peak/Array Reader & API** - Point-layout `double[]` m/z+intensity per index with pushdown random access, behind the public `MzPeakReader` API
- [x] **Phase 5: FragPipe Adapter & Tests** - Isolated `Spectrum → IScan/ISpectrum` MSFTBX adapter + full end-to-end JUnit suite on the fixture

## Phase Details

### Phase 1: Scaffold & Fixtures
**Goal**: A reproducible Maven build on JDK 25 with parquet-java resolved Hadoop-free, plus the HUPO-PSI example data vendored and pinned so every later phase can test against real fixtures.
**Depends on**: Nothing (first phase)
**Requirements**: BUILD-01, BUILD-02
**Success Criteria** (what must be TRUE):
  1. `JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -q verify` builds the project from a clean checkout with no errors.
  2. A test (or `mvn dependency:tree` assertion) confirms no `org.apache.hadoop.*` is on the runtime classpath while parquet-java 1.17.0 still resolves.
  3. The vendored `small.unpacked.mzpeak/` directory (and sibling example fixtures) exist in the repo and are readable from test code via a classpath/relative path.
  4. The upstream HUPO-PSI commit hash the fixtures were taken from is recorded in the repo (e.g. a `FIXTURES.md` / README note).
  5. codex adversarial review passed at start (design) and end (diff) with no unresolved HIGH findings.
**Plans**: TBD

### Phase 2: Data Model
**Goal**: A pure-data, dependency-free in-memory model mirroring mzpeaks/mzdata that downstream readers populate and the FragPipe adapter consumes, with intensity stored as `double[]` to avoid widening copies at the MSFTBX boundary.
**Depends on**: Phase 1
**Requirements**: MODEL-01, MODEL-02
**Success Criteria** (what must be TRUE):
  1. Interfaces `CoordinateLike`, `IntensityMeasurement`, `Indexed`, `KnownCharge` and classes `CentroidPeak(mz, intensity, index)` and `DeconvolutedPeak(neutralMass, intensity, charge, index)` exist and compile in `org.mzpeak.model`.
  2. `Spectrum{description, double[] mz, double[] intensity, List<CentroidPeak> peaks}` and `SpectrumDescription{id, index, msLevel, polarity, signalContinuity, scans, precursors}` exist, with intensity typed as `double[]` (not `float[]`).
  3. Support types `Precursor`, `SelectedIon`, `IsolationWindow`, `ScanEvent`, `ScanWindow`, and `Tolerance(PPM|Da)` exist with the documented fields.
  4. The model module has zero dependency on parquet-java or msftbx (verified by dependency scope / a compile boundary test).
  5. Unit tests construct a `Spectrum` and its peak primitives and assert accessor/round-trip behavior.
  6. codex adversarial review passed at start (design) and end (diff) with no unresolved HIGH findings.
**Plans**: TBD

### Phase 3: Manifest & Metadata Reader
**Goal**: Open an unpacked `*.mzpeak` directory, resolve member Parquet files by manifest role, and decode `spectra_metadata.parquet`'s packed parallel tables into a spectrum list with precursor/selected-ion facets joined by integer key.
**Depends on**: Phase 2
**Requirements**: MAN-01, META-01, META-02
**Success Criteria** (what must be TRUE):
  1. Given `small.unpacked.mzpeak/`, the reader parses `mzpeak_index.json` and resolves the metadata/data/peaks files by `(entity_type, data_kind)` role rather than by hardcoded filename.
  2. Reading the metadata returns the correct spectrum count, with index, id (nativeID), MS level, retention time, polarity, and signal continuity populated for each spectrum (asserted against known fixture values).
  3. For at least one MSn spectrum, precursor selected-ion m/z, charge state, and isolation window are correctly joined on `source_index == spectrum.index`; for MS1 spectra these are null (no row-position misjoin).
  4. codex adversarial review passed at start (design) and end (diff) with no unresolved HIGH findings.
**Plans**: TBD

### Phase 4: Peak/Array Reader & API
**Goal**: Materialize point-layout signal into `double[]` m/z + intensity arrays for a requested spectrum index using Parquet pushdown (no full scans), decoding off logical types, exposed through the complete public `MzPeakReader` API.
**Depends on**: Phase 3
**Requirements**: PEAK-01, PEAK-02, PEAK-03, API-01
**Success Criteria** (what must be TRUE):
  1. `getSpectrum(i)` returns `double[]` m/z and `double[]` intensity arrays of length `number_of_data_points` for a given index, with values matching the fixture for at least one MS1 and one MSn spectrum.
  2. Array decoding is driven off Parquet logical types / array-index annotations (no hardcoded f32/f64), and centroid peaks are read from `spectra_peaks.parquet` when the manifest declares a `peaks` data_kind.
  3. Random access resolves a spectrum's rows via row-group/page min-max stats on `spectrum_index` plus binary search / `RowFilter` — verified to skip row groups rather than scan the whole file.
  4. `MzPeakReader` exposes `open(Path)`, `size()`, `getMetadata(index)`, `getSpectrum(index)`, and `iterator()`, and iterating yields every spectrum in index order.
  5. codex adversarial review passed at start (design) and end (diff) with no unresolved HIGH findings.
**Plans**: TBD

### Phase 5: FragPipe Adapter & Tests
**Goal**: Provide an isolated module that converts a `Spectrum` into MSFTBX `IScan`/`ISpectrum` with no widening copies, and lock in correctness with an end-to-end JUnit suite over the vendored fixture.
**Depends on**: Phase 4
**Requirements**: FRAG-01, TEST-01
**Success Criteria** (what must be TRUE):
  1. An `org.mzpeak.fragpipe` adapter converts a `Spectrum` into `umich.ms.datatypes.scan.IScan` + `umich.ms.datatypes.spectrum.ISpectrum` (`SpectrumDefault`), and the msftbx dependency is confined to this module (core has zero msftbx dep).
  2. The adapter passes the model's `double[]` intensity array into `ISpectrum` with no widening copy (same array reference or verified no float→double conversion).
  3. A JUnit 5 suite over `small.unpacked.mzpeak/` asserts correct spectrum count, metadata (MS level, RT, precursor m/z), and m/z+intensity arrays for at least one MS1 and one MSn spectrum.
  4. An MSFTBX adapter round-trip test confirms `IScan.getMsLevel()/getRt()/getPrecursor()` and `ISpectrum.getMZs()/getIntensities()` match the source `Spectrum`.
  5. codex adversarial review passed at start (design) and end (diff) with no unresolved HIGH findings.
**Plans**: TBD

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 1. Scaffold & Fixtures | 0/TBD | Not started | - |
| 2. Data Model | 0/TBD | Not started | - |
| 3. Manifest & Metadata Reader | 0/TBD | Not started | - |
| 4. Peak/Array Reader & API | 0/TBD | Not started | - |
| 5. FragPipe Adapter & Tests | 0/TBD | Not started | - |
