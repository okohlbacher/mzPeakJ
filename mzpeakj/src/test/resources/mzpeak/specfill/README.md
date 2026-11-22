# The spec's null fill on small.chunked

`small.chunked.specfill.tsv` holds the m/z that the mzPeak specification's own Python code gives the null-marked
points of `../small.chunked.mzpeak` spectra 0 and 41 (X-8, R2). `specfill_reference.py` makes it: per chunk row, the
spec's `null_delta_decode` (docs/layouts/chunked-layout.md, Delta encoding) and `fill_nulls` (docs/layouts/signal-data.md,
Decoding null pairs) at HUPO-PSI/mzPeak 963d64a, with the spectrum's `polynomial_delta_coordinate_interpolation` as the
regression model. `NullFillSpecTest` compares mzPeakJ's read against it. The build never runs Python.

One line per filled point: `spectrum<TAB>position<TAB>m/z`, the position counted over the whole spectrum, the m/z in
Python `repr` form (it round-trips through `Double.parseDouble`).

## Pinned environment

The environment of `../golden` (see `../golden/README.md`): CPython **3.13.12** and the packages of
`../golden/uv-requirements.txt` (numpy 2.5.4, pyarrow 26.0.1). The committed file was generated on macOS arm64.

## Regenerating

From the repository root:

```sh
SPECFILL=mzpeakj/src/test/resources/mzpeak/specfill
uv run --no-project --python 3.13.12 --with-requirements mzpeakj/src/test/resources/mzpeak/golden/uv-requirements.txt \
  python -I "$SPECFILL/specfill_reference.py" mzpeakj/src/test/resources/mzpeak/small.chunked.mzpeak 0 41 \
  > "$SPECFILL/small.chunked.specfill.tsv"
```
