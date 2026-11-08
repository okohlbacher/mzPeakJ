# mzPeakJ — Design & Format Notes

This document captures how the [mzPeak](https://www.psidev.info/mzpeak) format works (as observed in the
HUPO-PSI example files and spec) and the decisions behind this Java reader/writer. It is the reference for
anyone extending mzPeakJ or porting the format to a new language.

> **Demonstrator.** mzPeakJ tracks the Rust reference implementation
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping), the HEAD Python reader and the
> HUPO-PSI spec. Ground truth is the spec **plus the actual example Parquet files** (the published paper
> [doi:10.1021/acs.jproteome.5c00435](https://doi.org/10.1021/acs.jproteome.5c00435) does not include
> schema detail). mzPeak is an unstable prototype. mzPeakJ reads the current layout of upstream commit
> `2ee2589` (spec snapshot `0220201`), whose examples are vendored in `src/test/resources/mzpeak/`, and the
> pin-era layout of upstream commit `29e59b2`, vendored as the legacy set in `src/test/resources/mzpeak-legacy/`.
> Each directory names its commit in `UPSTREAM_COMMIT.txt`.

**Legacy** marks the pin-era layout of upstream `29e59b2` and earlier, which mzPeakJ still writes (§8.1) and
the mzML2mzPeak corpus uses. Sections and paragraphs marked legacy describe it; everything else describes the
current layout.

---

## 1. What an mzPeak dataset is

A set of Apache Parquet files plus a `mzpeak_index.json` manifest, delivered as **either**:

- an **unpacked directory** `run.mzpeak/`, or
- a **single file** `run.mzpeak` — an **uncompressed (STORED) ZIP** of the same members. The example
  archives put the manifest last; the reader does not depend on the entry order.

TAR was explicitly rejected by the spec because it requires linear traversal and lacks per-file encryption.
ZIP was chosen because members can be accessed at arbitrary byte offsets.

### 1.1 Containers

`mzPeakJ` reads both via the `MzPeakSource` abstraction (`DirectorySource` / `ZipSource`). Because the ZIP
members are STORED (no ZIP-level compression — Parquet files use their own ZSTD), `ZipSource` parses the
central directory (`ZipIndex`) and reads each Parquet member **in place** from its byte range via a seekable
`FileSliceInputFile` over the archive's shared `RandomAccessBytes` (positional reads; a `FileChannel` for a local
file) — no copy into memory. A DEFLATE member, which the spec does not use, is inflated into memory as a fallback.
For other storage, `MzPeakSource.zip` and `MzPeakSource.unpacked` read caller-supplied `RandomAccessBytes`
(§8.2).

Members, offsets and the archive itself may exceed 4 GiB:

- **Per-entry zip64 extra fields** (header id `0x0001`) carry the size, compressed size and local-header offset
  of a member whose 32-bit field holds the `0xFFFFFFFF` sentinel. Every example archive of both fixture sets
  uses them, with a classic end-of-central-directory (EOCD) record.
- **zip64 EOCD.** When the classic EOCD carries a `0xFFFF`/`0xFFFFFFFF` sentinel and a zip64 locator
  (`0x07064b50`) sits 20 bytes before it, the central directory is taken from the zip64 EOCD record
  (`0x06064b50`) the locator points to. The record must lie before the locator and the directory before the
  record; a violation throws an `MzPeakException` that names zip64.
- A sentinel without a locator is accepted only as an entry count of exactly 65535 with the size and offset in
  range. The central directory itself must fit in 2 GiB.
- A member name that occurs twice throws.

### 1.2 Manifest and roles

The manifest lists every member under `files`. Each entry carries `name`, `entity_type`, `data_kind`,
`column_mapping` (alias `metadata_mapping`; see §2.2), `parameters` and `checksum` (lowercase hex SHA-512 of the
member). The `metadata` object carries the format `version`, the `cv_list`, the `scan_settings_list` and the
file-level documents (§7). An entry without a `name` makes the parser throw, naming its position and role, as both
references reject it.

The role `(entity_type, data_kind)` identifies a member. **Resolve members by role, never by file name.**
`MzPeakManifest` parses both strings as the Rust reference does (`FromStr`): lowercased and trimmed, with
aliases.

| Enum | Values (canonical spelling) | Also accepted |
|---|---|---|
| `EntityType` | `spectrum`, `chromatogram`, `wavelength_spectrum`, `provenance` | `mass spectrum`, `mass_spectrum` (spectrum); legacy spelling (below) |
| `DataKind` | `data_arrays`, `peaks`, `metadata`, `scans`, `precursors`, `selected_ions`, `products`, `signature`, `proprietary` | legacy spelling (below) |

Any other value, including `other` and an absent one, parses to `OTHER`, which never matches.
`MzPeakManifest.find(EntityType, DataKind)` returns the first matching entry in manifest order; the
`find(String, String)` overload parses its arguments first. `Entry` keeps the strings as written.

| Role | Conventional file | Read by |
|---|---|---|
| spectrum / metadata | `spectra_metadata.parquet` | `SpectrumMetadataDecoder` (§2) |
| spectrum / scans, precursors, selected_ions | `spectra_metadata_scans.parquet`, `_precursors`, `_selected_ions` | `SplitMetadataReader` (§2.2) |
| spectrum / data_arrays | `spectra_data.parquet` (signal arrays, typically profile) | `SpectrumArrayStore` (§3) |
| spectrum / peaks | `spectra_peaks.parquet` (centroid peaks) — **optional** | `SpectrumArrayStore` (§3) |
| chromatogram / metadata | `chromatograms_metadata.parquet` | `ChromatogramMetadataDecoder` (§2.5) |
| chromatogram / precursors, selected_ions | `chromatograms_metadata_precursors.parquet`, `_selected_ions` | not decoded (§10) |
| chromatogram / data_arrays | `chromatograms_data.parquet` | `ChromatogramDataDecoder` (§3.5) |
| wavelength_spectrum / metadata | `wavelength_spectra_metadata.parquet` | `WavelengthMetadataDecoder` (§2.5) |
| wavelength_spectrum / scans | `wavelength_spectra_metadata_scans.parquet` | not decoded (§10) |
| wavelength_spectrum / data_arrays | `wavelength_spectra_data.parquet` | `WavelengthSpectrumStore` (§3.5) |

Members of other roles (products, provenance, signature, proprietary, other) are ignored. Checksums are parsed
but not verified on read (§10); `UpstreamFixtureIntegrityTest` verifies those of the vendored examples.

**Legacy.** Pin-era manifests spell the roles `data arrays` and `wavelength spectrum` (with a space), have no
`column_mapping`, `parameters` or `checksum`, and list a single metadata member per entity, which holds all
its facets (§2.4). The parser accepts both spellings, so `data arrays` and `data_arrays` resolve to the same
role.

---

## 2. Metadata tables

### 2.1 Facets and layout detection

Spectrum metadata is split into four **facets**: the spectra themselves, their scans, their precursors and the
selected ions. Chromatograms have their own table plus precursor and selected-ion facets; wavelength spectra
have their own table plus scans. `Facet` names all nine; each has a role and key columns.

| Facet | Role | Key columns |
|---|---|---|
| `SPECTRUM` | spectrum / metadata | `index` |
| `SPECTRUM_SCAN` | spectrum / scans | `source_index`, `scan_index` |
| `SPECTRUM_PRECURSOR` | spectrum / precursors | `source_index`, `precursor_index` |
| `SPECTRUM_SELECTED_ION` | spectrum / selected_ions | `source_index`, `precursor_index`, `selected_ion_index` |
| `CHROMATOGRAM` | chromatogram / metadata | `index` |
| `CHROMATOGRAM_PRECURSOR`, `CHROMATOGRAM_SELECTED_ION` | chromatogram / precursors, selected_ions | as for spectra |
| `WAVELENGTH_SPECTRUM` | wavelength_spectrum / metadata | `index` |
| `WAVELENGTH_SCAN` | wavelength_spectrum / scans | `source_index`, `scan_index` |

The layout of a metadata member comes from **its own schema** (`MetadataShape`), never from
`metadata.version` nor from which facet members the manifest lists (no legacy example manifest has a
`metadata.version`, nor has Example_Processed.img; the other current examples carry 0.9.0, the upstream
`MZPEAK_VERSION` constant that mzPeakJ's writer also writes in both layouts):

| Shape | Recognised by |
|---|---|
| `SPLIT` | a top-level primitive key column (`index`, or `source_index` for a facet table) |
| `PACKED` (legacy, §2.4) | a top-level group named after the facet's packed struct (`spectrum`, `scan`, `chromatogram`, …; `spectrum` for wavelength spectra) |
| `EMPTY` | neither, and 0 rows: decodes to nothing |

A member with rows but neither shape throws, naming the member, the expected struct and the columns found.
`MetadataTable.forEachRow` iterates the rows of one facet in either shape.

### 2.2 Split tables

Each facet is its own member with flat top-level columns. `SplitMetadataReader` reads the four spectrum
facets, found by role; a facet member the manifest does not list is legal and contributes no rows.

**Join rule.** Rows are joined strictly by key: a spectrum by its `index`, every facet row by its
`source_index`. **Never join by row position** — the facet tables are not row-aligned with the spectrum table
(current small has 48 spectra but 34 precursor rows). A null key throws, as do a uint64 key with the high bit
set and a repeated spectrum index. Facet rows whose `source_index` matches no spectrum are dropped, as in the
Rust reference.

**Column resolution.** Every metadata column is resolved by accession through `ColumnResolver`, in this order,
taking only a field directly in the current scope:

1. the member's `column_mapping` entry for the accession (the manifest maps each column to a CV term, for
   example path `ms_level` to `MS:1000511`);
2. the canonical path of `ColumnTable`;
3. the `ColumnTable` fallbacks: spec alternates, then pin-era names (§2.4);
4. names given by the caller.

Steps 2 to 4 never take a field that the member's `column_mapping` assigns to another accession.

`ColumnTable` is the constant column identity of the nine facets, shared by reader and writer. Its canonical
rows are the union of the `column_mapping`s of the 8 current example manifests, copied verbatim. Paths are
dotted and omit list and element levels (`scan_windows.scan_window_lower_limit`). Example_Processed.img stores
its imaging positions in the columns `opt_IMS_1000050_position_x` and `opt_IMS_1000051_position_y`.

A **term marker** column (`term_marker: true`) holds child-term CURIEs of its term rather than a value, for
example `spectrum_representation` = `MS:1000128` or `activation.dissociation_method` = `MS:1000133`. A
boolean term marker holds presence flags; only TRUE counts.

A mapping's `unit` is a unit CURIE when given as a string. Given as a JSON array, it is the path of a column of
per-row unit CURIEs (Rust `PathOrCURIE`), kept apart as `ColumnMapping.unitPath`; that column is not resolved
yet, so the mapped values have no unit.

**Typed reads** go through `ParquetGroups` and are unsigned-aware: uint8, uint16 and uint32 cells are not
sign-extended; a uint64 cell with the high bit set throws. A column of the wrong shape or physical type (a scalar
where a struct or a list is expected, a struct where a scalar is, a non-numeric list) throws an `MzPeakException`
that names the member and the column, in both layouts.

**Leftovers.** Scalar columns that no decoder consumes become Params (split layout only). Only primitive,
non-repeated fields directly in the scope are visited. Structural columns (`ColumnTable.structural`: keys such
as `index`, `id`, `source_index`, `scan_index` or `precursor_index`, and bookkeeping columns such as
`parameters`, `auxiliary_arrays`, `number_of_auxiliary_arrays`, `data_processing_id` or the spec's
`data_processing_ref`), consumed columns and null cells are skipped. Unknown `opt_` columns become Params like
any other unmapped column.

| Column | Param |
|---|---|
| term-marker string | `Param(null, cell CURIE)` |
| term-marker boolean | `Param(mapping name, mapping accession)`, only when TRUE |
| mapped | `Param(mapping name, accession, value, unit)` |
| unmapped | `Param(field, null, value)` |

Leftovers of the spectrum, scan, activation and selected-ion scopes join that scope's Params (for example the
summary columns `lowest_observed_mz` … `total_ion_current` and the `spectrum_type` marker at spectrum scope).
Leftovers of the isolation-window scope follow its `parameters` list in `IsolationWindow.parameters` (AP-7).
Leftovers of the precursor scope have no place in the model; they are logged at DEBUG and dropped.

**What each row decoder reads:**

| Decoder | Fields |
|---|---|
| `SpectrumRowDecoder` | `index`, `id`, `time`; MS:1000511 ms level (default 0), MS:1000465 polarity, the MS:1000525 representation marker, MS:1003060 / MS:1003059 declared counts (default 0); the delta model (§4.1); `parameters`; leftovers (a column wins over a list entry with the same accession) |
| `ScanRowDecoder` | MS:1000016 scan start time in minutes (converted from its mapping's unit: second, millisecond or hour; another unit, or a unit column, throws), MS:1000927 ion injection time, MS:1000512 filter string, MS:1000616 preset scan configuration; `instrument_configuration_id`; `spectrum_reference`; `scan_windows` (MS:1000501/MS:1000500, a window needs both); `parameters`; typed ion mobility (§5); IMS:1000050/51 positions as Long Params when the list lacks them; leftovers |
| `PrecursorRowDecoder` | `precursor_index`, `precursor_id`; `isolation_window` (MS:1000827/28/29, then `parameters` and leftovers); activation Params in this order: the MS:1000044 dissociation-method marker, MS:1000045 as 'collision energy' with the mapping's unit, activation-scope leftovers, then `parameters` (a column wins over a list entry with the same accession) |
| `SelectedIonRowDecoder` | MS:1000744 (a row without it is skipped), MS:1000041, MS:1000042, `parameters`, typed ion mobility, leftovers |

### 2.3 One assembler for both layouts

`SpectrumFacetAssembler` takes the rows of both layouts, keyed as above, and builds the `SpectrumDescription`s
sorted by index. A spectrum without a `time` takes the smallest finite scan start time as its retention time,
which is where the Rust reader takes it from. `PrecursorJoin` attaches the precursors of one spectrum. Its key is
`(source_index, precursor_index)`, null equal to null:

- precursors keep their file order, as in the Python reader (the Rust reference pushes them in reverse, so its
  first precursor is the one with the highest `precursor_index`);
- a selected ion goes to the **first** precursor with its key; one whose key no precursor has goes to the
  spectrum's first precursor (the Rust reference falls back to the last one);
- selected ions of a spectrum without precursor rows form a precursor-less `Precursor` (the Rust reference
  drops them);
- a selected-ion row whose m/z is null is dropped (the Rust reference keeps the ion with m/z 0.0, its charge and
  its intensity).

Every example has one precursor per MSn spectrum, so the divergences do not show there. Two precursors of one
spectrum that share a key cannot be told apart by either reader.

### 2.4 Legacy: packed parallel tables

Pin-era `spectra_metadata.parquet` has **four independent top-level struct columns** — `spectrum`, `scan`,
`precursor`, `selected_ion` — that act like relational tables joined by integer keys. They are **not
row-aligned** with each other.

- `spectrum.index` (uint64) is the primary key.
- `scan`, `precursor` and `selected_ion` each carry their own `source_index`. A row whose struct or key is null
  is a placeholder and is skipped.
- Join: for the spectrum at `index = N`, collect all facet rows where `source_index = N`.

In legacy `small.unpacked.mzpeak`, metadata row 0 (spectrum index 0, an MS1) holds a `selected_ion` with
`source_index = 2`: it belongs to spectrum 2, not 0. Zipping by row position silently mis-assigns every
precursor.

`PackedMetadataReader` reads the four structs in one pass and feeds the same assembler (§2.3). The columns
have no `column_mapping`; they are resolved by accession through the `ColumnTable` fallbacks, which hold the
pin-era names such as `MS_1000511_ms_level`, `MS_1000525_spectrum_representation` or
`isolation_window.MS_1000827_isolation_window_target_mz`. Packed scopes yield no leftovers: the legacy writer
writes derived summary columns next to the `parameters` list, so leftovers would duplicate Params across a
read–write–read cycle. The legacy delta model is `mz_delta_model` (§4.1).

Legacy chromatogram metadata has the structs `chromatogram`, `precursor` and `selected_ion`; legacy
wavelength-spectrum metadata has `spectrum` and `scan`.

### 2.5 Chromatogram and wavelength-spectrum metadata

Both read either layout through `MetadataTable`, columns by accession:

- `ChromatogramMetadataDecoder`: `index`, `id` and the MS:1000626 chromatogram-type marker.
- `WavelengthMetadataDecoder`: `index`, `id`, `time` and MS:1003812 lambda max.

A repeated index keeps its first position and the values of its last row. The chromatogram precursor and
selected-ion facets, the wavelength scans facet and the Params of both tables are not decoded (§10).

---

## 3. Signal layouts and the array index

### 3.1 Point and chunk rows

Each signal member's top-level struct is named `point` **or** `chunk`.

**Point layout:** one row per data point.

```
point: struct<spectrum_index: uint64, mz: double, intensity: float>
```

Extra arrays are further columns of the struct (diaPASEF.ref: `mean_inverse_reduced_ion_mobility`). Chromatograms
use `chromatogram_index` and `time`, wavelength spectra `wavelength_spectrum_index` and `wavelength`.
`spectra_peaks.parquet` is **optional**: it stores centroid peaks next to the profile arrays of the same
spectrum, or instead of them (in current small the 34 MS2 spectra exist only there; small.chunked also stores
1612 centroid peaks for profile spectrum 0).

**Chunk layout:** one row per fixed-width chunk of the main axis (typically ~50 Da wide).

```
chunk: struct<spectrum_index: uint64, mz_chunk_start: double, mz_chunk_end: double,
              mz_chunk_values: large_list<double>, chunk_encoding: string,
              intensity: large_list<float>, ...transform columns>
```

`mz_chunk_start`/`mz_chunk_end` stay visible to the Parquet statistics; the encoded values are opaque to
them.

### 3.2 Array index and SignalPlan

The footer key `<entity>_array_index` (`spectrum_array_index`, `chromatogram_array_index`,
`wavelength_spectrum_array_index`) describes every column that holds part of a signal array: `{prefix,
entries}`, each entry with `path`, `array_type`, `data_type`, `unit`, `buffer_format` (`point`,
`chunk_values`, `chunk_secondary`, `chunk_start`, `chunk_end`, `chunk_encoding`, `chunk_transform`, `index`),
`transform`, `sorting_rank`, `buffer_priority`, `array_name`, `context` and `data_processing_id`. The spec's
conformance rules require readers to resolve signal columns through it rather than by column name.

`ArrayIndex` parses it; `SignalPlan.of(index, schema, entity)` derives the columns to read:

1. An `index` entry names the index field; without one it is `<entity>_index`.
2. **Main axis:** the array type with the lowest `sorting_rank` among the other entries (rank 1 in current
   files). It must be the entity's default axis — m/z `MS:1000514`, time `MS:1000595`, wavelength
   `MS:1000617` — otherwise the plan throws. Two value-bearing entries for the main axis throw too, because m/z
   would be ambiguous. Its `chunk_start`, `chunk_end` and `chunk_encoding` entries give the chunk columns; its
   `chunk_transform` entries map a transform CURIE to a column (`MS:1002312` → `mz_numpress_linear_bytes`,
   `MS:1003826` → `mz_grid`).
3. **Intensities:** every `MS:1000515` entry, in index order.
4. **Extras:** every other entry, keyed as in §3.6.
5. Every entry's path must lie under the prefix, and the buffer formats must agree with it (no chunk format
   under `point`, no `point` format under `chunk`); otherwise the plan throws. An entry whose path names no
   column of the member is skipped with a WARNING when it is an extra; a main-axis or intensity entry without a
   column throws, because skipping it would read NaN m/z, zero intensities or absolute values as deltas.

**Role flags never pick an array.** Arrays are told apart by array type and buffer format, not by
`buffer_priority`: small.chunked's intensity is `chunk_secondary`, and diaPASEF.ref's ion mobility is
`primary`.

A member without the footer key gets an **INFERRED** plan built from fixed column names (`point.mz`,
`chunk.mz_chunk_values`, `intensity_numpress_slof_bytes`, …), the names mzPeakJ read before it used the array
index; files written by mzPeakJ 0.10.3 and earlier decode through it. Since WR-2, the files mzPeakJ writes carry
a footer `<entity>_array_index` and decode through a FOOTER plan. A malformed index also gives the INFERRED
plan, with a WARNING.

The null-marking transforms `MS:1003901` and `MS:1003902` are treated alike: the spec swapped their labels
(current files put `MS:1003902` on m/z), and filling is keyed on NaN positions anyway.

**Legacy.** Pin-era array indices have no `index` entry and rank the main axis 0, with the two null-marking
transforms the other way round. Rule 1 and the lowest-rank rule cover them unchanged.

### 3.3 Spectrum chunks

`ChunkRowDecoder` decodes one chunk row through the plan. Per row:

| Row | Axis | Intensity |
|---|---|---|
| the axis's `MS:1002312` transform column is non-null | Numpress linear, absolute | SLOF, else PIC, else the plain list; 1:1 |
| `chunk_encoding` null or `MS:1003089` | null-aware delta from `chunk_start` | plain list, as long as the values or one longer |
| `MS:1000576` or `MS:1003088` | absolute values | same |
| `MS:1003826` (coordinate grid) | the grid's model applied to its cumulative indices (below) | plain list, 1:1 |
| any other CURIE | throws `unsupported chunk encoding <curie> for spectrum_index <n>` | |

Column presence picks the Numpress decoder, but an unknown `chunk_encoding` throws on every row. A row that
names `MS:1002312` without carrying a Numpress column decodes as delta. A row without Numpress m/z is never
decoded as Numpress: SLOF or PIC intensity that decodes to values throws, and a payload without values is
skipped, so it cannot hide the row's delta m/z and plain intensities. A plan may list several plain lists, SLOF
or PIC columns: the first plain list that carries a value (non-null and not empty) is read, else the first
non-null one, and the first SLOF or PIC column whose payload decodes to values. An empty payload, or a SLOF
payload of its 8-byte header only, has no values and does not hide a later column of any kind.

**Delta encoding** (`MS:1003089`, "truncation, delta prediction and zlib compression"): differences from a
predictor seeded at `mz_chunk_start`. **Null-aware**, per the spec's `null_delta_decode`: a null (NaN) breaks
the prediction chain, and the next non-null value is read as an **absolute re-anchor**, not a delta. Treating
a post-null value as a delta double-counts the re-anchor and yields non-monotonic, out-of-range m/z.

**The chunk_start-as-a-point rule** (learned from real data):

> A chunk whose `intensity.length == mz_chunk_values.length + 1` emits `mz_chunk_start` as its **first
> real point** (`intensity[0]` = its intensity); the decoded m/z values then pair with `intensity[1..]`. A
> chunk with equal lengths pairs values 1:1 with no separate start point.

The rule applies per chunk, not just to the first chunk of a spectrum. small.chunked decodes bit-identically
to small.unpacked for all 48 spectra (`LayoutEquivalenceTest`).

**MS-Numpress.** `mz_numpress_linear_bytes` (MS:1002312) with `intensity_numpress_slof_bytes` (MS:1002314) or
`intensity_numpress_pic_bytes` (MS:1002313) are decoded with the bundled Apache-2.0
[MS-Numpress](https://github.com/ms-numpress/ms-numpress) decoders (`ms.numpress.MSNumpress`). Numpress
encodes the **full absolute arrays** with no chunk_start, no delta and no null marking — decode and append
1:1. It is lossy by design (linear m/z is near-lossless; SLOF intensity is log-scaled uint16; PIC is
integer-only). The SLOF encoder chooses `fixedPoint = floor(65535 / log(max_intensity + 1))` per chunk so the
encoded values fit in uint16; this is transparent to the reader.

**Fail loudly.** A chunk the reader cannot decode throws an `MzPeakException` instead of being dropped or
misdecoded: a grid chunk without a grid or with an unknown `grid_type` (below), an unknown `chunk_encoding`,
Numpress intensity that decodes to values without a Numpress m/z source, Numpress m/z that decodes to values
without an intensity column, a value in a `*numpress*` column the plan does not decode, and a value in an
intensity chunk transform other than SLOF and PIC when no plain list of the row carries a value and no SLOF or
PIC payload decodes to values.

**Coordinate grids** (`MS:1003826`, draft). A grid chunk stores its axis as a struct `{grid_type, parameters,
indices}` in the column that the array index lists as the axis's `MS:1003826` chunk transform (`mz_grid` in
diaPASEF.grid): a model CURIE, the model's parameters, and grid indices read as unsigned 32-bit integers
(`GridChunk`). Each index maps to one coordinate through the model. Per row, `rejectUnknownNumpressColumns`
runs first, then the grid dispatch: a row whose `chunk_encoding` is the grid encoding takes the grid path;
otherwise a non-null Numpress linear column selects the Numpress path, by its presence, not by its payload, and
the delta path takes the rest. So a member may mix grid rows with delta rows.

- **Main axis.** The indices are delta-encoded: their running sum, kept in a `long`, gives each point's grid
  index, and the first index is absolute. The m/z values pair 1:1 with the plain intensity list; no chunk start
  point is emitted.
- **Extra arrays** stored as a grid (their `MS:1003826` transform column carries a value, such as
  diaPASEF.grid's ion mobility) use absolute indices and land in the extras (§3.6).

`GridModels.KINDS` is the single table of models:

| `grid_type` | Model | Parameters |
|---|---|---|
| `MS:1003824` linear grid interpolation | `(a + b·i) / s` | `[a, b]` or `[a, b, s]`; `s` defaults to 1 |
| `MS:1003825` square root grid interpolation | `(a + b·i)² / s` | `[a, b]` or `[a, b, s]`; `s` defaults to 1 |
| `MS:9999002` timsTOF m/z grid, **provisional** | a port of mzdata's `convert_f64`: the time of flight `i·timebase + delay`, the root `s` of the cubic calibration `c0 + beta·s + c2·s² + c3·s³ = tof` (Newton when `c3 ≠ 0`), then m/z = `s² − c4` | 7: `c0, beta, c2, c3, c4, timebase, delay` |
| `MS:9999001` timsTOF 1/K0 grid, **provisional** | `1 / (c6 + c7 / (offset + slope·i))` | 4: `c6, c7, offset, slope` |

The two `MS:9999…` accessions are placeholders that PSI-MS does not define yet; renumbering one is a one-line
change in `KINDS`. The arithmetic follows the reference's scalar decoders, fused multiply-adds included, and the
9 spectra of diaPASEF.grid decode to those of diaPASEF.ref bit for bit: m/z, intensity and ion mobility
(`GridDecodeTest`).

**Grid errors.** On the main axis, a row whose `chunk_encoding` equals the grid encoding but whose grid cannot be
decoded throws `chunk_encoding MS:1003826 for spectrum_index <n>, but the signal plan has no grid column`, `...,
but the row has no grid in <column>`, or, for a `grid_type` that is not in `KINDS`, `grid_type <type> of <column>
is not one of MS:1003824, MS:1003825, MS:9999002, MS:9999001 for spectrum_index <n>`. A row with a grid,
intensities and no axis values whose `chunk_encoding` is not the grid encoding throws `<column> carries a grid
but chunk_encoding is <encoding> for spectrum_index <n>`. A grid that cannot be read as the struct, an index that
is not an unsigned 32-bit integer, a running sum beyond 2³²−1, a parameter count the model does not take, or an
intensity list of another length throws an `MzPeakException` that names the grid column. A secondary grid of an
unknown `grid_type`, or one that cannot be decoded, only drops that extra array with a WARNING (§3.6).

### 3.4 Assembling a spectrum

`MzPeakReader` reads `spectra_data` and `spectra_peaks` separately. `Spectrum.mz()`/`intensity()` hold the
data arrays, or the peaks when the spectrum has no data rows; `peaks()` holds the centroids (sorted by m/z,
non-finite m/z dropped). That is the default `SignalMode.BOTH`; `PROFILE` and `CENTROID` read only one of the two
members (§8.2). A signal member must be sorted by spectrum index; an index in two non-contiguous runs throws.

### 3.5 Chromatograms and wavelength spectra

`ChromatogramDataDecoder` reads the columns the chromatogram plan names:

- **point:** the time value, and the first non-null value among the value-bearing intensity columns in index
  order. has_uv's 'DAD1 A' absorbance comes from `intensity_f32_au` (unit UO:0000269) where `intensity` is
  null. A null time reads as NaN, an intensity without any value as 0.
- **chunk:** the chunk start followed by the running sum of the chunk values as deltas (`chunk_encoding` null
  or `MS:1003089`); the intensities are the first intensity list that carries a value (non-null and not
  empty), else the values of the first Numpress SLOF column whose bytes decode to any (an empty payload, or a
  SLOF header without values, has none), considering every list and SLOF column of the plan. Missing
  intensities are padded with 0 and surplus ones dropped.

What the decoder cannot decode throws instead of reading as wrong or empty arrays: another `chunk_encoding`, a
value in a chunk transform column of the time axis (such as Numpress linear time), a chunk in which no list
carries a value and no SLOF column decodes to values but another intensity chunk transform does (any PIC
column, another transform, or one that names none), a negative index, and, on the first row, a plan without a
time values column (point layout) or without any intensity source.

`Chromatogram.intensityUnit()` is the unit of the plan entry that supplied the values (null for an INFERRED
plan or mixed units). Wavelength spectra are dense point rows (`WavelengthSpectrumStore`, `PointArrays`), read
like chromatogram point rows: the first non-null value among the intensity columns in index order; a
data member in chunk layout, or one without the plan's prefix struct, wavelength column or intensity column,
throws when the wavelength spectra are first requested.

### 3.6 Extra arrays

Every plan entry that is neither the main axis, an intensity nor the index is an **extra array**, keyed by:

- its `array_type` CURIE, for example `MS:1003006` (mean inverse reduced ion mobility array);
- its `array_name` for `MS:1000786` (non-standard data array), for example `ms level` with a space;
- `<key>@<field>` for each further value-bearing column of a key: arrays of one type may differ in unit or
  data type. The column with `buffer_priority` primary, else the first, gets the plain key. Nothing is thrown
  for extras.

`Spectrum.arrays()` is parallel to `mz()`; when `mz()` comes from the peaks member, it holds the peak extras.
`Spectrum.peakArrays()` is parallel to the stored peak list, addressed by `CentroidPeak.index()`.
`Spectrum.ionMobility()` is the first array whose key is in `CvTerms.ION_MOBILITY_ARRAYS` (§5). The maps are
unmodifiable and in array-index order; the `double[]` values are shared, not copied, like `mz()`.

`Chromatogram.arrays()` follows the same keys and contract, parallel to `time()`. Every TIC of the small*
examples and of has_uv carries a per-point `ms level` array (`MS:1000786`): 48 of 48 values in small.unpacked,
`chunk_secondary` in small.chunked, 212 in has_uv. has_uv's 'DAD1 A' has none.

**Nulls and drops.** A null cell reads as NaN; an entity whose cells of a key are all NaN gets no entry for
that key. With `reconstructProfile=false` the points dropped from `mz()` are dropped from the extras too. An
extra whose list or grid length does not match its chunk, whose grid has an unknown `grid_type` or cannot be
decoded (§3.3), whose other transform column (a Numpress one, not decoded for extras) carries a value, or whose
cells are not numbers is dropped with a WARNING (once per member and key). m/z and intensity are never dropped,
and a problem with an extra never prevents reading them.

### 3.7 Streaming

`SpectrumArrayStore` reads the per-row-group min/max statistics of the plan's index column at open time.
`get(index)` decodes only the row group(s) that cover the requested index and caches the last block run; the
range queries (§8.2) read through it, so a query decodes only the row groups of the spectra it selects.
`ZipSource` reads STORED members in place via `FileSliceInputFile` (positional `RandomAccessBytes` reads), so a
single-file archive streams identically to an unpacked directory.

---

## 4. Null marking and profile reconstruction

Profile spectra drop flanking zero-intensity points: the writer replaces their m/z with null. In current
small, spectrum 0 has 13,589 points, 2,376 of them null-marked (11,213 stored m/z); across its 14 profile
spectra 39,968 points are null-marked. The spectrum metadata stores a polynomial model of the local m/z
spacing:

```
delta(mz) = β₀ + β₁·mz + β₂·mz²  (least-squares polynomial on the non-null points)
```

### 4.1 Delta-model lookup

`SpectrumRowDecoder` takes the first of these that the schema has:

1. the list column of `MS:1003821` (`polynomial_delta_coordinate_interpolation` in current files);
2. the list column of `MS:1003820` (coordinate spacing model), also found under the name
   `coordinate_spacing_model`;
3. the scalar `median_delta`, as a one-coefficient model.

The column is picked by its presence in the schema, so a null cell in the chosen column gives no model even
when `median_delta` is set (`PackedResolverTest`).

**Legacy.** Pin-era files store the model as `spectrum.mz_delta_model`, which step 2 accepts as a third name of
the `MS:1003820` column, after `coordinate_spacing_model`. The HEAD Python reader (`reader.py:807-820`) never
reads a pin-era file, since it rejects the packed layout, and orders the lookup differently: `median_delta`
first, then a column named `mz_delta_model`, then the column the mapping gives `MS:1003821`, else `MS:1003820`.
The orders give different models for a file that has `median_delta` and a list column, for one that has
`mz_delta_model` and another column mapped to `MS:1003821` or `MS:1003820`, and for one whose only model is an
unmapped `coordinate_spacing_model` column, which the Python reader never reads.

### 4.2 Fill

With `reconstructProfile=true` (the default), each null-marked m/z is filled one `predict(mz)` step from a
stored neighbour, flank-aware. Null marking replaces the zero-intensity points on both sides of each stored
cluster, so an interior run of two nulls is the right flank of one cluster followed by the left flank of the
next. The first null steps forwards from the left anchor (`m[left] + predict(m[left])`), the last steps
backwards from the right anchor (`m[right] - predict(m[right])`). A leading run steps backwards from the
first stored value; a trailing run, an interior single null and the middle of a longer run step forwards. The
stored polynomial is used for both flanks as is, without re-fitting. Filled points get intensity 0; the point
count matches the declared `number_of_data_points` (13,589 for spectrum 0) and the axis is monotonic.

Without a stored model, nulls are filled by linear interpolation between stored anchors. With
`reconstructProfile=false` only the stored points are returned (11,213 for spectrum 0).

### 4.3 Comparison with the reference readers

Both reference readers — Rust `fill_nulls_for` (`filter.rs`) and the HEAD Python reader (`filters.py`) — are
flank-aware too, and agree with each other on a different rule: they use the polynomial only for a
single-point cluster and the cluster's local median spacing otherwise. mzPeakJ keeps the stored polynomial for
every cluster.

The only ground truth is small.numpress, which stores the same 14 profile spectra without null marking.
Against it, every point mzPeakJ fills lands within 1e-6 Da, in both fixture sets
(`ProfileReconstructionGroundTruthTest`); the reference rule is up to 1.8e-5 Da off on spectrum 0. Between
the two rules, filled m/z differ by up to about 4.3e-5 Da on the current examples (Example_Processed.img), so
the golden comparison allows 5e-5 Da for filled points (§9.2). Porting the reference rule stays deferred
(§10).

---

## 5. CV term tables

`CvTerms` holds three term tables, generated once from psi-ms `4.2.11` as bundled with mzdata `0.68.0`.
Only `is_a` edges are followed (not `part_of`), obsolete terms are skipped, and each table is in accession
order:

| Constant | Rule | Terms |
|---|---|---|
| `ION_MOBILITY_TYPES` | direct children of MS:1002892 'ion mobility attribute' | 7 |
| `ION_MOBILITY_ARRAYS` | MS:1002893 'ion mobility array' and its descendants | 10 |
| `DISSOCIATION_METHODS` | descendants of MS:1000044 'dissociation method', with names | 22 |

- `ScanEvent.ionMobilityValue()` reads the first Param with a finite value whose accession is an ion mobility
  type, the types taken in mzdata's priority order (MS:1002476, MS:1002815, MS:1001581, MS:1003371, then the
  table order); `ionMobilityType()` is the type of that Param. Typed `ion_mobility_value`/`ion_mobility_type`
  columns of scans and selected ions become such a Param (`IonMobilityParams`) when the list holds none, with
  the PSI-MS name and unit the Rust reader gives the type (MS:1002815 'inverse reduced ion mobility drift time'
  in MS:1002814, for example; another type is 'ion mobility value' without a unit); a value without a type is
  MS:1002476 (drift time, in milliseconds).
- `Spectrum.ionMobility()` looks for an ion mobility array key (§3.6).
- `Activation.methods()` reports only `DISSOCIATION_METHODS`, which also name the dissociation-method marker's
  Param. MS:1002631 (EThcD) is a child of MS:1003181 'combined dissociation method', not of MS:1000044: it is
  not reported by `methods()` and stays in `Activation.parameters()`. `SUPPLEMENTAL_ACTIVATION` is a
  deprecated alias of `DISSOCIATION_METHOD`.

`IonMobilityAndActivationTermsTest` regenerates the three tables from the OBO file named by
`-Dmzpeak.psims.obo=<path>` and compares them with the constants.

---

## 6. Parquet without Hadoop

mzPeak Parquet files are ZSTD-compressed. parquet-java's default codec factory invokes a Hadoop
`Configuration` on every codec lookup, which pulls the shaded Woodstox/Hadoop runtime. mzPeakJ avoids this:

- **Reading/writing** use `LocalInputFile` / `ByteArrayInputFile` / `FileSliceInputFile` / `LocalOutputFile`
  + `PlainParquetConfiguration` + the parquet-column Group API (`ParquetGroups`).
- **Codec**: a custom `ZstdCompressionCodecFactory` backed by `zstd-jni` (self-contained native jar) is
  supplied to both `ParquetReadOptions.builder().withCodecFactory(...)` (reads) and
  `ParquetWriter.Builder.withCodecFactory(...)` (writes). The Hadoop `CodecFactory` and
  `Configuration` are **never** invoked.

**Caveat:** parquet-java's classes statically reference Hadoop types, so `hadoop-client-api` (a single
shaded jar with the Hadoop API stubs) must be on the classpath at compile and runtime. It is never used;
no Hadoop install, config, native library, or cluster setup is required.

### 6.1 Type variance

The spec allows physical-type variance:
- m/z: FLOAT or DOUBLE
- intensity: FLOAT, INT32, or DOUBLE
- Lists: `list` or `large_list`
- Strings: `string` or `large_string`

**mzPeakJ rule:** never assume a physical type. `ParquetGroups` dispatches on the actual
`PrimitiveTypeName` via `optDouble`/`optLong`/`doubleListNullable`, and navigates list structure by field
*position* so element names (`element`/`item`/`list`) are irrelevant. Index columns (uint64) are read as
signed Java `long`; values with the high bit set are rejected with a clear error. Metadata cells go through
`ColumnResolver`, which adds the unsigned handling of §2.2.

---

## 7. File-level metadata

File/run-level metadata is a set of JSON documents (shapes from `schema/*.json` in the HUPO-PSI repo):

| Key | JSON type | Key content |
|---|---|---|
| `file_description` | object | `contents: [Param]`, `source_files: [SourceFile]` |
| `instrument_configuration_list` | array | `[{id:int, components:[{component_type,order,parameters:[Param]}], parameters:[Param], software_reference:str}]` |
| `software_list` | array | `[{id:str, version:str, parameters:[Param]}]` |
| `data_processing_method_list` | array | `[{id:str, methods:[{order:int, software_reference:str, parameters:[Param]}]}]` |
| `sample_list` | array | `[{id:str, name:str, parameters:[Param]}]` |
| `run` | object | `{id:str, default_instrument_id:int, default_data_processing_id:str, default_source_file_id:str, start_time:RFC3339?, parameters:[Param]}` |
| `scan_settings_list` | array | `[{id:str, parameters:[Param]}]` (imaging pixel-raster settings) |

**Param record** (from `schema/param.json`):
- `name` (required string) — CV term name or user label
- `accession` (nullable string, format `\S+:\S+`) — e.g. `"MS:1000045"`; null = user param
- `value` (number | string | boolean | null)
- `unit` (nullable string, CURIE) — e.g. `"UO:0000266"` for electronvolt

**Index first.** The spec puts the documents in the manifest's `metadata` object; writers also copy them into
the key-value footer of `spectra_metadata.parquet`, and some only there. `FooterMetadataReader.resolve` reads
each document from the manifest when it is there, and reads the footer lazily, only for the documents the
manifest lacks. A malformed footer document that the manifest supplies is ignored; a malformed one that is
needed throws. Example_Processed.img has an empty `metadata` object, so all of its documents come from the
footer.

- The `run` is merged field by field, manifest first. The default instrument is read from
  `default_instrument_configuration_id` (the Rust reference's spelling), else `default_instrument_id` (the
  spec schema's, which the examples use); the params from `parameters`, else `params` (the examples'
  spelling).
- `scan_settings_list` comes from the manifest when it has one (even an empty one), else from the footer. A
  param's unit is read from `unit`, falling back to `unit_accession`.
- Missing documents yield empty or absent objects rather than errors.

**Writer run key.** `MzPeakWriter` writes a single run key, `default_instrument_id`: the spec schema
`ms_run.json` still lists it under `required`, and the Rust reader, which accepts either name, rejects a run
that has both as a duplicate field.

---

## 8. Architecture

```
org.mzpeak.model             pure data — no I/O deps
  interfaces: CoordinateLike, IntensityMeasurement, Indexed, KnownCharge
  peaks:      CentroidPeak(mz, intensity, index)
  spectra:    Spectrum · SpectrumDescription · Precursor · SelectedIon
              Activation · IsolationWindow · ScanEvent · ScanWindow · ImagingPosition
              Polarity · SignalContinuity
  other:      Chromatogram · WavelengthSpectrum
  queries:    DoubleRange · SignalSlice
  params:     Param · CvTerms · Tolerance(PPM|Da) · NativeId

org.mzpeak.model.meta        file/run-level metadata records
  FileMetadata (InstrumentConfiguration · Component · Software · DataProcessing · Sample
                · MsRun · FileDescription · SourceFile) · ScanSettings

org.mzpeak.io                reading + writing
  public       MzPeakReader · MzPeakWriter · MzPeakManifest · MzPeakSource · MzPeakException
               RandomAccessBytes · SignalQuery · ColumnStatistics (§8.2)
  containers   DirectorySource · ZipSource · ZipIndex · UnpackedSource
  metadata     SpectrumMetadataDecoder (shape dispatch) · MetadataShape · ParquetFooter
               Facet · ColumnTable · ColumnResolver · FacetRow · MetadataTable
               SplitMetadataReader · PackedMetadataReader (legacy) · SpectrumFacetAssembler
               SpectrumRowDecoder · ScanRowDecoder · PrecursorRowDecoder · SelectedIonRowDecoder
               PrecursorJoin · IonMobilityParams
               ChromatogramMetadataDecoder · WavelengthMetadataDecoder · FooterMetadataReader
               MetadataColumns (column statistics, encoded TIC/BPC)
  signal       SignalEntity · ArrayIndex · SignalPlan
               SpectrumArrayStore · ChunkRowDecoder · ChunkCodecs · GridChunk · GridModels · NullFill
               DoubleArrayBuilder
               ChromatogramStore · ChromatogramDataDecoder · WavelengthSpectrumStore · PointArrays
  writer       (§8.1)
               MzPeakWriter → WritePlanner → WritePlan → DatasetWriter
               SplitMetadataWriter · PackedMetadataWriter (MetadataTablesWriter)
               PointSignalWriter · NumpressSignalWriter · SpectrumSummary
               ParquetOutput · FooterMetadataWriter · ManifestWriter · ArchivePacker · WriteReport

org.mzpeak.io.parquet         Hadoop-free Parquet I/O
  ParquetGroups               type-variance-safe accessors (optDouble, readParams, groupList, …)
  ZstdCompressionCodecFactory custom ZSTD compressor + decompressor
  ByteArrayInputFile          in-memory Parquet member (for ZIP fallback)
  FileSliceInputFile          seekable byte-range slice of a RandomAccessBytes (STORED ZIP)

org.mzpeak.cli                MzPeakInfo (dataset summary)
org.mzpeak.examples           ExtractSpectra · ConvertToDta · ExtractXic · MzPeakFileInfo
ms.numpress                   bundled MS-Numpress decoders (Apache-2.0, vendored)
```

Only the classes marked public are API; everything else in `org.mzpeak.io` is package-private.
`MzPeakReader.open` decodes all spectrum metadata eagerly and opens the signal, chromatogram and wavelength
members lazily on first use. The decoders take `(MzPeakSource, MzPeakManifest)` and find their own members by
role.

### 8.1 Writer

`MzPeakWriter`'s public overloads delegate to one pipeline, whose settings are `MzPeakWriter.Options` (signal
encoding, Numpress intensity, row-group size, metadata layout). `WritePlanner` validates the input before any
I/O (non-negative, unique indices; in the split layout, no selected ions that readers would attach to another
precursor), numbers each entity 0..n-1 in index order and remaps `precursor_index`, routes the spectra
(CENTROID peak copies to `spectra_peaks` only, other arrays to `spectra_data`, peak lists to `spectra_peaks`),
re-sorts a main axis that is not ascending (by `Double.compare`, so rows with a NaN move to the end) and decides
which members are written. A CENTROID spectrum with a non-finite m/z keeps both files, a deviation from §11.5:
the reader builds a peak from every `spectra_peaks` row, and such a row would read back as an invented peak (the
null fill interpolates a NaN m/z, and Numpress linear cannot encode NaN or an infinity). `spectra_data` is always
written, in both layouts and both signal encodings, without a row when every spectrum is centroid, because both
Rust reference readers open it unconditionally. `DatasetWriter` then writes the metadata members, the signal
members (point layout, or MS-Numpress chunks of up to 500 points, never one of a single point unless the
spectrum has one, with linear m/z and SLOF or PIC intensity), chromatograms and wavelength spectra in point
layout, and the manifest last; `ArchivePacker` packs a directory into a STORED ZIP whose last entry is the
manifest. It streams each member twice, through a CRC-32 and then into the archive, so no member is held in memory
and a member may exceed 2 GiB (`ZipOutputStream` adds zip64 fields above 4 GiB). Every entry gets the time
2000-01-01T00:00 through `setTimeLocal` and no extra field. 1980-01-01T00:00 is avoided: it is the JDK's marker
for a time before 1980, which keeps the time as an instant in the default time zone and writes it as an
extended-timestamp (0x5455) field, so the bytes would depend on the zone; `setTime(long)` converts through the
zone too.

**Metadata layouts** (`Options.withMetadataLayout`):

- `SPLIT`, the default since WR-7: the current layout (§2.2), written by `SplitMetadataWriter`.
  `spectra_metadata` comes with its `_scans`, `_precursors` and `_selected_ions` tables,
  `chromatograms_metadata` with empty precursor and selected-ion tables, and `wavelength_spectra_metadata` with
  its `_scans` table. The manifest is in the current dialect: underscore roles, a `column_mapping` for every
  CV-mapped column, scan settings with `unit` and an `id` (one without gets `scan_settings_<position>`, made
  unique, with a WARNING, since the Rust reader needs it). Every scan, precursor and selected ion is written, and
  a Param moves to its standardized column only where the reader rebuilds it unchanged.
- `PACKED`: the pin-era dialect that mzPeakJ 0.10.3 and earlier wrote and read, written by
  `PackedMetadataWriter`: packed metadata structs (§2.4), the role spellings `data arrays` and
  `wavelength spectrum`, empty column mappings, and only the first scan and the primary precursor of a spectrum.
  Isolation-window Params go to the pin-era `isolation_window.parameters` list.
  The HEAD Python reader rejects it ("Old concatenated storage layout not supported").

Both layouts get format version 0.9.0, the `cv_list`, a SHA-512 checksum and an empty `parameters` list per
manifest entry, the array index and the entity and data-point counts in every signal footer, and the file-level
documents in both the manifest and the `spectra_metadata.parquet` footer.

**Determinism.** Within one JVM, the same input and options give the same bytes. Across JVM runs, the ZIP layer
and the content of every member are the same, but the footers of the Parquet members can differ: parquet-java
1.17.1 collects the encodings of each column chunk in a `HashSet` of enum constants
(`ParquetFileWriter.startColumn`) and writes them in its iteration order
(`ParquetMetadataConverter.toFormatEncodings`). Enum constants hash by identity, so that order can change from
one run to the next, and with it the member's bytes, its checksum in the manifest and its CRC in an archive. In
the MZ-9 investigation, 2 of 12 JVM runs writing current small.unpacked differed so; with the encodings of each
column chunk sorted, all 12 gave the same bytes for every member, so nothing in mzPeakJ adds to it. mzPeakJ
therefore claims determinism of member content (`ContentDigest`), not of Parquet bytes. `DeterminismProcessTest`
writes current small.unpacked, has_uv and Example_Processed.img as archives in six child JVMs each and compares
the ZIP entries (order, method, time, extra fields, sizes), the `ContentDigest` of every Parquet member and the
manifest with its checksums masked; it also packs fixed members in three time zones and compares the bytes, and
writes current small.unpacked in two time zones.

**Readers.** The default output is readable by the HEAD Python reader. The opt-in `UpstreamInteropTest` checks
this on legacy small.unpacked and current small.unpacked, has_uv, Example_Processed.img and diaPASEF.ref
(archive and directory), the Numpress SLOF and PIC archives of current small, the `ExtractSpectra` MS2
subset and current small without scans. The Python reader's arrays pass the golden rules against mzPeakJ's
read of each source, its counts match, and `check_archive_integrity()` returns `(True, [])`. Readability by the
Rust reference is untested.

Known limits of the HEAD Python reader (2ee2589) on that output, left to the maintainer's upstream reports and
pinned by no test, since a test would pin an upstream bug: it loses the selected ions of precursors whose
`precursor_index` is null when other precursors in the file keep theirs (`reader.py:421`: a pandas groupby
drops NaN keys), where mzPeakJ and the Rust reference attach them; and pynumpress cannot decode the 12-byte
Numpress linear payload of a single value, which the writer still produces for a spectrum of one point.

**Not written:** extra signal arrays (spectrum ion mobility, the TIC's `ms level`) and the chromatogram
intensity unit; each kind of drop logs one WARNING, and so does a `PACKED` write that drops scans, precursors or
selected ions. Nor the delta-`chunk` layout, chunked chromatograms or null-marked profiles (the reconstructed
arrays are written).

### 8.2 Reader API

`MzPeakReader` serves a dataset's spectra, chromatograms, wavelength spectra and file-level metadata; it is not
thread-safe. The README shows each feature in a short example. This section gives the contract and, at its end,
the places where mzPeakJ deliberately differs from the Rust and Python readers.

**Opening and options.** `open(Path)`, `open(Path, Options)`, `open(MzPeakSource)` and
`open(MzPeakSource, Options)`; `open(Path, boolean)` sets `reconstructProfile`. A reader owns its source and
closes it exactly once: when the open fails (also with an `Error`), or on the first `close()`, which closes both
signal readers and the source even when one of them fails and throws the first failure. After `close()`, the
methods that read the dataset throw `IllegalStateException` (spectra, chromatograms, wavelength spectra, queries,
footer-backed metadata not cached yet); what the reader holds in memory stays available.
`MzPeakReader.Options` is an immutable final class with value equality: `Options.defaults()`,
`withReconstructProfile(boolean)` (default `true`, §4.2) and `withSignalMode(SignalMode)` (default `BOTH`).
`options()` returns the options a reader was opened with.

| `SignalMode` | Members read | `mz()`, `intensity()`, `arrays()` | `peaks()`, `peakArrays()` |
|---|---|---|---|
| `BOTH` (default) | data arrays and peaks | the data arrays, else the centroids in stored order (§3.4) | the centroids |
| `PROFILE` | data arrays only | the data arrays, else empty | empty |
| `CENTROID` | peaks only | the centroids in stored order, else empty | the centroids |

A spectrum read in `PROFILE` or `CENTROID` mode is a partial view of what the dataset stores. It must not be
passed to `MzPeakWriter`, which would write only that part; read with `BOTH` before writing.

**Range queries.**

| API | Contract |
|---|---|
| `DoubleRange(low, high)` | a closed interval; a bound may be infinite; a NaN bound or `low > high` throws `IllegalArgumentException` |
| `SignalQuery` | immutable: `all()`, `timeRange(start, end)` or `indexRange(first, last)`, narrowed by `withMz(low, high)`, `withIonMobility(low, high)` and `withMsLevels(min, max)`; every range is closed and compared exactly |
| `select(query)` | the selected `SpectrumDescription`s in index order, from the metadata in memory; reads no signal, and the m/z and ion-mobility filters play no part |
| `extractSignal(query)` | a `Stream<SignalSlice>` with one slice per selected spectrum from the data arrays member, keeping the points that pass the m/z and ion-mobility filters; `reconstructProfile` applies |
| `queryPeaks(query)` | the same from the peaks member: the peaks in stored order, non-finite m/z dropped |

`DoubleRange.contains(double)` tests a value against the closed interval.
`SignalSlice(index, time, msLevel, mz, intensity, ionMobility)`, with `size()` its number of points: a spectrum
without points in the member gives an empty slice, and `ionMobility` is `null` when the spectrum has no ion
mobility array. The ion-mobility filter applies only to the spectra that have one. The streams are lazy and
sequential, decode only the row groups of the selected spectra, ignore the reader's `SignalMode`, and throw
`IllegalStateException` for a slice requested after `close()`. A slice that the filters leave whole shares its
arrays with the reader's cache, like `Spectrum.mz()`.

**Column statistics and observed ranges.**

| API | Source |
|---|---|
| `columnStatistics(entity, kind, accession)` | an `Optional<ColumnStatistics>`: `ColumnStatistics(member, column, accession, rowGroups)` with `complete()`, `min()` and `max()`, from the Parquet row-group statistics in the footer of the member that holds the column, without reading a row. The column is resolved by accession through `MetadataShape` and `ColumnResolver` (§2), in the packed layout also by a unique field with the CURIE prefix (`MS_1000016_…`). Empty for the data arrays and peaks roles, a role without a metadata member, a term that no column holds, and a column that is not INT32, INT64, FLOAT or DOUBLE |
| `observedMzRange()` | the smallest row-group minimum of MS:1000528 (lowest observed m/z) and the largest maximum of MS:1000527 (highest observed m/z) in the spectrum metadata; empty unless both statistics are complete; throws `MzPeakException` when that lowest m/z exceeds that highest m/z |
| `observedTimeRange()` | the smallest and largest finite retention time of the spectrum metadata held in memory; reads nothing |

**TIC and BPC.**

| API | Result |
|---|---|
| `tic()`, `bpc()` | the first of `chromatograms()` of the type MS:1000235 (total ion current chromatogram), resp. MS:1000628 (basepeak chromatogram); else `encodedTic()`, resp. `encodedBpc()` |
| `encodedTic()`, `encodedBpc()` | one point per spectrum, in index order, that has a total ion current (MS:1000285), resp. a base peak intensity (MS:1000505), and a finite retention time; index 0 and id `TIC`, resp. index 1 and id `BPC`; no intensity unit and no arrays. Empty when the spectrum metadata has no such numeric column; never one of `chromatograms()` |

`chromatograms()`, `getChromatogram(long)` and `getChromatogramById(String)` return stored chromatograms only.

**Extra arrays.** `Spectrum.arrays()`, `peakArrays()`, `array(String)`, `ionMobility()` and
`ionMobilityArrayType()` (§3.6). `Chromatogram.arrays()` holds a chromatogram's other per-point arrays, parallel
to `time()`, such as the `ms level` array of the small* and has_uv TICs, and `Chromatogram.intensityUnit()` the
unit CURIE of its intensities (§3.5). The 5-argument `Chromatogram` constructor sets no unit and no arrays.

**Isolation windows.** `IsolationWindow.parameters()` holds the window's other CV and user params, read and
written in both metadata layouts (§2.2, §8.1); the 3-argument constructor sets an empty list.

**Sources.** `MzPeakSource.open(Path)` opens a local directory or archive. For other storage, such as an object
store, the caller supplies the bytes:

- `RandomAccessBytes`: `size()`, `read(ByteBuffer, long)` (a positional read that keeps no position of its own
  and is safe for concurrent calls) and `close()`; `RandomAccessBytes.of(FileChannel)` wraps a channel.
- `MzPeakSource.zip(description, bytes)` reads a single-file archive through one `RandomAccessBytes`: the
  central directory when the source is created, then STORED members in place, only the ranges a reader asks
  for. The source owns `bytes`.
- `MzPeakSource.unpacked(description, opener)` reads an unpacked dataset whose members a `MemberOpener` opens as
  `RandomAccessBytes`, lazily, when a reader first asks for them. A member name that is absolute or contains a
  `..` segment is rejected before the opener is called. The source closes every member it opened.

What a reader expects of any `MzPeakSource` (member names, concurrent streams, ownership) is stated in its
Javadoc. `FileSliceInputFile(RandomAccessBytes, long, long)` gives parquet-java a member as a slice of such bytes.

**Manifest.** `MzPeakManifest.ColumnMapping` has a sixth component, `unitPath`: the path of a column that holds a
unit CURIE per row, for a `unit` given as a JSON array of segments. The 6-argument canonical constructor sets it,
and the 5-argument one leaves it null. `equals` and `hashCode` include it.

**Grids** need no API: grid chunks decode like the other chunk encodings (§3.3).

**Integrity.** There is no integrity check yet: the manifest's SHA-512 checksums are parsed but not verified on
read (§10).

**Deviations from the reference readers.** All are deliberate; the tests named pin them:

- **Explicit signal modes return empty arrays.** `PROFILE` and `CENTROID` follow the spec text (`spectra.md`):
  when the requested representation is absent, the arrays are empty. The Rust reader's
  `SignalLoadingPreference` only chooses when a spectrum has both and otherwise reads whichever it has; its
  default prefers the profile, where mzPeakJ's default `BOTH` reads both (`SignalModeTest`).
- **Partial views.** A spectrum read in `PROFILE` or `CENTROID` mode lacks the other member's arrays and must not
  be written back (above).
- **Exact intervals: 562, not 563.** Every range is closed and compared exactly. On current small, with
  `reconstructProfile=false`, `extractSignal(SignalQuery.timeRange(0.3, 0.4).withMz(800, 820))` gives 562 points;
  Rust's `extract_signal` gives 563, because its tolerant (`isclose`) bounds also admit a stored point just above
  820. With `withMz(800, 820.00002)` mzPeakJ gives 563 too (`SignalQueryTest`).
- **No implicit TIC or BPC.** In the Rust reader, `get_chromatogram_by_id("TIC")`/`("BPC")` and
  `get_chromatogram_by_index(0)`/`(1)` fall back to the chromatogram encoded in the spectrum metadata when none is
  stored. mzPeakJ's `getChromatogramById`, `getChromatogram` and `chromatograms()` return stored chromatograms
  only; the encoded ones are `encodedTic()` and `encodedBpc()`, and `tic()`/`bpc()` pick a stored chromatogram by
  its type, not by its index. Index 1 is not a BPC in every file (in has_uv it is the 'DAD1 A' absorbance trace),
  and a stored TIC can differ from the encoded one, as on current small (`EncodedChromatogramTest`).
- **`observedTimeRange()` comes from the retention times.** Rust's `observed_time_range` reads the row-group
  statistics of the MS:1000016 scan start time column; mzPeakJ takes the retention times it holds in memory: a
  spectrum's `time`, else its earliest scan start time (§2.3). Both give (0.004935, 0.487236666667) on current
  small (`ColumnStatisticsTest`).
- **No integrity check yet.** The Rust and Python readers verify the manifest checksums
  (`check_archive_integrity`); mzPeakJ does not (above).
- **Grid m/z and the Python reader.** The timsTOF m/z model keeps the fused multiply-add of `convert_f64`; the
  HEAD Python reader computes without it, so its diaPASEF.grid m/z differ from mzPeakJ's, within relative
  1e-12 (`GridDecodeTest`, `GridModelsTest`).

---

## 9. Testing

The default build runs every test on the vendored fixtures and never needs Python, Rust or the corpus:

```
mvn -B -Dmzpeak.corpus.dir=/nonexistent-mzpeak-corpus verify
```

CI runs `mvn verify` on JDK 17 and 21 via GitHub Actions.

### 9.1 Fixture sets

`Fixtures` names both sets (`CURRENT`, `LEGACY`, `current(name)`, `legacy(name)`). The current files are
byte-identical to upstream `2ee2589`.

**Current set** — `src/test/resources/mzpeak/`, upstream `2ee2589` (split metadata, array index, SHA-512
checksums):

| Fixture | Form | Signal layout | Contents |
|---|---|---|---|
| `small.unpacked.mzpeak/` | unpacked directory | point | 48 spectra (14 MS1 profile + 34 MS2 centroid), 1 TIC chromatogram (48 points) |
| `small.mzpeak` | STORED ZIP | point | same as small.unpacked |
| `small.chunked.mzpeak` | STORED ZIP | delta chunk (spectra and TIC) | same, plus centroid peaks for the 14 profile spectra |
| `small.numpress.mzpeak` | STORED ZIP | Numpress chunk (linear m/z, SLOF intensity) | same 48 spectra, profiles without null marking |
| `has_uv.mzpeak` | STORED ZIP | point | 212 MS1 centroid spectra, 520 UV/DAD wavelength spectra, 2 chromatograms (TIC, DAD1 A) |
| `diaPASEF.ref.mzpeak` | STORED ZIP | point, peaks member only | 9 spectra (1 MS1 + 8 MS2) of a Bruker diaPASEF run with per-peak ion mobility (MS:1003006); TIC and BPC |
| `diaPASEF.grid.mzpeak` | STORED ZIP | coordinate-grid chunks (timsTOF m/z and 1/K0 grids, provisional) | the same run; its spectra decode to those of diaPASEF.ref bit for bit (§3.3) |
| `Example_Processed.img.mzpeak` | STORED ZIP | point | 9 profile spectra of an imzML imaging run with pixel positions; no chromatograms |

**Legacy set** — `src/test/resources/mzpeak-legacy/`, upstream `29e59b2` (packed metadata): `small`,
`small.chunked`, `small.numpress`, `small.unpacked` (the same 48-spectrum run) and `has_uv` (212 MS spectra,
520 UV/DAD spectra, 2 chromatograms). It guards the packed layout that mzPeakJ reads, writes on request
(`PACKED`, §8.1) and the corpus uses. `small.mzML` is not vendored.

### 9.2 Oracles and tolerances

**FX-1b — HEAD Python golden** (`src/test/resources/mzpeak/golden/`). `generate_golden.py <dataset> [--out DIR]
[--meta]` runs the Python reader of `2ee2589` with pinned packages (`uv-requirements.txt`, CPython 3.13.12)
and writes per fixture:

- `<name>.tsv`: one summary line per entity — S (spectra), P (peak lists), X (extra arrays, keyed by
  array_type), C (chromatograms), W (wavelength spectra), R (raw pyarrow cells of point-layout data members) —
  with n, n_filled, exact sums of x, y, x·y, i·x, i·y and the first and last 3 pairs, at `%.17g`;
- `<name>.arrays.tsv`: every point (A lines, with filled flags) of spectrum 0, the first centroid spectrum,
  the last spectrum and every chromatogram. An entity above **20,000** points is a D line instead: n,
  n_filled and the SHA-256 of the little-endian float64 bytes of x, y and each extra array. The fixture's
  smallest spectrum is then stored in full as well. Spectrum 0 of diaPASEF.ref and diaPASEF.grid (205,921
  points) is a D line. Formatted text is never digested, because Java and Python render `%.17g` differently.

`Golden` parses the files and `Golden.Comparator` compares arrays; `GoldenComparatorTest` checks both.

**FX-2 — raw metadata cells.** With `--meta` the generator also dumps every row of every metadata, scans,
precursors and selected_ions member with pyarrow alone (`<name>.meta.jsonl`), so the cells do not depend on
how either reader interprets them. `GoldenMeta` reads them; `GoldenMetaTest` checks them against an
independent parquet-java read.

**Regeneration.** The command, the pinned environment and the regeneration loop are in
`src/test/resources/mzpeak/golden/README.md`. The loop reproduces the committed files byte for byte on the
same platform; the build never runs Python, and the reference checkout is never written to.

**Tolerances** (`Golden.Tolerance.DEFAULT`):

| Quantity | Tolerance |
|---|---|
| n, stored values | exact |
| filled m/z | 5e-5 Da (§4.3) |
| sums and pairing statistics | relative 1e-12; 1e-9 when filled points are involved |
| digest lines (n_filled = 0) | bit-exact |

**Cross-fixture checks:** small.chunked and small.mzpeak against small.unpacked (`LayoutEquivalenceTest`);
the unmarked small.numpress profiles as ground truth for the fill (`ProfileReconstructionGroundTruthTest`);
legacy against current small; small.chunked against small.unpacked for chromatogram extras; the manifest
SHA-512 checksums (`UpstreamFixtureIntegrityTest`).

### 9.3 Characterization snapshots

These pin the output of the code before a restructuring; a change that alters it on purpose updates the
expected values and says why.

| Test | Pins |
|---|---|
| `LegacyMetadataSnapshotTest` (CH-1) | SHA-256 of the canonical lines of every spectrum description, chromatogram and wavelength header of the legacy set |
| `SignalSnapshotTest` (CH-2) | SHA-256 over the raw bits of decoded arrays: for the legacy set, through the public API, every spectrum's m/z, intensity and centroids, every chromatogram and every wavelength spectrum; for the current set except diaPASEF.grid, only the m/z and intensity of each spectrum in the data arrays and peaks members, read through `SpectrumArrayStore.load` with an empty delta-model map. No extra array is pinned |
| `ContainerNegativePathTest` (CH-3) | stable message fragments of the ZIP and directory error paths, on corrupted copies of legacy fixtures |
| `WriterSnapshotTest` | content digests of every member the writer produces from legacy fixtures (`src/test/resources/writer-snapshots/`); on a mismatch the actual TSV is written to `target/writer-snapshots/` |

### 9.4 Reference ports and opt-in harnesses

- `RustReferenceTest` ports the `reader.rs` test assertions (spectrum 0 → 13,589 points, spectrum 5 → 650
  peaks, spectrum 25 → 789 peaks, TIC 48 points); `CrossImplTest` and `EicAndCoverageTest` port the Python
  `common_checks()` (34 selected ions, spectrum 4 → 837 peaks, EIC 96 centroid peaks, secondary peaks for
  chunked, native ID format, isolation window). They run on the legacy set.
- **Opt-in**, never part of the default build:
  - `CorpusTest`: `-Dmzpeak.corpus.dir=<dir>` opens every `.mzpeak` under the directory;
  - `ValidatedRoundTripTest`, `ForwardBackwardCorpusTest`: `-Dmzpeak.roundtrip=true` reads, re-writes,
    validates with the external `mzpeak-validate` and re-reads every corpus file (`ValidatedRoundTripTest`
    as a directory and as an archive);
  - `UpstreamInteropTest`: `-Dmzpeak.upstream.python=<python/ of a HUPO-PSI/mzPeak checkout at 2ee2589>`,
    with `uv` installed, reads mzPeakJ's output with the HEAD Python reader (§8.1) in the pinned environment
    of the golden generator (§9.2);
  - `Zip64Test`: `-Dmzpeak.zip64.file=<archive>` also opens a real zip64 archive;
  - `IonMobilityAndActivationTermsTest`: `-Dmzpeak.psims.obo=<path>` regenerates the CV tables (§5).

### 9.5 Coverage gate

`mvn verify` runs the tests under the JaCoCo agent, which instruments only `org.mzpeak.*` and
`ms.numpress.*`, writes the report and then runs `jacoco:check` (`pom.xml`, execution `check`):

| Rule | Lines | Branches | Methods |
|---|---|---|---|
| every package `org.mzpeak` and `org.mzpeak.*` | 100% | 100% | 100% |
| `ms.numpress` (vendored) | ≥ 0.9951 (207/208) | ≥ 0.9924 (131/132) | 100% |

A package-level ratio of 1.0 means that no class in it misses a line, branch or method; no `org.mzpeak`
class is excluded. `ms.numpress` is vendored and stays byte-identical, so it gets its own rule at exactly
the coverage reached: the only gap is `MSNumpress.java` line 73-74 in `decode(String, byte[], int)`, the
true branch of `if (nbrOfDoubles < 0)` and its `Corrupt numPic data!` throw. `decodePic` returns its output
count, which starts at 0 and only grows, so no input reaches the throw.

The report is written before the check, so a failing build leaves it in `target/site/jacoco/`. The log names
each package that breaks a rule and the ratio it reached; `index.html` leads from package to class to the
source, where red lines were never run and yellow lines have a branch that was never taken (the diamond in
the margin gives the count); `jacoco.csv` has a row per class with `LINE_MISSED`, `BRANCH_MISSED` and
`METHOD_MISSED`, and `jacoco.xml` has the counts per line. A new gap is closed with a test that asserts
behaviour on a fixture (or a constructed input where no fixture reaches the path), or, if no input can reach
it, by removing the dead code and saying why in the commit message; never by an exclusion.

The gate needs the whole suite. For quick local runs, such as one test with `-Dtest=...`, pass
`-Djacoco.skip=true`, which skips the agent, the report and the check. `mvn test` and `mvn package` stop
before `verify` and never run the check.

---

## 10. Known limitations / deferred

- **Grids.** The timsTOF grid accessions `MS:9999001` and `MS:9999002` are provisional (§3.3). Grids are decoded
  in spectrum signal members only; a chromatogram chunk is read as deltas whatever its encoding (§3.5).
- **Writer** (§8.1). Extra signal arrays and the chromatogram intensity unit are not written; each kind of drop
  logs a WARNING. Not written either: the delta-`chunk` layout, chunked chromatograms and null-marked profiles
  (the reconstructed arrays are written). The `PACKED` layout writes only the first scan and the primary
  precursor of a spectrum, with a WARNING when it drops more.
  Whether the Rust reference reads mzPeakJ's output is untested.
- **Integrity.** Manifest checksums are not verified on read, unlike in the Rust and Python readers (§8.2).
- **Byte determinism.** The Parquet members, and with them their checksums and CRCs, can differ between JVM runs
  in the order of a column chunk's encodings in the footer, which parquet-java takes from a `HashSet` (§8.1);
  their content does not.
- **ZIP.** A duplicate member name throws instead of resolving to the last one.
- **Dataset checks.** A dataset without a spectrum metadata member throws; the format version is not checked.
- **Metadata not decoded:** products facets; chromatogram precursor and selected-ion facets; the wavelength
  scans facet; Params of chromatograms and wavelength spectra; precursor-scope leftovers;
  leftover Params in the legacy packed layout.
- **Precursor join.** The reference-identical join (precursors in reverse, last precursor for unmatched ions,
  orphan ions dropped, null-m/z ions kept) is not ported (§2.3).
- **Null fill.** The reference readers' local-median rule is not ported (§4.3).
- **Provenance and signature** members are ignored, and the items of the draft imaging profile (such as a
  pixel-grid API) are not implemented.
- **Extra arrays** of wavelength spectra are not read; no example has one.
- **Predicate/page-level pushdown within a row group** — row groups are skipped by min/max statistics, but
  all pages within a selected group are decoded.
- **Maven Central** publication.

---

## 11. Tooling notes

- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@25 && mvn verify`
- Run an example: `./run-example.sh org.mzpeak.examples.MzPeakFileInfo src/test/resources/mzpeak/small.mzpeak`
- Inspect a fixture: `./run-example.sh org.mzpeak.cli.MzPeakInfo src/test/resources/mzpeak/has_uv.mzpeak`
- Re-deriving golden values: see `src/test/resources/mzpeak/golden/README.md` (§9.2)
- Javadoc: `mvn javadoc:javadoc -Ddoclint=none` → `target/reports/apidocs`. Without `-Ddoclint=none` the goal
  writes the pages but fails on doclint errors in the vendored `ms.numpress` sources and in `ScanSettings`.
