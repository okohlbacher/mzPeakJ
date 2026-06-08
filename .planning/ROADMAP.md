# Roadmap: mzPeakJ

## Overview

mzPeakJ is a pure-JVM Java reader and writer for the HUPO-PSI mzPeak format (Parquet-based
mass-spectrometry data). It reads unpacked `*.mzpeak/` directories and single-file STORED ZIPs;
handles the `point`, delta-`chunk`, and MS-Numpress `chunk` layouts; exposes spectra, chromatograms,
UV/DAD wavelength spectra, and file/run metadata; and writes back in point or Numpress chunk
layout. Built for exploration and cross-validation against the Rust/Python/C# reference
implementations; not a hardened production library.

**Current version: 0.10.2** (123 tests, CI on JDK 17 + 21, MIT-licensed, GitHub Pages published).

## Process

**Adversarial review gate (applies to EVERY phase):**
- At phase START: codex design/plan review — `codex exec --skip-git-repo-check "<prompt>"`
- At phase END: codex diff review — `codex exec review`
- A phase is not complete until the end-of-phase codex review passes with no unresolved HIGH findings.

**Toolchain:** `export JAVA_HOME=/opt/homebrew/opt/openjdk@25` before any `mvn` call.

---

## Completed Phases (Milestones 1 & 2)

| Phase | Description | Status |
|---|---|---|
| 1 | Scaffold & Fixtures — Hadoop-free Maven + vendored HUPO-PSI fixtures | ✅ |
| 2 | Data Model — pure-data model (Spectrum, CentroidPeak, Precursor, …) | ✅ |
| 3 | Manifest & Metadata Reader — mzpeak_index.json + spectra_metadata.parquet | ✅ |
| 4 | Peak/Array Reader & API — point-layout double[] decoding + MzPeakReader | ✅ |
| 5 | (FragPipe adapter — removed; scope reduced to library-only) | ✅ |
| M2.1 | Single-file STORED ZIP support (in-place FileSliceInputFile reads) | ✅ |
| M2.2 | Chunk layout decoding (delta + MS-Numpress linear/SLOF/PIC) | ✅ |
| M2.3 | Profile reconstruction (mz_delta_model polynomial + interpolation) | ✅ |
| M2.4 | Chromatogram + UV/wavelength spectra decoding | ✅ |
| M2.5 | mzPeak writer (point + Numpress chunk; directory + ZIP) | ✅ |
| M2.6 | File/run metadata (footer KV JSON read + write) | ✅ |
| M2.7 | Activation params, tolerance-based peak search, scan-number lookup | ✅ |
| M2.8 | Streaming row-group reader + cross-validated test suite (123 tests) | ✅ |
| M2.9 | Shaded jar + CLI examples (MzPeakFileInfo, ExtractSpectra, ConvertToDta, ExtractXic) | ✅ |
| M2.10 | GitHub Pages docs + CI + MIT license | ✅ |

---

## Milestone 3: Upstream Conformance, Imaging, and Ion Mobility

**Motivation:** Three significant upstream changes since our last sync (HUPO-PSI/mzPeak,
as of June 2026):

1. **Centroid/profile split** (commit 03ccdb75, June 2026) — the spec now MANDATES that ALL
   centroid spectra go to `spectra_peaks.parquet`; `spectra_data.parquet` is profile-ONLY.
   Writers must split correctly; readers must route via `MS_1003059_number_of_peaks`.

2. **Imaging / MSI support** (commit 8435967b, June 2026) — two new UInt32 columns in the
   `scan` sub-table (`IMS_1000050_position_x`, `IMS_1000051_position_y`) for pixel coordinates;
   `scan_settings` JSON in Parquet footer KV for pixel raster metadata (ScanSettings concept).

3. **Ion mobility columns** (column name confirmed: `ion_mobility_value` + `ion_mobility_type`,
   commit 4843d885) — these are present in the `scan` and `selected_ion` structs; reading them
   requires extending the model and the metadata decoder.

Additionally, several upstream robustness improvements were observed: chunk decoding now works
with non-large-list variants; NaN charge states coerce to 0; fallback encoding loop nesting was
fixed in the writer.

### Upstream change reference

| Commit | Date | Change | Priority |
|---|---|---|---|
| `03ccdb75` | 2026-06-01 | All centroids → spectra_peaks.parquet (MANDATORY) | P0 |
| `8435967b` | 2026-06-06 | imzML / imaging support: position x/y columns + scan_settings | P1 |
| `4843d885` | 2026-06-06 | Confirm `ion_mobility_value` column name (was `ion_mobility`) | P1 |
| `573e1344` | 2026-04-20 | Chunk reader compatible with non-large_list | P2 |
| `52911c78` | 2026-04-26 | Fallback encoding loop nesting fix (writer-side) | P2 |
| `d1aaaf84` | 2026-06-03 | Fix source of NaN in writer | P3 |

---

### Phase M3.1 — Centroid/Profile Separation (Spec Conformance)

**Priority: P0 — spec MUST; affects write correctness**

**Goal:** mzPeakJ's writer produces spec-conformant output where all centroid spectra land in
`spectra_peaks.parquet` (not `spectra_data.parquet`). The reader routes correctly based on the
`MS_1003059_number_of_peaks` column in spectra_metadata.parquet.

**Context:**
- `spectra_data.parquet` = profile signal ONLY
- `spectra_peaks.parquet` = all centroid peaks (pure centroid spectra AND secondary centroid peaks
  for profile spectra); entity index column is `spectrum_index`
- `spectrum.MS_1003059_number_of_peaks` in spectra_metadata.parquet: count of centroid peaks for
  the given spectrum; MUST be written when centroid peaks exist
- `spectrum.MS_1003060_number_of_data_points`: count of profile data points; existing column

**Current state:** The reader already reads `spectra_peaks.parquet` as "secondary peaks" for
profile spectra. The writer always writes all spectra to `spectra_data.parquet` regardless of
signal continuity; `MS_1003059_number_of_peaks` is not written.

**Tasks:**

1. **Writer: route by signal continuity**
   - `MzPeakWriter.writeDirectory/writeArchive`: detect `SignalContinuity.CENTROID` → write to
     peaks file, not data file
   - Ditto for Numpress writer (centroid Numpress is unlikely in practice but must not crash)
   - Update `mzpeak_index.json` manifest generation to include the peaks entry unconditionally
   - Write `MS_1003059_number_of_peaks` to the spectrum sub-table in spectra_metadata.parquet
     for any spectrum whose peaks are written to the peaks file

2. **Metadata writer: add MS_1003059_number_of_peaks column**
   - Extend `MzPeakWriter`'s spectrum metadata schema to include
     `MS_1003059_number_of_peaks` (INT64, nullable)
   - Populate it from `Spectrum.peaks().size()` when the spectrum is centroid

3. **Reader: routing via MS_1003059_number_of_peaks**
   - `SpectrumMetadataDecoder`: read `MS_1003059_number_of_peaks` per spectrum into
     `SpectrumDescription` (new field: `int peakCount`, -1 = not written)
   - `SpectrumArrayStore.load()`: when `peakCount > 0` and `pointCount == 0` (or vice versa),
     route array loading to the correct file; currently it reads peaks from the peaks file but
     may load empty arrays from the data file for pure centroid spectra from new fixtures

4. **Tests: round-trip with centroid spectra**
   - `MzPeakWriterTest`: add a round-trip test that writes mixed profile+centroid spectra, reads
     back, asserts profile arrays from data file and centroid peaks from peaks file
   - Update `CrossImplTest.spectrum4_837CentroidPoints`: verify that centroid MS2 spectra in
     updated fixtures are still correctly decoded (when HUPO-PSI updates their example files)

**Success Criteria:**
1. A round-trip write+read with a centroid spectrum produces correct peaks from the peaks file and
   an empty / absent data entry in the data file.
2. `MzPeakManifest` always reports both data-arrays and peaks entries.
3. `MS_1003059_number_of_peaks` is populated in written metadata; reader exposes it on
   `SpectrumDescription.peakCount()`.
4. All 123 existing tests continue to pass.
5. Codex adversarial review passes at start and end with no HIGH findings.

---

### Phase M3.2 — Ion Mobility Reading

**Priority: P1 — new data present in real fixtures (e.g. Bruker TDF-derived files)**

**Goal:** Read `ion_mobility_value` (float64) and `ion_mobility_type` (CURIE string) from the
`scan` and `selected_ion` sub-tables in `spectra_metadata.parquet`.

**Context:**
- Column names are `"ion_mobility_value"` (float64) and `"ion_mobility_type"` (CURIE Utf8) — these
  are LITERAL strings, not constants in the Rust `constants.rs`
- Present in both the `scan` struct and the `selected_ion` struct
- `ion_mobility_type` is a child of MS:1002892 (ion mobility separation type)
- No ion mobility test fixture is currently vendored; this phase may be implemented against a
  synthetic round-trip test unless a suitable fixture can be sourced

**Tasks:**

1. **Model: add ion mobility to ScanEvent**
   - `ScanEvent`: add `OptionalDouble ionMobilityValue()` and `String ionMobilityType()` (nullable)

2. **Model: add ion mobility to SelectedIon**
   - `SelectedIon`: add `OptionalDouble ionMobilityValue()` and `String ionMobilityType()` (nullable)

3. **Reader: decode from scan sub-table**
   - `SpectrumMetadataDecoder`: extend scan-row reader to check for `ion_mobility_value` and
     `ion_mobility_type` columns (use `ParquetGroups.optDouble(group, "ion_mobility_value")`);
     guard with `has()` check so files without IM columns don't fail

4. **Reader: decode from selected_ion sub-table**
   - Extend selected-ion row reader similarly

5. **Writer: write ion mobility if present**
   - If `ScanEvent.ionMobilityValue()` is present, include `ion_mobility_value` and
     `ion_mobility_type` columns in the scan metadata schema and populate them

6. **Tests:**
   - `SpectrumParamsTest` or a new `IonMobilityTest`: writer round-trip — create a spectrum with
     a scan event carrying ion mobility, write, read back, assert values preserved

**Success Criteria:**
1. `ScanEvent.ionMobilityValue()` returns the correct value when present in fixture.
2. Round-trip write+read preserves ion mobility value and type.
3. Files without ion mobility columns load without error.
4. Codex adversarial review passes.

---

### Phase M3.3 — Imaging (MSI) Support

**Priority: P1 — new data kind; first-class in HUPO-PSI toolchain as of June 2026**

**Goal:** Read pixel coordinates (position x/y) from imaging mzPeak files and expose them on
`ScanEvent`. Parse `scan_settings` from the Parquet footer. Write imaging mzPeak files from
spectra that carry pixel coordinates.

**Context — column details (from Rust `writer/visitor.rs`):**
- `IMS:1000050` → column name `IMS_1000050_position_x` (UInt32) in scan struct extra fields
- `IMS:1000051` → column name `IMS_1000051_position_y` (UInt32) in scan struct extra fields
- These are NOT in the `parameters` list (they are promoted to dedicated columns; `associated_curie_to_skip()` removes them from params)
- They are EXTRA fields appended after the standard scan columns — the Parquet schema will have them only in imaging files
- `scan_settings` footer KV key: JSON-serialized `ScanSettings` struct (not yet formally in spec)
  containing pixel grid dimensions, pixel size, raster direction
- The spec does NOT yet document imaging; this is an implementation-leading-spec situation

**Context — test fixture:**
- `HUPO-PSI/mzPeak` has `test/data/imaging/Example_Processed.imzML + .ibd` (3×3 pixel grid,
  9 spectra, processed mode, from pyimzML). The Rust `Justfile` converts it to
  `Example_Processed.img.mzpeak`. We should produce this fixture using the Rust CLI and vendor it
  (or write a synthetic 3×3 fixture directly from Java).

**Tasks:**

1. **Model: ImagingPosition**
   ```java
   public record ImagingPosition(int x, int y) {}
   ```
   Add to `org.mzpeak.model`.

2. **Model: ScanSettings**
   ```java
   public record ScanSettings(
       String id,
       Integer maxPixelX,  // IMS:1000042
       Integer maxPixelY,  // IMS:1000043
       Double pixelSizeX,  // IMS:1000046 (µm)
       Double pixelSizeY,  // IMS:1000047 (µm)
       List<Param> parameters
   ) {}
   ```
   Add to `org.mzpeak.model.meta`.

3. **Model: extend ScanEvent**
   - Add `ImagingPosition imagingPosition()` (nullable) to `ScanEvent`

4. **Reader: decode position x/y from scan struct**
   - `SpectrumMetadataDecoder`: after reading the standard scan fields, use
     `ParquetGroups.optInt(scanGroup, "IMS_1000050_position_x")` and
     `"IMS_1000051_position_y"` (guarded with `has()`) to populate `ImagingPosition`

5. **Reader: parse scan_settings from footer**
   - `FooterMetadataReader`: add handling for key `"scan_settings"` → parse JSON array →
     `List<ScanSettings>`; add to `FileMetadata` (new field)
   - `FileMetadata`: add `List<ScanSettings> scanSettingsList()`
   - `MzPeakReader.fileMetadata()` already returns `FileMetadata`; no new public API needed
     beyond the new field

6. **Writer: write position x/y columns**
   - `MzPeakWriter`: if a `ScanEvent` carries non-null `ImagingPosition`, include
     `IMS_1000050_position_x` (INT32, unsigned) and `IMS_1000051_position_y` (INT32, unsigned)
     in the scan struct schema; populate from the position
   - This requires making the scan metadata schema dynamic (or always including the columns with
     null values for non-imaging files — prefer dynamic schema to avoid bloat)

7. **Writer: write scan_settings to footer**
   - `FooterMetadataWriter`: serialize `List<ScanSettings>` → `"scan_settings"` JSON key

8. **Fixture and tests:**
   - Either run the Rust CLI to produce `Example_Processed.img.mzpeak` and vendor it under
     `src/test/resources/mzpeak/`, OR generate a synthetic 3×3 pixel grid from Java
   - `ImagingTest`: open imaging fixture, assert 9 spectra; each spectrum's
     `ScanEvent.imagingPosition()` returns non-null with x in [1..3], y in [1..3];
     `fileMetadata().scanSettingsList()` is non-empty with pixel grid dimensions
   - Round-trip writer test: create 9 spectra with pixel coordinates, write, read back, assert
     positions preserved

**Success Criteria:**
1. `reader.getSpectrum(i).description().scans().get(0).imagingPosition()` returns correct pixel
   coordinate for an imaging mzPeak file.
2. `reader.fileMetadata().scanSettingsList()` returns the pixel grid metadata.
3. Writer produces a valid imaging mzPeak directory that the reader can open with correct positions.
4. Non-imaging files open without error (position columns absent → `imagingPosition()` returns null).
5. Codex adversarial review passes.

---

### Phase M3.4 — Robustness and Writer Improvements

**Priority: P2 — hardening; no user-visible feature**

**Goal:** Align with upstream robustness fixes; harden edge cases found during M3.1–3.3.

**Tasks:**

1. **Chunk decoding: non-large-list tolerance**
   - `SpectrumArrayStore` chunk decoder: `doubleListNullable` / `byteList` in `ParquetGroups`
     currently assume `large_list`. If a fixture uses `list` (not `large_list`), these throw.
   - Fix: check `GroupType` for both `list` and `large_list` in `ParquetGroups.doubleListNullable`
     and `ParquetGroups.byteList`; handle both transparently.
   - Upstream reference: commit `573e1344` ("make chunk layout reader compatible with non-large lists").

2. **NaN charge state coercion**
   - `SpectrumMetadataDecoder`: charge state decoded as float may be `NaN` or `Infinity` for some
     vendor files. Coerce to `null` rather than propagating NaN (aligned with mzdata commit
     `432c9f86`).

3. **Bloom filter awareness (writer)**
   - `MzPeakWriter`: add bloom filter on the `spectrum_index` column when writing
     `spectra_data.parquet` and `spectra_peaks.parquet`. parquet-java supports this via
     `ParquetWriter.Builder.withBloomFilterEnabled(columnPath, true)`.
   - Low impact on read performance today (parquet-java doesn't use bloom filters for pushdown),
     but makes written files more compatible with other implementations.

4. **Source of NaN fix**
   - Review the NaN-source fix (commit `d1aaaf84`) in the Rust writer and check whether mzPeakJ's
     own writer has the same issue (likely the SLOF fixedPoint computation when all intensities
     are zero).
   - Add a `NumpressWriterTest` case with an all-zero intensity spectrum to catch this.

**Success Criteria:**
1. Chunk decoding succeeds on a synthetic fixture whose intensity column uses `list` (not
   `large_list`).
2. Charge state `NaN` in fixture → `SelectedIon.primaryIon().charge()` returns `null`.
3. Written mzPeak files contain bloom filters on spectrum_index (verifiable via parquet-tools).
4. All 123+ tests pass; codex adversarial review passes.

---

## Milestone 3 Progress

| Phase | Description | Priority | Status |
|---|---|---|---|
| M3.1 | Centroid/Profile separation — writer + reader routing | P0 | Not started |
| M3.2 | Ion mobility reading — scan + selected_ion sub-tables | P1 | Not started |
| M3.3 | Imaging (MSI) support — position x/y + scan_settings | P1 | Not started |
| M3.4 | Robustness — non-large-list chunk, NaN charge, bloom filters | P2 | Not started |

**Execution order:** M3.1 → M3.2 → M3.3 → M3.4 (M3.2 and M3.3 can be parallelized after M3.1)

---

## Backlog (Deferred from Milestone 2)

These items were explicitly deferred and remain out of scope for Milestone 3 unless
the user requests them:

| Item | Notes |
|---|---|
| Chunk/delta layout writing | Writing profile arrays as delta-encoded chunk layout |
| Wavelength spectra writing | Encoding UV/DAD spectra in the writer |
| Chromatogram chunk writing | Chunk time-axis layout is decoded but not written |
| Page-level pushdown within row group | Whole row groups are skipped; individual pages are not |
| Detail-level loading | Metadata-only vs full-spectrum (currently always full) |
| Full ZIP64 EOCD | Per-entry zip64 extra fields work; true zip64 EOCD rejected |
| Maven Central publication | Not yet published to Central |

---

## Upstream Tracking

Repository moved: `mobiusklein/mzpeak_prototyping` → **`HUPO-PSI/mzPeak`** (GitHub redirect still works)

Key upstream repos:
- Spec + Rust: https://github.com/HUPO-PSI/mzPeak (HEAD: `8435967b`, 2026-06-06)
- C#/.NET: https://github.com/HUPO-PSI/mzPeak.NET (HEAD: `8a950fa0`, 2026-06-07)
- TypeScript: https://github.com/HUPO-PSI/mzpeakts
- mzdata (Rust MS I/O): https://github.com/mobiusklein/mzdata (v0.64.2)

Next sync recommended after: spec formalizes imaging entity type / scan_settings schema (currently
implementation-leading-spec; the June 2026 meeting minutes indicate formal spec update planned
for end-of-summer 2026).
