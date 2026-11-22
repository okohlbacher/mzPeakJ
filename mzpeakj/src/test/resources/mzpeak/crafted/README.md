# Crafted members

Parquet members derived from the HUPO-PSI fixtures in `mzpeakj/src/test/resources/mzpeak` for the cases that
parquet-java cannot write, so that the tests cannot build them on the fly. `make_crafted.py` writes every one of them
with pyarrow; each case is a function of the script, listed in its `CASES`. The build never runs Python.

| File | Made from | Change | Used by |
|---|---|---|---|
| `small.spectra_metadata.brotli.parquet` | `small.unpacked.mzpeak/spectra_metadata.parquet` | every column chunk BROTLI (no Java library writes BROTLI); Parquet schema (but the root group's name), rows and footer key-values kept, pyarrow writes its own `ARROW:schema` | `CodecReadTest`, `PureJavaCodecFactoryTest` (X-1, C1) |

A test that puts a crafted member into a dataset copy updates the member's checksum in the copy's manifest.

## Pinned environment

The environment of `../golden` (see `../golden/README.md`): CPython **3.13.12** and the packages of
`../golden/uv-requirements.txt` (pyarrow 26.0.1). The committed files were generated on macOS arm64.

## Regenerating

From the repository root:

```sh
CRAFTED=mzpeakj/src/test/resources/mzpeak/crafted
uv run --no-project --python 3.13.12 --with-requirements mzpeakj/src/test/resources/mzpeak/golden/uv-requirements.txt \
  python -I "$CRAFTED/make_crafted.py"
```

The script writes into its own directory by default (`--out DIR` elsewhere) and checks every file it writes
(codecs, rows and key-values as described). Regenerating with the pinned environment reproduces the committed files
byte for byte.
