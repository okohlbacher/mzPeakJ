# Known issues of the Rust reference reader

Found while checking mzPeakJ-written files with the Rust reference reader (`mzpeak_prototyping` of
[HUPO-PSI/mzPeak](https://github.com/HUPO-PSI/mzPeak) at `2ee2589`, 2026-11-10). The issues are not filed upstream
(maintainer, 2026-11-11). Each but R-10 to R-17 also shows on the reference's own example files, so none is caused
by mzPeakJ; R-10, R-11 and the first half of R-12 follow from spec rules mzPeakJ keeps, the second half of R-12 is not
traced yet, R-13, R-14, R-15 and R-17 show only on auxiliary arrays, which no example file holds and which mzPeakJ
writes since 0.13.0, and R-16 only on a param unit or accession that is not a CURIE, which no example file holds (see
below).

The reader is checked with [`tools/rdump`](../tools/rdump/README.md), a small dumper on the reference's public API.
`RustReaderAcceptanceTest` (CI job `rust-reader`) expects R-1, R-2, R-3, R-10, R-11, R-13 and R-14 on the files
mzPeakJ writes and fails on any other unreadable entity; when one of them no longer occurs, the test fails too, so the
table is updated.
R-12 and R-15 to R-17 were found with rdump or in the reference's source by hand (2026-11-23) and are not asserted by
the test yet.

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
| R-11 | The range read `extract_signal` projects only the first intensity column of the point layout (`src/reader/point.rs:528`, `array_indices.get(&ArrayType::IntensityArray)`), so a spectrum whose intensities are in another column, such as `intensity_MS_1000132` (percent of base peak), comes back without intensity; reading it by index (`get_spectrum`) is right | any spectra_data with spectra in two intensity units; mzPeakJ writes one intensity column per unit since 0.13.0 (R5) | intensities missing in range reads |
| R-12 | In the Numpress (chunked) layout, (a) the chunk decoder labels the intensity of a spectrum stored in a per-unit transform column, such as `intensity_MS_1000132_numpress_slof_bytes`, as `DetectorCounts` (the values are right), and (b) the range read `extract_signal` panics with "list array" | (a) any Numpress spectra_data with spectra in two intensity units, which mzPeakJ writes since 0.13.0 (R5); (b) mzPeakJ's Numpress output, also with one intensity unit; not the reference's small.numpress | (a) wrong intensity unit; (b) range reads of Numpress data fail |
| R-13 | A compressed auxiliary array does not decode: `AuxiliaryArrayVisitor` (`src/reader/visitor.rs:1880-1925`) hands the stored bytes to mzdata as the array's data with its compression, and mzdata takes the data of a compressed array for base64 text ("The content was not properly Base64 encoded"); auxiliary-arrays.md stores the compressed bytes themselves ("No base64 encoding is used") | any auxiliary array with a compression other than MS:1000576, such as zlib (MS:1000574) | array unreadable |
| R-14 | The auxiliary arrays of a chunked spectrum whose chunks span several row groups are dropped: the chunk path of `get_spectrum_arrays` (`src/reader.rs:552-565`) returns before `load_auxiliary_arrays_for_spectrum`, which the single-row-group and point paths call | a spectrum with auxiliary arrays in the chunked (Numpress) layout across a row-group boundary | arrays missing |
| R-15 | `AuxiliaryArrayVisitor` panics on an auxiliary array whose name has no accession or one mzdata's `ArrayType::from_accession` does not map, or whose data type or compression `BinaryDataArrayType::from_accession` or `BinaryCompressionType::from_accession` does not map (`src/reader/visitor.rs:1896-1897`, `1912`, `1922`, each `.unwrap()`); auxiliary-arrays.md only requires CV terms | an auxiliary array named MS:1002478 (mean charge array), or of another CV data type or compression; mzPeakJ refuses to write such arrays (provisional D13-28) | entity unreadable |
| R-16 | A param unit or accession that is not an mzdata CURIE (`PREFIX:number`, the number a u32) panics in every `parameters` list: `ParameterVisitor` reads accessions and units through `AnyCURIEArray` and `UnitArray` (`src/reader/visitor/curie_array_visitor.rs:379`, `389`), which call `parse().unwrap()` (`:34`, `:70`, `:257`) | a param with the unit `seconds` (which C6 reads and D13-26 writes as given) or the accession `MS:abc`, seen on spectra, scans and wavelength spectra; chromatogram params go through the same visitor | entity unreadable |
| R-17 | The auxiliary arrays of a spectrum with 0 data points are dropped: `get_spectrum` reads the arrays, and with them the auxiliary arrays, only when the spectrum's `data_point_counts` entry is above 0 (`src/reader.rs:1796-1831`) | a spectrum without profile points, such as a centroid-only one, that has auxiliary arrays | arrays missing |

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

- R-11 shows on a spectrum stored in an intensity column other than the first. The spec requires a column for each
  intensity unit (`signal-data.md`, §Buffer priority and naming: arrays of different units "must be defined as
  separate arrays in the array index and therefore have distinct names"), so no form satisfies both the spec and the
  Rust range read. `RustReaderAcceptanceTest` writes the two-unit copy of current small.unpacked (spectrum 1 cut to
  two points whose intensities [25, 100] are in percent of base peak, MS:1000132) and expects rdump to read spectrum
  1 by index with those intensities in `PercentBasePeak`, and its range read over spectra 0-1
  (`rdump ... --signal-range 0 1`) to give spectrum 1's two points without intensity. rdump's records, with the values
  of its `.bin` file:

  ```text
  {"kind":"S","index":1, ... "arrays":{"IntensityArray":{"ctype":"IntensityArray","dtype":"Float64","n":2,"off":27178,"unit":"PercentBasePeak"},"MZArray":{"ctype":"MZArray","dtype":"Float64","n":2,"off":27180,"unit":"MZ"}}}
  IntensityArray      [25.0, 100.0]
  MZArray             [200.0909090936184, 200.1818181872368]
  {"columns":["spectrum_index","mz","intensity"],"index":0,"int_off":514371,"intensity_non_null":13589,"kind":"R","mz_off":500782,"n":13589}
  {"columns":["spectrum_index","mz","intensity"],"index":1,"int_off":527962,"intensity_non_null":0,"kind":"R","mz_off":527960,"n":2}
  mz                  [200.0909090936184, 200.1818181872368]
  intensity           [NaN, NaN]   (null cells)
  ```

- R-12, not asserted by the test, shows on Numpress (SLOF) archives that mzPeakJ writes split. On the two-unit copy
  of current small.unpacked (the R-11 file, written with `SignalEncoding.NUMPRESS`), rdump reads spectrum 1 by index
  with its two intensities right but labelled `DetectorCounts`, where the point layout gives `PercentBasePeak`; and
  `rdump ... --signal-range 0 1` records `{"kind":"R","panic":"list array"}`. The range read panics the same way on
  current small.unpacked written as Numpress with its one intensity unit, while on the reference's own
  small.numpress it returns the `intensity_numpress_slof_bytes` column undecoded (no intensity). The spec requires a
  column for each intensity unit, so (a) has no form both accept; the cause of (b) is not traced.

- R-13 shows on the zlib-compressed auxiliary array of spectrum 0 that `RustReaderAcceptanceTest` writes next to an
  uncompressed one, both holding the float64 values [1, 2, 3] (`AuxiliaryArrayWriteTest`). mzPeakJ writes an array's
  bytes and compression as given (D13-19), so no form satisfies both the spec and the Rust reader short of
  decompressing; the uncompressed array reads, with the same record whether spectrum 0's 13589 points lie in one
  row group or, written with a 1 KiB row-group size, in several (the point path loads the arrays). rdump's arrays of
  spectrum 0, with the values its `.bin` file holds:

  ```text
  "NonStandardDataArray { name: \"zlib signal to noise\" }": {"dtype": "Float64", "error": "The content was not properly Base64 encoded"}
  "SignalToNoiseArray": {"ctype": "SignalToNoiseArray", "dtype": "Float64", "n": 3, "off": 27178, "unit": "Unknown"}
  SignalToNoiseArray  [1.0, 2.0, 3.0]
  ```

- R-14 shows on small.unpacked written with Numpress chunks and a 1 KiB row-group size, spectra 0 and 8 each given
  the uncompressed array: spectrum 8's chunks span row groups 0 and 1, and rdump reads its m/z and intensity without
  the array, while it reads spectrum 0's (all its chunks in row group 0; parquet-java checks the row-group size
  first after 100 rows, so spectrum 0 cannot span row groups in a write by mzPeakJ). mzPeakJ reads both.

- R-15 has no rdump record: mzPeakJ refuses to write such an array (provisional D13-28), so the evidence is the
  reference's source at `2ee2589`, `AuxiliaryArrayVisitor` in `src/reader/visitor.rs`:

  ```rust
  let accession = param.curie().unwrap();                                // 1896
  let val = ArrayType::from_accession(accession).unwrap();               // 1897
  da.dtype = BinaryDataArrayType::from_accession(val).unwrap();          // 1912
  da.compression = BinaryCompressionType::from_accession(val).unwrap();  // 1922
  ```

- R-16 shows with rdump on current small.unpacked written split with one param added to spectrum 0 (its own
  params, or those of its first scan) with the unit `seconds`, or with the accession `MS:abc`, and on current has_uv
  with such a param added to wavelength spectrum 0; only that entity fails, every other one reads:

  ```text
  {"index":0,"kind":"S","panic":"called `Result::unwrap()` on an `Err` value: MissingNamespaceSeparator"}
  {"index":0,"kind":"S","panic":"called `Result::unwrap()` on an `Err` value: AccessionParsingError(ParseIntError { kind: InvalidDigit })"}
  {"index":0,"kind":"W","panic":"called `Result::unwrap()` on an `Err` value: MissingNamespaceSeparator"}
  ```

  mzPeakJ writes and reads such a param unit as given (D13-26); for the units and param accessions of auxiliary
  arrays the writer refuses it (D13-28).

- R-17 shows with rdump on current small.unpacked written split with spectrum 0 and spectrum 1 each given the
  uncompressed float64 array MS:1000517 [1, 2, 3], spectrum 1 without its profile points: rdump reads spectrum 0's
  `SignalToNoiseArray` (n 3) next to its m/z and intensity, and spectrum 1 with `"arrays":{}`; mzPeakJ reads the
  array [1, 2, 3] for both.

A latent difference, not seen by the test: the reference's `ScanSettings` struct still uses the pin-era names,
`source_file_refs` and targets as bare param arrays, while the spec's `schema/scan_settings_list.json` names the
references `source_file_references` and makes each target an object with its `parameters`, which mzPeakJ's split
layout writes. The Rust and Python readers do not parse `scan_settings_list`, so nothing breaks today.
