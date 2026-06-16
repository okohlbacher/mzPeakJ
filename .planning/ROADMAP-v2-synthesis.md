# mzPeakJ Gap-Closure — Synthesized Roadmap v2 (post codex + vibe review)

Reviews: codex-cli 0.137.0 + vibe 2.14.1, both adversarial, against ground-truth reference
fixture schemas (`src/test/resources/mzpeak/*.mzpeak`, pinned to Rust HEAD `29e59b2`) and the
independent `mzpeak-validate` profile `mzpeak-0.9`.

## Reframing (what actually matters)

The validator's **hard bar is tiny**: only `spectrum.index` + `scan.source_index` are required columns
(`schema/tables/spectra_metadata.columns.json`); the current writer already passes it. So the objective is
**not** "validator-clean" — it is **eliminate write-side data loss** vs the reference's richer schema, and
stay validator-clean (with one new hard constraint: imaging archives MUST carry typed `position_x/y`).

Both reviewers independently confirmed the roadmap's biggest miss: **`MzPeakWriter` drops `precursor.activation`
entirely** and writes only the first scan/precursor/selected-ion. That is real MSn data loss.

## Corrections to roadmap v1
- **A2 — DROP.** `writeManifest` (MzPeakWriter:609) already embeds all footer docs into the index `metadata`.
  Real residual: it double-serializes and cannot carry `scan_settings_list` (see B5).
- **B6 — RESCOPE honestly.** Default read reconstructs/normalizes null-marked profile points, so writing the
  `mz_delta_model` coefficients does NOT reproduce the original sparse layout. Sparse/null-layout fidelity is
  **OUT**. Fidelity target = the arrays the reader surfaces (reconstructed). We still pass the model column
  through when present (cheap parity) but do not claim lossless re-encode of the null layout.
- **A3 (delta-chunk default write) — OUT.** Point/Numpress output is validator-legal and reader-faithful.

## Scope IN (implement)

### Conformance
- **A1** version string `mzpeak-0.9` → **`0.9.0`**; reader still accepts both.

### Writer data-loss fixes (the core)
- **W1 Activation** — emit `precursor.activation.parameters` (dissociation method + collision energy). *(new; #1 priority)*
- **W2 Scan windows** — emit `scan.scan_windows` list-of-struct (lower MS:1000501 / upper MS:1000500 + params).
- **W3 Typed IM columns** — emit `ion_mobility_value`(double)+`ion_mobility_type`(string) on **both** `scan` and
  `selected_ion`; **mask** the promoted IM param out of that struct's `parameters` to avoid re-read duplication.
- **W4 Typed imaging columns** — emit `scan.IMS_1000050_position_x`/`IMS_1000051_position_y` (uint32) when present;
  mask promoted params. **Required** for validator-clean imaging round-trips (imaging.rules: 1-based, must exist).
- **W5 Facet routing fix** — choose `spectra_data` vs `spectra_peaks` by **payload presence** (has profile arrays /
  has peak list), not solely `signalContinuity == CENTROID`. Handles profile+peaks.
- **W6 selected_ion.precursor_index** + **scan.scan_index** (running ordinal; = source_index acceptable for 1 scan/spectrum).
- **W7 isolation_window.parameters** (completeness; usually empty).

### Derived spectrum columns (B1) — explicit derivation table
Columns: `MS_1000559_spectrum_type` (string), `MS_1000528_lowest_observed_mz`, `MS_1000527_highest_observed_mz`
(double), `MS_1000504_base_peak_mz` (double), `MS_1000505_base_peak_intensity` (float), `MS_1000285_total_ion_current` (float).

| spectrum state | observed mz lo/hi | base_peak_mz / intensity | TIC | spectrum_type |
|---|---|---|---|---|
| has arrays/peaks (n>0) | min/max of mz | argmax-intensity mz / max intensity | Σ intensity | `MS:1000294` mass spectrum (or representation-derived) |
| empty (n==0) | **omit (null)** | **omit (null)** | **omit (null)** | still emit type |
| profile | over `mz[]`/`intensity[]` | over `intensity[]` | Σ `intensity[]` | — |
| centroid | over `peaks` | over `peaks` | Σ `peaks.intensity` | — |

Match Rust: observed-range + TIC appended when data present; base peak optional. Null (omit column) for empty.

### Carrier + facets
- **B5 scan_settings_list (write)** + **carrier**: introduce **`MzPeakDataset`** (record: spectra, chromatograms,
  wavelengthSpectra, fileMetadata, scanSettings, perSpectrum deltaModels). `MzPeakReader.dataset()` builds it;
  `MzPeakWriter.write*(path, dataset)` consumes it. This is the round-trip carrier codex asked for and makes the
  forward-backward test one line. Existing overloads stay for back-compat.
- **B7 Wavelength write** — emit `wavelength_spectra_metadata.parquet` + `wavelength_spectra_data.parquet`
  (mirror spectrum facets; keep `double` for fidelity) + manifest entries, when dataset carries wavelength spectra.
- **mz_delta_model column** pass-through (carry coefficients on `SpectrumDescription`, write when present).
- **data_processing_ref** — write run's default_data_processing_id (cheap parity; optional column).

### Chromatogram richness (corpus is MRM-heavy → SRM precursor matters)
- Add `MS_1000465_scan_polarity`, `parameters`, and **precursor/selected_ion** structs to chromatogram metadata,
  and `ms_level` to chromatogram data. Carry on the `Chromatogram` model. *(W8)*

## Scope OUT (deferred — documented, not silently dropped)
- `auxiliary_arrays` / `number_of_auxiliary_arrays` (overflow non-primary arrays) — rare in corpus, complex.
- Multi-scan / multi-precursor packed rows (DIA multiplex) — writer emits the primary; documented limitation.
- Delta-chunk write layout; sparse/null-layout fidelity for `mz_delta_model`.
- Parquet narrow primitive widths (uint8 ms_level, float intensity): keep current widths; validator accepts.

## Forward-backward E2E test (corpus = 523 files under ~/Claude/mzML2mzPeak/data/**/*.mzpeak)
1. **Forward:** read file → `dataset()` → write each layout (point; numpress) to temp.
2. **Validate:** run `mzpeak-validate` (FULL, not --quick, at least once per layout class) on each written artifact;
   any error-level finding fails the file. Parse `level`/`ruleId` JSON.
3. **Backward:** re-read written artifact; assert per-spectrum: index, id, ms_level, polarity, RT, continuity,
   m/z+intensity arrays (EXACT for point; Numpress **oracle tolerance** = re-encode/decode the same source through
   MSNumpress and compare to that, not a hand-picked epsilon), peaks, precursor incl. **activation**, isolation
   window, selected ion(s) incl. IM, scan windows, ion mobility, imaging position; chromatograms; wavelength spectra;
   file metadata (run/software/instrument/dp/sample/source files); scan settings; index-JSON metadata == footer KV.
4. **Issue collection:** per-file JSONL report (file, layout, validator findings, fidelity diffs) → aggregate, so a
   523-file sweep reports ALL failures, not the first. Opt-in `-Dmzpeak.roundtrip=true`; `-Dmzpeak.roundtrip.maxFiles=N`;
   skip >256 MB; parallelizable.

## Implementation order (per reviewers; serialize MzPeakWriter edits)
1. Carrier: `MzPeakDataset` + `MzPeakReader.dataset()` + attach deltaModel + FileMetadata/dataset scanSettings.
2. A1 version; manifest scan_settings from dataset.
3. Schema expand: activation, scan_windows, typed IM (scan+ion), typed imaging, scan_index, selected_ion.precursor_index,
   isolation_window.parameters, derived columns, mz_delta_model, data_processing_ref.
4. W5 facet routing by payload.
5. Derived-column computation (null table above) + param masking (W3/W4).
6. Wavelength write (B7) + chromatogram richness (W8).
7. Forward-backward corpus harness with explicit per-column assertions + Numpress oracle.
8. Execute corpus sweep, collect issues, fix; final codex+vibe review of the diff.
