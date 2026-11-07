"""Golden output of the HEAD Python mzPeak reader for one dataset (mzPeakJ unit FX-1b).

Usage::

    generate_golden.py <dataset> [--out DIR] [--reader-commit COMMIT]

Without ``--out`` the summary table goes to stdout. With ``--out`` the summary is written to
``DIR/<name>.tsv`` and the selected full arrays to ``DIR/<name>.arrays.tsv``, where ``<name>`` is the
dataset file name without ``.mzpeak``. Run it only in the pinned environment of README.md; the reader
itself is a temporary copy of ``mzPeak-reference/python``, so the reference checkout is never written to.

Summary lines (``<name>.tsv``), one per entity, tab-separated::

    kind key array n n_filled sum_x sum_y sum_xy sum_ix sum_iy head tail

* ``S`` spectra as ``reader[i]`` returns them (data arrays, else peaks): m/z against intensity.
* ``P`` peaks as ``read_peaks_for(i)`` returns them, for every index in the peaks member.
* ``C`` chromatograms (``read_chromatogram``), time against intensity, keyed by chromatogram id.
* ``W`` wavelength spectra, wavelength against intensity.
* ``X`` every other array of an S, P, C or W entity: the owner's main axis against the extra array. The key
  is ``<owner kind>:<owner key>``, the array column the array_type (the array_name for MS:1000786).
* ``R`` raw pyarrow cells of a point-layout spectrum data member, nulls skipped: ``n`` counts the
  non-null m/z cells, ``n_filled`` the null ones, and ``i`` is the row ordinal within the entity.

``n_filled`` counts the points whose main-axis value the reader's null fill produced (a second reader with
null filling disabled returns a different value there). ``i`` is the point index, sums are exact
(``math.fsum``) over float64 values with per-element rounded products, ``head`` and ``tail`` hold the first
and last 3 (x, y) pairs as ``x0,y0,x1,y1,...`` (``-`` when empty). Numbers use ``%.17g``.

Array lines (``<name>.arrays.tsv``) cover spectrum 0, the first centroid spectrum, the last spectrum and
every chromatogram. ``A kind key i x y filled`` stores one point; an entity above ``DIGEST_THRESHOLD`` points
is stored as one ``D kind key n n_filled sha256_x sha256_y extras`` line instead, the SHA-256 taken over the
little-endian float64 bytes with NaN canonicalised (``extras`` is ``key=sha256;...`` or ``-``). When a
selected spectrum is digested, the dataset's smallest spectrum is stored in full as well.
"""

import argparse
import hashlib
import importlib.metadata
import math
import os
import platform
import sys

import numpy as np

import mzpeak
from mzpeak import MzPeakFile
from mzpeak.file_index import DataKind
from mzpeak.mz_reader import BufferFormat

DIGEST_THRESHOLD = 20000
PACKAGES = ("pyarrow", "numpy", "pandas", "scipy", "psims", "pynumpress")
COLUMNS = ("kind", "key", "array", "n", "n_filled", "sum_x", "sum_y", "sum_xy", "sum_ix", "sum_iy",
           "head", "tail")

MZ_ARRAY = "MS:1000514"
INTENSITY_ARRAY = "MS:1000515"
TIME_ARRAY = "MS:1000595"
WAVELENGTH_ARRAY = "MS:1000617"
NON_STANDARD_ARRAY = "MS:1000786"
CENTROID_SPECTRUM = "MS:1000127"


class Entity:
    """The arrays of one spectrum, peak list, chromatogram or wavelength spectrum, as float64."""

    def __init__(self, kind, key, x, y, filled, extras, centroid=False):
        self.kind = kind
        self.key = key
        self.x = x
        self.y = y
        self.filled = filled
        self.extras = extras
        self.centroid = centroid

    def __len__(self):
        return len(self.x)


def fail(message):
    raise SystemExit(f"generate_golden.py: {message}")


def fmt(value):
    return "%.17g" % float(value)


def escape(text):
    return (text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r"))


def as_f64(values, what):
    values = np.asarray(values)
    if values.dtype.kind not in "fiub":
        fail(f"{what} has non-numeric dtype {values.dtype}")
    return values.astype(np.float64)


def exact_sum(values):
    if values.size == 0:
        return 0.0
    if np.isfinite(values).all():
        return math.fsum(values.tolist())
    return float(np.sum(values))


def pairs(x, y):
    if len(x) == 0:
        return "-"
    return ",".join(fmt(v) for pair in zip(x, y) for v in pair)


def summary_row(kind, key, array, x, y, n_filled, stored=False):
    """One summary line; ``stored`` skips NaN (null) cells as the R lines require."""
    i = np.arange(len(x), dtype=np.float64)
    if stored:
        has_x = ~np.isnan(x)
        has_y = ~np.isnan(y)
        n = int(has_x.sum())
        n_filled = len(x) - n
        sums = (exact_sum(x[has_x]), exact_sum(y[has_y]), exact_sum((x * y)[has_x & has_y]),
                exact_sum((i * x)[has_x]), exact_sum((i * y)[has_y]))
    else:
        n = len(x)
        sums = (exact_sum(x), exact_sum(y), exact_sum(x * y), exact_sum(i * x), exact_sum(i * y))
    return [kind, escape(key), array, str(n), str(n_filled), *(fmt(s) for s in sums),
            pairs(x[:3], y[:3]), pairs(x[-3:], y[-3:])]


def sha256_f64(values):
    canonical = np.array(values, dtype="<f8")
    canonical[np.isnan(canonical)] = np.nan
    return hashlib.sha256(canonical.tobytes()).hexdigest()


def array_keys(data_reader):
    """Map each array_name of a data member to its golden key (array_type, array_name for MS:1000786)."""
    keys = {}
    for entry in data_reader.array_index.values():
        if entry.buffer_format == BufferFormat.Index:
            continue
        key = entry.array_name if entry.array_type == NON_STANDARD_ARRAY else entry.array_type
        if keys.setdefault(entry.array_name, key) != key:
            fail(f"array name {entry.array_name!r} maps to {keys[entry.array_name]} and {key}")
    return keys


def split_arrays(result, keys, axis_type, what):
    """Split a reader result into main axis, intensity and extras, keyed as in ``array_keys``."""
    names = {}
    for name, key in keys.items():
        names.setdefault(key, name)
    arrays = {}
    for name, key in keys.items():
        value = result.get(name)
        if isinstance(value, np.ndarray):
            arrays[key] = as_f64(value, f"{what} {name!r}")
    x = arrays.pop(axis_type, np.zeros(0))
    y = arrays.pop(INTENSITY_ARRAY, np.zeros(0))
    for key, values in [("intensity", y), *arrays.items()]:
        if len(values) != len(x):
            fail(f"{what}: {key} has {len(values)} values for {len(x)} {names.get(axis_type)} values")
    return x, y, arrays


def filled_flags(x, x_unfilled, what):
    if len(x) != len(x_unfilled):
        fail(f"{what}: {len(x)} values with null filling, {len(x_unfilled)} without")
    same = (x == x_unfilled) | (np.isnan(x) & np.isnan(x_unfilled))
    return ~same


def member_indices(data_reader, index_field):
    """Sorted entity indices present in a data member, from the raw pyarrow cells."""
    table = data_reader.handle.read()
    values = table.column(0).combine_chunks().field(index_field).drop_null()
    return [int(v) for v in np.unique(values.to_numpy(zero_copy_only=False))]


def read_spectra(reader, unfilled):
    data_keys = array_keys(reader.spectrum_data) if reader.spectrum_data is not None else {}
    peak_keys = array_keys(reader.spectrum_peak_data) if reader.spectrum_peak_data is not None else {}
    spectra = []
    for i in range(len(reader)):
        spec = reader[i]
        if spec["index"] != i:
            fail(f"spectrum {i} reads back as index {spec['index']}")
        from_peaks = spec["data_kind"] == DataKind.Peaks
        keys = peak_keys if from_peaks else data_keys
        x, y, extras = split_arrays(spec, keys, MZ_ARRAY, f"spectrum {i}")
        x_unfilled, _, _ = split_arrays(unfilled[i], keys, MZ_ARRAY, f"spectrum {i} without null fill")
        filled = filled_flags(x, x_unfilled, f"spectrum {i}")
        centroid = spec.get("spectrum representation") == CENTROID_SPECTRUM
        spectra.append(Entity("S", str(i), x, y, filled, extras, centroid))
    return spectra


def read_peaks(reader):
    if reader.spectrum_peak_data is None:
        return []
    keys = array_keys(reader.spectrum_peak_data)
    peaks = []
    for i in member_indices(reader.spectrum_peak_data, "spectrum_index"):
        x, y, extras = split_arrays(reader.read_peaks_for(i), keys, MZ_ARRAY, f"peaks {i}")
        peaks.append(Entity("P", str(i), x, y, np.zeros(len(x), dtype=bool), extras))
    return peaks


def read_chromatograms(reader):
    if reader.chromatogram_data is None or reader.chromatograms is None:
        return []
    keys = array_keys(reader.chromatogram_data)
    chromatograms = []
    seen = set()
    for index in reader.chromatograms.index:
        chrom = reader.read_chromatogram(int(index))
        key = chrom.get("id")
        if not isinstance(key, str) or key in seen:
            fail(f"chromatogram {index} has a missing or duplicate id {key!r}")
        seen.add(key)
        x, y, extras = split_arrays(chrom, keys, TIME_ARRAY, f"chromatogram {key!r}")
        chromatograms.append(Entity("C", key, x, y, np.zeros(len(x), dtype=bool), extras))
    return chromatograms


def read_wavelength_spectra(reader):
    facet = reader.wavelength_data
    if facet is None:
        return []
    keys = array_keys(facet.spectrum_data)
    spectra = []
    for i in range(len(facet)):
        spec = facet[i]
        x, y, extras = split_arrays(spec, keys, WAVELENGTH_ARRAY, f"wavelength spectrum {i}")
        spectra.append(Entity("W", str(i), x, y, np.zeros(len(x), dtype=bool), extras))
    return spectra


def stored_rows(reader):
    """R lines: raw pyarrow cells of the spectrum data member, when it uses the point layout."""
    data = reader.spectrum_data
    if data is None or data.buffer_format() != BufferFormat.Point:
        return []
    leaves = {}
    for leaf, entry in data.array_index.items():
        if entry.buffer_format == BufferFormat.Point:
            leaves.setdefault(entry.array_type, leaf)
    point = data.handle.read().column("point").combine_chunks()
    index = point.field("spectrum_index").to_numpy(zero_copy_only=False)
    x = as_f64(point.field(leaves[MZ_ARRAY]).to_numpy(zero_copy_only=False), "stored m/z")
    y = as_f64(point.field(leaves[INTENSITY_ARRAY]).to_numpy(zero_copy_only=False), "stored intensity")
    rows = []
    for value in np.unique(index):
        mask = index == value
        rows.append(summary_row("R", str(int(value)), "-", x[mask], y[mask], 0, stored=True))
    return rows


def summary_rows(spectra, peaks, chromatograms, wavelength_spectra, stored):
    rows = []
    groups = (spectra, peaks, chromatograms, wavelength_spectra)
    for group in groups:
        for e in group:
            rows.append(summary_row(e.kind, e.key, "-", e.x, e.y, int(e.filled.sum())))
    for group in groups:
        for e in group:
            for key, values in e.extras.items():
                rows.append(summary_row("X", f"{e.kind}:{e.key}", escape(key), e.x, values,
                                        int(e.filled.sum())))
    rows.extend(stored)
    return rows


def array_rows(spectra, chromatograms):
    """Full arrays (A) or digests (D) of the selected spectra and of every chromatogram."""
    selected = []
    if spectra:
        first_centroid = next((e for e in spectra if e.centroid), None)
        for e in (spectra[0], first_centroid, spectra[-1]):
            if e is not None and e not in selected:
                selected.append(e)
        if any(len(e) > DIGEST_THRESHOLD for e in selected):
            smallest = min(spectra, key=len)
            if smallest not in selected:
                selected.append(smallest)
    rows = []
    for e in selected + chromatograms:
        if len(e) > DIGEST_THRESHOLD:
            extras = []
            for key, values in e.extras.items():
                if "=" in key or ";" in key:
                    fail(f"extra array key {key!r} cannot be written to a D line")
                extras.append(f"{key}={sha256_f64(values)}")
            rows.append(["D", e.kind, escape(e.key), str(len(e)), str(int(e.filled.sum())),
                         sha256_f64(e.x), sha256_f64(e.y), escape(";".join(extras)) if extras else "-"])
        else:
            for i in range(len(e)):
                rows.append(["A", e.kind, escape(e.key), str(i), fmt(e.x[i]), fmt(e.y[i]),
                             "1" if e.filled[i] else "0"])
    return rows


def reader_sources_sha256():
    """SHA-256 over the installed reader's ``.py`` files (relative path, NUL, content, NUL; sorted)."""
    root = os.path.dirname(os.path.abspath(mzpeak.__file__))
    files = []
    for directory, subdirs, names in os.walk(root):
        subdirs[:] = [d for d in subdirs if d != "__pycache__"]
        files.extend(os.path.relpath(os.path.join(directory, n), root) for n in names if n.endswith(".py"))
    digest = hashlib.sha256()
    for rel in sorted(f.replace(os.sep, "/") for f in files):
        digest.update(rel.encode("utf-8") + b"\0")
        with open(os.path.join(root, rel), "rb") as fh:
            digest.update(fh.read())
        digest.update(b"\0")
    return digest.hexdigest()


def header(title, dataset, command, reader_commit, columns):
    packages = " ".join(f"{p}=={importlib.metadata.version(p)}" for p in PACKAGES)
    return [
        f"# {title} of {dataset}, read by the HEAD Python mzPeak reader (mzPeakJ FX-1b)",
        f"# reader: HUPO-PSI/mzPeak python/mzpeak @ {reader_commit}",
        f"# reader-sources-sha256: {reader_sources_sha256()}",
        f"# python: {platform.python_implementation()} {platform.python_version()}",
        f"# packages: {packages}",
        f"# command: {command}",
        f"# digest-threshold: {DIGEST_THRESHOLD}",
        f"# columns: {columns}",
    ]


def render(header_lines, rows):
    return "".join(line + "\n" for line in header_lines) + "".join("\t".join(r) + "\n" for r in rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Golden output of the HEAD Python mzPeak reader.")
    parser.add_argument("dataset", help="an .mzpeak archive or unpacked directory")
    parser.add_argument("--out", help="write <name>.tsv and <name>.arrays.tsv into this directory")
    parser.add_argument("--reader-commit", default="unrecorded",
                        help="the mzPeak-reference commit the reader copy was taken from")
    args = parser.parse_args(argv)

    dataset = os.path.basename(os.path.normpath(args.dataset))
    name = dataset[:-len(".mzpeak")] if dataset.endswith(".mzpeak") else dataset
    command = f"generate_golden.py {dataset}"
    if args.out:
        command += f" --out {os.path.basename(os.path.normpath(args.out))}"
    if args.reader_commit != "unrecorded":
        command += f" --reader-commit {args.reader_commit}"

    reader = MzPeakFile(args.dataset)
    unfilled = MzPeakFile(args.dataset)
    if unfilled.spectrum_data is not None:
        unfilled.spectrum_data._do_null_filling = False

    spectra = read_spectra(reader, unfilled)
    chromatograms = read_chromatograms(reader)
    rows = summary_rows(spectra, read_peaks(reader), chromatograms, read_wavelength_spectra(reader),
                        stored_rows(reader))
    summary = render(header("Golden summary", dataset, command, args.reader_commit, " ".join(COLUMNS)), rows)

    if not args.out:
        sys.stdout.write(summary)
        return 0
    arrays = render(header("Golden arrays", dataset, command, args.reader_commit,
                           "A kind key i x y filled | D kind key n n_filled sha256_x sha256_y extras"),
                    array_rows(spectra, chromatograms))
    for suffix, text in ((".tsv", summary), (".arrays.tsv", arrays)):
        with open(os.path.join(args.out, name + suffix), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
