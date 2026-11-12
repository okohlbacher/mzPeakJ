# rdump

Dumps an mzPeak dataset as the Rust reference reader reads it: `mzpeak_prototyping` of
[HUPO-PSI/mzPeak](https://github.com/HUPO-PSI/mzPeak), pinned to `2ee2589facddea6de6c1e7c6cfa77e56895420ec` in
`Cargo.toml`, with the dependency versions of `Cargo.lock`. mzPeakJ's `RustReaderAcceptanceTest` runs it on the files
mzPeakJ writes, locally and in the CI job `rust-reader`.

## Build

Rust 1.85 or newer (edition 2024); CI and the local check use 1.96.0.

```sh
cargo build --release --locked --manifest-path tools/rdump/Cargo.toml
```

## Run

```sh
tools/rdump/target/release/rdump <dataset> <outPrefix> [indicesFile]
```

`<dataset>` is an `.mzpeak` archive or directory (or an `.mzML` file, read with mzdata). `indicesFile` limits the
spectra to the indices it lists, one per line; chromatograms and wavelength spectra are always dumped. rdump exits 0
even when the dataset cannot be opened; the failure is a record of the output.

`<outPrefix>.jsonl` holds one JSON object per line, told apart by `kind`:

| kind | content |
|---|---|
| `OPEN_ERROR`, `OPEN_PANIC` | the dataset did not open (`error`); nothing else follows |
| `HEADER` | `n_spectra`, `n_chromatograms`, `n_wavelength`, observed time and m/z range |
| `S` | a spectrum: `index`, `id`, `ms_level`, `rt`, `desc`, `prec`, `arrays` (raw arrays), `spec_peaks` (the spectrum's peaks), and `peaks` from the separate `get_spectrum_peaks_for` call, or `peaks_err` / `peaks_panic` where that call failed; `none: true` when the spectrum was not found, `panic` when reading it panicked |
| `C` | a chromatogram: `id`, `ctype`, `desc`, `arrays`; or `none` / `panic` |
| `T` | the encoded TIC and BPC (`which`): `arrays`, or `error` / `panic` |
| `W` | a wavelength spectrum: `id`, `rt`, `desc`, `arrays`; or `none` (with which half failed) / `panic` |
| `END` | the dump is complete |

`<outPrefix>.bin` holds every dumped array as little-endian float64 values. An array entry `{"n", "off", ...}` points
to `n` values starting at value `off`; a peak list has `mz_off` and `int_off` (deconvoluted peaks: `mass_off`,
`int_off`, `z_off`). Panics are caught and recorded; set `RDUMP_PANIC_HOOK` to also print them to stderr.

Ion mobility of centroid peaks is not exposed by the reference API, so it is not dumped (see
[`docs/upstream-issues.md`](../../docs/upstream-issues.md), R-7).
