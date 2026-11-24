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
import struct
import zlib

import pyarrow as pa
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


def aux_without_items(fixtures, out):
    """small.unpacked's chromatograms_metadata.parquet with auxiliary arrays that lack ``items`` (mzPeakJ U1).

    mzpeak-convert 0.17.2 writes the ``auxiliary_arrays`` struct without its ``items`` field. The struct here drops
    ``items`` from the source's type, and chromatogram 0 (the only row) gets two arrays: MS:1000786 (non-standard
    data array, value ``ms level``, unit UO:0000186) of the 64-bit integers 1, 2 and 1, stored as is (MS:1000576),
    the form of the corpus; and MS:1000517 (signal to noise array) of the 64-bit floats 1.5, 2.5, 3.5 and 4.5,
    zlib-compressed (MS:1000574). ``number_of_auxiliary_arrays`` becomes 2. Every other column, the codec (ZSTD) and
    the footer key-values (pyarrow writes its own ``ARROW:schema``) are kept.
    """
    source = fixtures / "small.unpacked.mzpeak" / "chromatograms_metadata.parquet"
    table = source_table(source)
    assert table.num_rows == 1, table.num_rows
    list_type = table.schema.field("auxiliary_arrays").type
    item_type = list_type.value_type
    struct_type = pa.struct([f for f in item_type if f.name != "items"])
    assert struct_type.num_fields == item_type.num_fields - 1
    arrays_type = pa.large_list(pa.field(list_type.value_field.name, struct_type, list_type.value_field.nullable))

    def param(accession, name, string=None):
        value = {"integer": None, "float": None, "string": string, "boolean": None}
        return {"value": value, "accession": accession, "name": name, "unit": None}

    ms_level = param("MS:1000786", "non-standard data array", "ms level")
    ms_level["unit"] = "UO:0000186"
    arrays = [
        {"data": list(struct.pack("<3q", 1, 2, 1)), "name": ms_level, "data_type": "MS:1000522",
         "compression": "MS:1000576", "unit": "UO:0000186", "parameters": [], "data_processing_id": None},
        {"data": list(zlib.compress(struct.pack("<4d", 1.5, 2.5, 3.5, 4.5), 9)),
         "name": param("MS:1000517", "signal to noise array"), "data_type": "MS:1000523",
         "compression": "MS:1000574", "unit": None, "parameters": [], "data_processing_id": None},
    ]
    column = table.schema.get_field_index("auxiliary_arrays")
    table = table.set_column(column, pa.field("auxiliary_arrays", arrays_type),
                             pa.array([arrays], type=arrays_type))
    count = table.schema.get_field_index("number_of_auxiliary_arrays")
    table = table.set_column(count, table.schema.field(count), pa.array([2], type=table.schema.field(count).type))
    target = out / "small.chromatograms_metadata.aux-without-items.parquet"
    pq.write_table(table, target, compression="ZSTD", use_compliant_nested_type=False)
    metadata = pq.ParquetFile(target).metadata
    codecs = {
        metadata.row_group(g).column(c).compression
        for g in range(metadata.num_row_groups)
        for c in range(metadata.num_columns)
    }
    assert codecs == {"ZSTD"}, codecs
    assert pq.read_table(target).equals(table)
    removed = [line for line in parquet_schema(source) if line not in parquet_schema(target)]
    assert len(removed) == 1 and " items" in removed[0], removed
    written = dict(metadata.metadata)
    assert written.pop(b"ARROW:schema", None) is not None
    assert written == table.schema.metadata
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


CASES = [brotli_metadata, empty_rowgroup_peaks, split_empty_data, aux_without_items]


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--fixtures", type=pathlib.Path, default=HERE.parent)
    parser.add_argument("--out", type=pathlib.Path, default=HERE)
    args = parser.parse_args()
    for case in CASES:
        print(case(args.fixtures, args.out))


if __name__ == "__main__":
    main()
