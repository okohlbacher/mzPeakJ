# Known issues of the Rust reference reader

Found while checking mzPeakJ-written files with the Rust reference reader (`mzpeak_prototyping` of
[HUPO-PSI/mzPeak](https://github.com/HUPO-PSI/mzPeak) at `2ee2589`, 2026-11-10). The issues are not filed upstream
(maintainer, 2026-11-11). Each also shows on the reference's own example files, so none is caused by mzPeakJ.

The reader is checked with [`tools/rdump`](../tools/rdump/README.md), a small dumper on the reference's public API.
`RustReaderAcceptanceTest` (CI job `rust-reader`) expects R-1, R-2 and R-3 on the files mzPeakJ writes and fails
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

How the test sees them on mzPeakJ's own output:

- R-1 shows for the spectra without peak rows whose index lies between the first and the last spectrum with peak rows
  (the reader picks the row group by its index range, then panics when the index is not in it); before the first
  and after the last such spectrum, the peak list is empty instead.
- R-2 does not show on Example_Processed.img written by mzPeakJ: the file has no chromatograms, and for such a
  dataset the reader counts the TIC and BPC it derives from the spectra but `get_chromatogram` returns none for them.
  The test therefore also writes small.unpacked with its chromatogram without type.
- R-3 shows on every wavelength spectrum of has_uv.
