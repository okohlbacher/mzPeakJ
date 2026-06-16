# mzPeakJ — Gap-Closure Roadmap (write-side fidelity + conformance)

**Reference:** `mobiusklein/mzpeak_prototyping` @ HEAD `29e59b2` (the commit our fixtures pin to).
**Goal:** Make mzPeakJ's *writer* round-trip-lossless against the Rust reference and spec-conformant,
so that any corpus file read by mzPeakJ can be re-written and (a) pass the independent
`mzpeak-validate` validator and (b) re-read identically (forward-backward fidelity).

The **read path is already at parity** (point / delta-chunk / Numpress linear+SLOF+PIC / profile
reconstruction via `mz_delta_model`; random access by index/id/scan/RT). All gaps below are
**write-side or conformance**.

---

## Scope decision

**IN (this roadmap):** Tier 1 + Tier 2 — everything needed for lossless round-trip + validator-clean output.
**OUT (deferred, explicitly beyond a demonstrator):** async/object-store/S3 reading, Parquet predicate
pushdown, bloom filters, modular encryption, 3D-IMS frame restacking, non-ZSTD codecs. These do not
affect correctness of written files and are ecosystem/perf features.

**Open question for reviewers:** is wavelength/UV *write* (B7) in or out? It is needed for true
round-trip of PDA files in the corpus, but the Rust writer treats it as a secondary facet. Proposed:
IN, because corpus contains UV/DAD files and "read but can't write" is a fidelity hole.

---

## Tier 1 — conformance (cheap, high interop value)

### A1 — Format version string
- **Now:** `MzPeakWriter.java:597` writes `"version": "mzpeak-0.9"`.
- **Reference:** writes `"0.9.0"` (`constants.rs::MZPEAK_VERSION`).
- **Change:** write `"0.9.0"`. Reader must continue to accept both old and new strings.
- **Risk:** spec issue `issue-format-version-metadata.md` is open; the validator currently tolerates
  `mzpeak-0.9`. Decision: match the reference literal `0.9.0` since that is what HEAD emits.

### A2 — Full metadata duplicated into `mzpeak_index.json`
- **Now:** `writeManifest` puts only `version` + `cv_list` + `scan_settings_list` + `imaging` into the
  index JSON; the full docs (run, software, instrument_configuration_list, data_processing_method_list,
  sample_list, file_description) live only in the Parquet footer KV.
- **Reference:** commit `29e59b2` duplicates ALL metadata docs into the index `metadata` object.
- **Change:** have `writeManifest` embed the same JSON docs that `FooterMetadataWriter` produces, under
  `metadata`. Single source of truth: reuse `FooterMetadataWriter.serialize()` output, parse each
  value back to a JSON node, attach under the right key.
- **Risk:** double-encoding (string vs object). Must embed as JSON objects, not escaped strings.

### B1 — Derived spectrum columns
- **Now:** writer emits only `number_of_data_points` / `number_of_peaks` in the spectrum facet.
- **Reference:** also writes `lowest_observed_mz` (MS:1000528), `highest_observed_mz` (MS:1000527),
  `base_peak_mz` (MS:1000504), `base_peak_intensity` (MS:1000505), `total_ion_current` (MS:1000285),
  `spectrum_type` (MS:1000559), and (when present) observed IM / wavelength ranges.
- **Change:** compute these from the spectrum arrays at write time and emit as typed columns. Reader
  should surface them (already generically captured if present, but add typed accessors).
- **Risk:** empty spectra (0 points) → null, not 0; base peak of empty spectrum is null. TIC for
  centroid vs profile. Must not fabricate values when arrays are absent.

---

## Tier 2 — round-trip fidelity (currently read-only)

### B2 — Scan windows (write)
- **Now:** decoded on read (`SpectrumMetadataDecoder`), NOT written (`scan` facet has no `scan_windows`).
- **Change:** add `scan_windows` list-of-struct to the scan facet schema and populate from
  `ScanEvent.scanWindows()` (lower MS:1000501 / upper MS:1000500).

### B3 — Dedicated ion-mobility columns (write)
- **Now:** IM written into `scan.parameters` only.
- **Reference:** typed `ion_mobility_value` (double) + `ion_mobility_type` (CURIE string) scan columns.
- **Change:** when a scan has a recognized IM param, emit the typed columns AND keep param fallback for
  unrecognized accessions. Reader already normalizes both.

### B4 — Dedicated imaging position columns (write)
- **Now:** imaging x/y in `scan.parameters` only.
- **Reference:** typed `IMS_1000050_position_x` / `IMS_1000051_position_y` (UInt32) scan columns.
- **Change:** emit typed columns when `ScanEvent.imagingPosition()` present.

### B5 — `scan_settings_list` (write)
- **Now:** writer emits an empty list; imaging raster (pixel grid/size) lost on round-trip.
- **Change:** serialize `ScanSettings` (id, parameters incl. IMS:1000042–47) from the source into the
  index JSON `scan_settings_list`. Reader already parses it.

### B6 — `mz_delta_model` write + delta-chunk layout (A3)
- **Now:** writer emits point or Numpress only; never the reference default `delta:50` chunked layout
  nor a fitted `mz_delta_model` column.
- **Change (scoped):** (i) write the `mz_delta_model` column when the source spectrum carries one
  (pass-through round-trip), so reconstructed profiles re-encode losslessly; (ii) OPTIONAL: a delta-chunk
  writer matching `delta:50`. Proposed: do (i) now (cheap, fidelity-preserving); make (ii) a stretch goal
  only if time permits — point/Numpress output is already validator-legal.
- **Risk:** fitting a fresh delta model (WLS quadratic, intensity-weighted) is complex and easy to get
  numerically wrong; pass-through of an existing model avoids that. Do NOT re-fit.

### B7 — Wavelength/UV write (decision: IN)
- **Now:** read-only.
- **Change:** emit `wavelength_spectra_metadata.parquet` + `wavelength_spectra_data.parquet` when the
  source has wavelength spectra, mirroring the spectrum facets (Nanometer unit, f32 acceptable).

### C1 — ScanSettings model depth
- **Reference:** ScanSettings also has `source_file_refs` and `targets` (inclusion lists).
- **Change:** extend the model + read + write. Low priority; include if cheap.

---

## End-to-end forward-backward test design

**Forward:** corpus `.mzpeak` → mzPeakJ read → mzPeakJ write (each supported layout: point, Numpress;
delta if B6(ii) lands).
**Validate:** run `mzpeak-validate` on every written artifact; fail on any error-level finding.
**Backward:** re-read the written artifact and assert fidelity vs. the in-memory source:
- spectrum count, indices, ms_level, polarity, RT
- per-spectrum m/z + intensity arrays equal (within encoding tolerance: exact for point, Numpress
  fixed-point tolerance for Numpress layout)
- precursor / isolation window / selected ion / activation
- scan windows, ion mobility, imaging position (the newly-written columns)
- chromatograms (TIC/BPC/SRM) time+intensity
- metadata: run, software, instrument config, data processing, samples, source files, scan settings
- the index-JSON metadata block (A2) matches the footer KV

**Corpus:** all 523 files under `~/Claude/mzML2mzPeak/data/**/*.mzpeak`. Cap spectra/file for huge files.
Skip > 256 MB (OOM). Parameterized JUnit, disabled by default, enabled with `-Dmzpeak.roundtrip=true`.

**Issue collection:** harness writes a per-file JSONL report (file, layout, validator findings,
fidelity diffs) so failures across 523 files are aggregated, not one-shot.

---

## Risks / things reviewers should attack

1. **Shared-file coupling:** A1/A2/B1/B2/B3/B4/B5/B6 all edit `MzPeakWriter.java` (schema + row writing)
   and several touch `FooterMetadataWriter` / manifest. Parallelizing edits will conflict. Plan: serialize
   writer edits; parallelize test harness, validator wrapper, model/reader-accessor additions.
2. **Schema evolution vs. existing read tests:** adding columns must not break the 130+ existing tests or
   the existing fixtures' round-trip.
3. **Null vs zero semantics** for derived columns (B1) and empty/chromatogram-only spectra.
4. **Numpress tolerance** in the backward assertion — exact equality will fail for Numpress; need a
   principled tolerance tied to the fixed-point scale.
5. **Validator false negatives:** does `mzpeak-validate` actually check the newly-written columns, or
   will it pass files that are subtly wrong? The backward re-read assertion is the real gate.
6. **Performance:** 523 files × read+write+validate+reread could be very slow; need caps + parallelism
   in the harness, and it must stay opt-in for normal CI.
7. **`spectrum_reference` / cross-references** when filtering/subsetting — not in scope here but ensure
   we don't emit dangling refs.
