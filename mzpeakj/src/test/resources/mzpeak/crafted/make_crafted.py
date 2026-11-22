"""Crafted Parquet members derived from the HUPO-PSI fixtures, for the cases parquet-java cannot write.

Usage::

    make_crafted.py [--fixtures DIR] [--out DIR]

``--fixtures`` defaults to the directory above this script (``mzpeakj/src/test/resources/mzpeak``), ``--out``
to this script's directory. Run it only in the pinned environment of ``../golden/README.md`` (CPython 3.13.12
and ``../golden/uv-requirements.txt``); see ``README.md`` beside this script. Every case is a function below,
listed in ``CASES``; each writes one file named ``<fixture>.<member>.<case>.parquet``.
"""

import argparse
import pathlib

import pyarrow.parquet as pq

HERE = pathlib.Path(__file__).resolve().parent


def brotli_metadata(fixtures, out):
    """small.unpacked's spectra_metadata.parquet re-encoded with the BROTLI codec (mzPeakJ X-1, C1).

    The Parquet schema (list elements keep the source's name ``item``; only the root is pyarrow's ``schema``), the
    rows and the footer key-values (pyarrow writes its own ``ARROW:schema``) are kept; only the codec of every
    column chunk changes.
    """
    source = fixtures / "small.unpacked.mzpeak" / "spectra_metadata.parquet"
    key_values = pq.ParquetFile(source).metadata.metadata
    table = pq.read_table(source)
    table = table.replace_schema_metadata({k: v for k, v in key_values.items() if k != b"ARROW:schema"})
    target = out / "small.spectra_metadata.brotli.parquet"
    pq.write_table(table, target, compression="BROTLI", use_compliant_nested_type=False)
    metadata = pq.ParquetFile(target).metadata
    codecs = {
        metadata.row_group(g).column(c).compression
        for g in range(metadata.num_row_groups)
        for c in range(metadata.num_columns)
    }
    assert codecs == {"BROTLI"}, codecs
    assert pq.read_table(target).equals(table)
    assert parquet_schema(target) == parquet_schema(source)
    written = dict(pq.ParquetFile(target).metadata.metadata)
    assert written.pop(b"ARROW:schema", None) is not None
    assert written == {k: v for k, v in key_values.items() if k != b"ARROW:schema"}
    return target


def parquet_schema(path):
    """The Parquet schema of ``path`` as text, without the line naming the root group."""
    return str(pq.ParquetFile(path).schema).splitlines()[2:]


CASES = [brotli_metadata]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fixtures", type=pathlib.Path, default=HERE.parent)
    parser.add_argument("--out", type=pathlib.Path, default=HERE)
    args = parser.parse_args()
    for case in CASES:
        print(case(args.fixtures, args.out))


if __name__ == "__main__":
    main()
