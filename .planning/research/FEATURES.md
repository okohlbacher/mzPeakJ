# Features Research — mzPeakJ (reader)

What an mzPeak *reader* must / may do. Categorized for v1 scoping.

## Table stakes (v1 — must have or the reader is useless)

| Feature | Notes | Complexity |
|---|---|---|
| Open an unpacked `*.mzpeak` directory | Locate + parse `mzpeak_index.json`; dispatch files by `(entity_type, data_kind)` | Low |
| List spectra with core metadata | index, id (nativeID), MS level, retention time, polarity, signal continuity (centroid/profile) | Med |
| Precursor info for MSn | selected-ion m/z, charge state, isolation window target/offsets — joined from `selected_ion`/`precursor` facets | Med |
| Materialize peaks for a spectrum | `point`-layout `spectra_data.parquet` → `double[]` m/z + `double[]` intensity by `spectrum_index` | Med-High |
| Centroid peaks when present | same from `spectra_peaks.parquet` (only if manifest lists `data_kind:"peaks"`) | Low |
| Random access by index | row-group/page min-max stats on sorted `spectrum_index` + binary search | Med-High |
| Sequential iteration | `Iterator<Spectrum>` / `ExactSizeIterator`-style | Low |
| FragPipe conversion | `Spectrum → IScan/ISpectrum` (MSFTBX) round-trip | Med |
| Tests on real data | HUPO-PSI `small.unpacked.mzpeak/` fixtures | Low |

## Differentiators (nice, but defer past v1)

- Lookup by scan number (parse vendor scan no. from `id` nativeID) and by retention time (RT→index map)
- Lazy vs eager array loading + row-group caching (perf)
- Detail-level control (metadata-only vs full peaks) mirroring `mzpeak_prototyping`'s `DetailLevel`
- Tolerance-based peak search (`Tolerance::{PPM,Da}`) on a spectrum's peak list

## Anti-features (deliberately NOT building in milestone 1)

- Writing mzPeak
- Single-file ZIP random access
- `chunk` layout + MS-Numpress/delta decoding
- Chromatograms / wavelength spectra
- Profile reconstruction (null-marking/zero-run fill via `mz_delta_model`)
- Ion-mobility array handling beyond passing through if trivially present

## Dependencies between features

manifest parse → metadata read → (precursor join) → index/random-access → array materialization → FragPipe adapter.
Metadata read is the spine; array materialization is the highest-risk piece (Parquet pushdown + type variance).
