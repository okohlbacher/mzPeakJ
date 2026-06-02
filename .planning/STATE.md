# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-06-01)

**Core value:** Given an unpacked mzPeak dataset, return correct per-spectrum `double[]` m/z + intensity arrays and metadata that round-trip into FragPipe/MSFTBX.
**Current focus:** Phase 1 — Scaffold & Fixtures

## Current Position

Phase: 1 of 5 (Scaffold & Fixtures)
Plan: 0 of TBD in current phase
Status: Ready to plan
Last activity: 2026-06-01 — Roadmap created (5 phases, 12/12 requirements mapped)

Progress: [░░░░░░░░░░] 0%

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
