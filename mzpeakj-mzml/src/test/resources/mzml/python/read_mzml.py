"""The external mzML reader of PythonMzMLReaderTest (mzPeakJ JZ-11, mzML design 12.6).

Reads each mzML file named on the command line with pyteomics (MS-Numpress through pynumpress) and prints one
tab-separated line per spectrum, in document order:

    <file index> <id> <ms level or -> <n> <SHA-256 of the main axis> <SHA-256 of the intensities> [<intensities>]

The main axis is the 'm/z array', else the 'wavelength array' (a UV spectrum); n is its length. Each SHA-256 is that
of the array as little-endian 64-bit floats ('<f8'); an absent array is hashed as an empty one. With --values the
intensities follow, as Python's repr of each value, joined by commas.

pyteomics parses PSI formats with a psims controlled vocabulary; the script hands it the PSI-MS OBO vendored in psims
(no remote OBO, so the result does not depend on the network or on today's PSI-MS release). Run as
    uv run --no-project --with-requirements uv-requirements.txt python -I read_mzml.py [--values] FILE...
"""
import hashlib
import sys

import numpy
from psims.controlled_vocabulary import controlled_vocabulary
from pyteomics import mzml


def sha256(values):
    data = numpy.ascontiguousarray(numpy.asarray(values, dtype="<f8")).tobytes()
    return hashlib.sha256(data).hexdigest()


def main(argv):
    values = "--values" in argv
    files = [a for a in argv if a != "--values"]
    out = sys.stdout
    with controlled_vocabulary._use_vendored_psims_obo() as obo:
        cv = controlled_vocabulary.ControlledVocabulary.from_obo(obo)
    for number, path in enumerate(files):
        with mzml.MzML(path, decode_binary=True, use_index=False, cv=cv) as reader:
            for spectrum in reader:
                axis = spectrum.get("m/z array")
                if axis is None:
                    axis = spectrum.get("wavelength array")
                if axis is None:
                    axis = []
                intensity = spectrum.get("intensity array")
                if intensity is None:
                    intensity = []
                level = spectrum.get("ms level")
                fields = [str(number), spectrum["id"], "-" if level is None else str(int(level)),
                          str(len(axis)), sha256(axis), sha256(intensity)]
                if values:
                    fields.append(",".join(repr(float(v)) for v in numpy.asarray(intensity, dtype="<f8")))
                out.write("\t".join(fields) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
