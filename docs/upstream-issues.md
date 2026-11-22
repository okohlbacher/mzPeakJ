# Known issues of the Rust reference reader

Found while checking mzPeakJ-written files with the Rust reference reader (`mzpeak_prototyping` of
[HUPO-PSI/mzPeak](https://github.com/HUPO-PSI/mzPeak) at `2ee2589`, 2026-11-10). The issues are not filed upstream
(maintainer, 2026-11-11). Each but R-10 also shows on the reference's own example files, so none is caused by
mzPeakJ; R-10 follows from a spec rule mzPeakJ keeps (see below).

The reader is checked with [`tools/rdump`](../tools/rdump/README.md), a small dumper on the reference's public API.
`RustReaderAcceptanceTest` (CI job `rust-reader`) expects R-1, R-2, R-3 and R-10 on the files mzPeakJ writes and fails
on any other unreadable entity; when one of them no longer occurs, the test fails too, so the table is updated.

| # | Problem | Where it shows | Effect |
|---|---|---|---|
| R-1 | `get_spectrum_peaks_for` panics for a spectrum without peak rows | small.mzpeak (12 spectra), converted tiny.pwiz | spectrum unreadable through the peaks API |
| R-2 | A chromatogram with a null type panics | Example_Processed.img, CK1 corpus file | chromatogram unreadable |
| R-3 | Wavelength spectrum retention time reads as 0.0 | the reference's own has_uv.mzpeak (all 520) | wrong time |
| R-4 | A missing collision energy or selected-ion intensity reads as 0.0 instead of absent | several fixtures | absent value reported as 0 |
| R-5 | Names of non-standard data arrays are lost | converted mzML with named arrays | array identity lost |
| R-6 | Null m/z values filled less precisely than mzPeakJ: median error against the mzML 9.8e-8 (Rust) vs 3.9e-9 (mzPeakJ) on small; up to 1.7e-5 differences | small.mzpeak, Example_Processed.img | m/z precision |
| R-7 | Ion mobility of centroid peaks not exposed, even with the ion-mobility-aware peak types | diaPASEF | data not reachable |
| R-8 | `AuxiliaryArrayVisitor` requires 64-bit Arrow list/string types (`as_list::<i64>`), panicking on Parquet files without an `ARROW:schema` footer (as written by non-Arrow writers) | any file whose metadata carries `auxiliary_arrays` without an Arrow hint | wavelength spectra unreadable (mzPeakJ now writes the hint) |
| R-9 | The packed (legacy) layout no longer opens (`metadata.rs:334`) | the 5 legacy upstream fixtures themselves | legacy files unreadable |
| R-10 | A null cell of an extra array reads as 0.0 (`src/reader/point.rs:128`, `val.unwrap_or_default()`) instead of NaN or absent; so does a null element of a Numpress chunk's extra list | any file with a null extra cell; mzPeakJ writes a NaN extra cell as null since 0.13.0 (W10) | value reported as 0 |

How the test sees them on mzPeakJ's own output:

- R-1 shows for the spectra without peak rows whose index lies between the first and the last spectrum with peak rows
  (the reader picks the row group by its index range, then panics when the index is not in it); before the first
  and after the last such spectrum, the peak list is empty instead.
- R-2 does not show on Example_Processed.img written by mzPeakJ: the file has no chromatograms, and for such a
  dataset the reader counts the TIC and BPC it derives from the spectra but `get_chromatogram` returns none for them.
  The test therefore also writes small.unpacked with its chromatogram without type.
- R-3 shows on every wavelength spectrum of has_uv.
- R-10 shows on the extra array of a spectrum whose cell is NaN. mzPeakJ writes such a cell as null, since
  parquet-java 1.18.2 writes no column index for a page holding a NaN and the spec requires a page index for every
  column (`conformance.md`, l.36); no form satisfies both the spec and the Rust reader, which read the stored NaN as
  NaN before. `RustReaderAcceptanceTest` writes the first three points of small.unpacked's spectra 0 and 1, spectrum
  1 with the signal-to-noise array `[1, NaN, 3]`, in the point layout and as Numpress, and expects rdump to read
  `[1, 0, 3]` (mzPeakJ reads `[1, NaN, 3]`). rdump's record of spectrum 1 of the point-layout archive, with the
  values its `.bin` file holds for each array:

  ```text
  "SignalToNoiseArray": {"ctype": "SignalToNoiseArray", "dtype": "Float64", "n": 3, "off": 6, "unit": "Unknown"}
  MZArray             [200.0909090936184, 200.1818181872368, 200.27272728085518]
  IntensityArray      [449.0517272949219, 626.677001953125, 727.1613159179688]
  SignalToNoiseArray  [1.0, 0.0, 3.0]
  ```

A latent difference, not seen by the test: the reference's `ScanSettings` struct still uses the pin-era names,
`source_file_refs` and targets as bare param arrays, while the spec's `schema/scan_settings_list.json` names the
references `source_file_references` and makes each target an object with its `parameters`, which mzPeakJ's split
layout writes. The Rust and Python readers do not parse `scan_settings_list`, so nothing breaks today.
