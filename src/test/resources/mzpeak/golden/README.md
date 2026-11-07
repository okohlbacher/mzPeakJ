# Golden output of the HEAD Python reader

The oracle for reading the current fixtures in `src/test/resources/mzpeak` (HUPO-PSI/mzPeak 2ee2589).
`generate_golden.py` runs the Python reader of that commit (`python/mzpeak`) over one dataset and records
what it returns. The test helper `org.mzpeak.io.Golden` parses the files and compares mzPeakJ's arrays
against them; `GoldenComparatorTest` checks the files and the comparator. The build never runs Python.

| File | Content |
|---|---|
| `generate_golden.py` | the generator: `generate_golden.py <dataset> [--out DIR] [--reader-commit COMMIT]` |
| `uv-requirements.txt` | the pinned package versions (the only place they are pinned) |
| `<name>.tsv` | one summary line per spectrum, peak list, chromatogram, wavelength spectrum and extra array |
| `<name>.arrays.tsv` | full arrays (or digests) of selected spectra and of every chromatogram |

`<name>` is the dataset file name without `.mzpeak`. Without `--out`, the generator writes the summary table
to stdout; the writer interop units use that form on files mzPeakJ wrote.

## Pinned environment

- CPython **3.13.12** (`uv run --python 3.13.12`; uv downloads it if needed)
- the reader of HUPO-PSI/mzPeak **2ee2589facddea6de6c1e7c6cfa77e56895420ec** (`python/`), installed from a
  temporary copy so that the reference checkout is never written to
- packages, as pinned in `uv-requirements.txt`:

| Package | Version |
|---|---|
| pyarrow | 26.0.1 |
| numpy | 2.5.4 |
| pandas | 3.1.1 |
| scipy | 1.18.1 |
| psims | 1.4.0 |
| pynumpress | 0.1.5 |
| lxml | 6.1.3 |
| python-dateutil | 4.0.0 |
| six | 1.17.0 |
| sqlalchemy | 2.1.8 |
| typing-extensions | 4.16.0 |
| tzdata | 2026.6 |

The committed files were generated on macOS arm64. Every header records the reader commit, a SHA-256 over
the installed reader's sources, the Python and package versions and the command.

## Regenerating

From the repository root, with `REF` pointing at a checkout of HUPO-PSI/mzPeak that contains 2ee2589:

```sh
REF=../mzPeak-reference
COMMIT=2ee2589facddea6de6c1e7c6cfa77e56895420ec
GOLDEN=src/test/resources/mzpeak/golden
TMP=$(mktemp -d)
git -C "$REF" archive "$COMMIT" python | tar -x -C "$TMP"
for f in small small.chunked small.numpress small.unpacked has_uv diaPASEF.ref diaPASEF.grid Example_Processed.img; do
  uv run --no-project --python 3.13.12 --with "$TMP/python" --with-requirements "$GOLDEN/uv-requirements.txt" \
    python -I "$GOLDEN/generate_golden.py" "src/test/resources/mzpeak/$f.mzpeak" --out "$GOLDEN" --reader-commit "$COMMIT"
done
rm -rf "$TMP"
git -C "$REF" status --porcelain   # prints nothing
```

`git archive` copies the committed `python/` directory of 2ee2589 into the temporary directory, where uv
builds and installs it; `python -I` keeps the user site and `PYTHON*` variables out. The loop reproduces the
committed files byte for byte on the same platform.

## Summary lines (`<name>.tsv`)

Tab-separated, after `#` header lines:
`kind key array n n_filled sum_x sum_y sum_xy sum_ix sum_iy head tail`.

| Kind | Entity | x, y | Key |
|---|---|---|---|
| S | `reader[i]`: the spectrum's data arrays, else its peaks | m/z, intensity | spectrum index |
| P | `read_peaks_for(i)`, for every index in the peaks member | m/z, intensity | spectrum index |
| C | `read_chromatogram(i)` | time, intensity | chromatogram id |
| W | wavelength spectra | wavelength, intensity | wavelength spectrum index |
| X | every other array of an S, P, C or W entity | the owner's main axis, the extra array | `<owner kind>:<owner key>` |
| R | raw pyarrow cells of a point-layout spectrum data member | stored m/z, stored intensity | spectrum index |

- `array` is `-`, except on X lines: the array_type, or the array_name for MS:1000786 (for example
  `ms level`).
- `n` is the number of points and `n_filled` the number of points whose main-axis value the reader's null
  fill produced (a second reader with null filling disabled returns a different value there). On X lines
  both are the owner's.
- R lines skip null cells: `n` counts the non-null m/z cells (the stored count), `n_filled` the null ones,
  and `i` is the row ordinal within the entity. R lines exist only for point-layout data members.
- The sums are exact (`math.fsum`) over float64 values; `x·y` and `i·x` are rounded per element, and `i` is
  the point index. `head` and `tail` are the first and last 3 pairs as `x0,y0,x1,y1,x2,y2` (`-` if empty).
  Values use `%.17g`, which round-trips every double; NaN is `nan`.
- Keys escape backslash, tab, newline and carriage return as `\\`, `\t`, `\n` and `\r`.

## Array lines (`<name>.arrays.tsv`)

Spectrum 0, the first centroid spectrum (MS:1000127), the last spectrum and every chromatogram:

- `A kind key i x y filled`: one point, `filled` 1 where the null fill produced x.
- `D kind key n n_filled sha256_x sha256_y extras`: an entity above **20,000** points, as SHA-256 digests of
  the little-endian float64 bytes of each array (NaN canonicalised to `0x7ff8000000000000`), `extras` as
  `key=sha256;...` or `-`. When a selected spectrum is a D line, the dataset's smallest spectrum is stored in
  full as well. Digests are compared only when `n_filled` is 0.

Spectrum 0 of diaPASEF.ref and diaPASEF.grid has 205,921 points and is a D line. All `*.tsv` files together
take 3,826,648 bytes, within the 8 MB cap, so the 20,000-point threshold stands.

## Tolerances (`Golden.Tolerance.DEFAULT`)

- `n` exact, stored values exact, digests bit-exact.
- Filled m/z within 5e-5 Da: the Python reader uses the stored model only for single-point clusters and the
  cluster's local median spacing otherwise, mzPeakJ the stored polynomial for every cluster (DESIGN.md §4).
- Sums and pairing statistics relative 1e-12; when `n_filled` > 0, relative 1e-9 plus 5e-5 Da per filled
  point on the sums that involve x.
