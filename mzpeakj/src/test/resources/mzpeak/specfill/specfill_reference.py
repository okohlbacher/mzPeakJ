"""Fill the null-marked m/z of small.chunked spectra with the spec's Python reference code.

Usage: specfill_reference.py <small.chunked.mzpeak> <spectrum index>... > small.chunked.specfill.tsv

For each chunk row of each spectrum named, the m/z chunk values are decoded with the spec's ``null_delta_decode``
(docs/layouts/chunked-layout.md, Delta encoding) and their nulls filled with the spec's ``fill_nulls``
(docs/layouts/signal-data.md, Decoding null pairs), using the spectrum's
``polynomial_delta_coordinate_interpolation`` coefficients as the regression model. The functions below are the
spec's code at HUPO-PSI/mzPeak 963d64a with ``pa.Array`` inputs replaced by Python lists, ``np.concat`` by
``np.concatenate`` and the model's ``predict`` kept as written. One line per filled point:
``spectrum<TAB>position<TAB>m/z`` with the position counted over the whole spectrum and the m/z in ``repr`` form.
"""
import sys
import zipfile
import io

import numpy as np
import pyarrow.parquet as pq


class DeltaCurveRegressionModel:
    def __init__(self, beta):
        self.beta = beta

    def predict(self, mz):
        acc = self.beta[0]
        for i in range(1, len(self.beta)):
            acc += self.beta[i] * mz ** i
        return acc


def null_delta_decode(data, start):
    acc = []
    if data[0] is None:
        if len(data) > 1 and data[1] is None:
            acc.append(start)
        start = None
    else:
        acc.append(start)
    last = start
    for item in data:
        if item is not None:
            if last is not None:
                last = item + last
                acc.append(last)
            else:
                acc.append(item)
                last = item
        else:
            acc.append(None)
            last = None
    return acc


def find_pairs(mask):
    parts = []
    indices = np.where(mask)[0]
    if len(indices) == 0:
        return np.array([[0, len(mask)]])
    if indices[0] != 0:
        parts.append([0])
    parts.append(indices)
    if indices[-1] != len(mask) - 1:
        parts.append([len(mask) - 1])
    indices = np.concatenate(parts)
    indices = indices.reshape((-1, 2))
    indices[:, 1] += 1
    return indices


def estimate_median_delta(data):
    deltas = np.diff(data)
    median = np.median(deltas)
    deltas_below = deltas[deltas <= median]
    median = np.median(deltas_below)
    return median, deltas_below


def fill_nulls(data, common_spacing):
    values = np.array([np.nan if v is None else v for v in data], dtype=float)
    pair_indices = find_pairs(np.isnan(values))
    chunks = []
    for (start, end) in pair_indices:
        chunk = values[start:end].copy()
        n = len(chunk)
        has_real = chunk[~np.isnan(chunk)]
        n_has_real = len(has_real)
        if n_has_real == 1:
            if n == 2:
                if np.isnan(chunk[0]):
                    chunk[0] = chunk[1] - common_spacing.predict(chunk[1])
                else:
                    chunk[1] = chunk[0] + common_spacing.predict(chunk[0])
            elif n == 3:
                dx = common_spacing.predict(chunk[1])
                chunk[0] = chunk[1] - dx
                chunk[2] = chunk[1] + dx
            else:
                raise Exception(f'Invalid singleton chunk: {chunk}')
        else:
            dx, _ = estimate_median_delta(has_real)
            if np.isnan(chunk[0]):
                chunk[0] = chunk[1] - dx
            if np.isnan(chunk[-1]):
                chunk[-1] = chunk[-2] + dx
        chunks.append(chunk)
    return np.concatenate(chunks)


def member(archive, name):
    with zipfile.ZipFile(archive) as z:
        return pq.read_table(io.BytesIO(z.read(name)))


def main():
    archive = sys.argv[1]
    wanted = [int(a) for a in sys.argv[2:]]
    meta = member(archive, 'spectra_metadata.parquet').to_pylist()
    models = {m['index']: m['polynomial_delta_coordinate_interpolation'] for m in meta}
    rows = member(archive, 'spectra_data.parquet').column('chunk').to_pylist()
    for index in wanted:
        model = DeltaCurveRegressionModel(models[index])
        offset = 0
        for row in rows:
            if row is None or row['spectrum_index'] != index:
                continue
            if row['chunk_encoding'] != 'MS:1003089':
                raise SystemExit(f"spectrum {index}: chunk_encoding {row['chunk_encoding']}")
            values = row['mz_chunk_values']
            decoded = null_delta_decode(values, row['mz_chunk_start']) if values else [row['mz_chunk_start']]
            filled = fill_nulls(decoded, model) if any(v is None for v in decoded) else decoded
            for k, v in enumerate(decoded):
                if v is None:
                    print(f'{index}\t{offset + k}\t{float(filled[k])!r}')
            offset += len(decoded)


if __name__ == '__main__':
    main()
