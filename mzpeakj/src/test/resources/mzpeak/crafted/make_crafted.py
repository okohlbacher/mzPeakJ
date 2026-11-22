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


def empty_rowgroup_peaks(fixtures, out):
    """small.unpacked's spectra_peaks.parquet without rows, as one row group of 0 rows (mzPeakJ X-4, C3).

    The Parquet schema, the codec (ZSTD) and the footer key-values (pyarrow writes its own ``ARROW:schema``) of the
    source are kept, the key-values unchanged although they still count the source's spectra and points.
    """
    source = fixtures / "small.unpacked.mzpeak" / "spectra_peaks.parquet"
    table = source_table(source).slice(0, 0)
    target = out / "small.spectra_peaks.empty-rowgroup.parquet"
    write_row_groups(source, target, table, [table])
    assert row_counts(target) == [0], row_counts(target)
    return target


def split_empty_data(fixtures, out):
    """small.unpacked's spectra_data.parquet with spectrum 0 split around an empty row group (mzPeakJ X-4, C3).

    Row group 0 holds the first 6000 points of spectrum 0, row group 1 no rows, row group 2 the rest of spectrum 0
    and every later spectrum; rows, Parquet schema, codec (ZSTD) and footer key-values (pyarrow writes its own
    ``ARROW:schema``) are kept.
    """
    source = fixtures / "small.unpacked.mzpeak" / "spectra_data.parquet"
    table = source_table(source)
    index = table.column("point").combine_chunks().field("spectrum_index").to_pylist()
    spectrum0 = index.count(0)
    assert index[:spectrum0] == [0] * spectrum0 and spectrum0 > 6000, spectrum0
    target = out / "small.spectra_data.split-empty.parquet"
    write_row_groups(source, target, table, [table.slice(0, 6000), table.slice(0, 0), table.slice(6000)])
    assert row_counts(target) == [6000, 0, table.num_rows - 6000], row_counts(target)
    return target


def source_table(source):
    """The rows of ``source`` with its footer key-values but pyarrow's ``ARROW:schema`` as schema metadata."""
    key_values = pq.ParquetFile(source).metadata.metadata
    table = pq.read_table(source)
    return table.replace_schema_metadata({k: v for k, v in key_values.items() if k != b"ARROW:schema"})


def write_row_groups(source, target, table, groups):
    """Writes ``groups`` (slices of ``table``) to ``target``, one row group each, ZSTD, and checks the result."""
    with pq.ParquetWriter(target, table.schema, compression="ZSTD", use_compliant_nested_type=False,
                          use_dictionary=["point.spectrum_index", "point.intensity"],
                          use_byte_stream_split=["point.mz"]) as writer:
        for group in groups:
            # a table of 0 rows is written as a row group of 0 rows
            writer.write_table(group, row_group_size=max(group.num_rows, 1))
    metadata = pq.ParquetFile(target).metadata
    codecs = {
        metadata.row_group(g).column(c).compression
        for g in range(metadata.num_row_groups)
        for c in range(metadata.num_columns)
    }
    assert codecs == {"ZSTD"}, codecs
    assert pq.read_table(target).equals(table)
    assert parquet_schema(target) == parquet_schema(source)
    written = dict(pq.ParquetFile(target).metadata.metadata)
    assert written.pop(b"ARROW:schema", None) is not None
    assert written == table.schema.metadata


def row_counts(path):
    """The number of rows of every row group of ``path``."""
    metadata = pq.ParquetFile(path).metadata
    return [metadata.row_group(g).num_rows for g in range(metadata.num_row_groups)]


def parquet_schema(path):
    """The Parquet schema of ``path`` as text, without the line naming the root group."""
    return str(pq.ParquetFile(path).schema).splitlines()[2:]


CASES = [brotli_metadata, empty_rowgroup_peaks, split_empty_data]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fixtures", type=pathlib.Path, default=HERE.parent)
    parser.add_argument("--out", type=pathlib.Path, default=HERE)
    args = parser.parse_args()
    for case in CASES:
        print(case(args.fixtures, args.out))


if __name__ == "__main__":
    main()
