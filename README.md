# mzPeakJ

[![CI](https://github.com/okohlbacher/mzPeakJ/actions/workflows/ci.yml/badge.svg)](https://github.com/okohlbacher/mzPeakJ/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A pure-JVM **Java reader and writer** for [mzPeak](https://www.psidev.info/mzpeak) — a next-generation
mass-spectrometry data format built on Apache Parquet, under development by the
[HUPO-PSI](https://www.psidev.info/).

> **⚠️ Demonstrator / proof-of-concept.** mzPeakJ is an independent demonstrator that tracks the mzPeak
> reference implementations — primarily the Rust crate
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping) by Joshua Klein, plus the
> HUPO-PSI [spec](https://github.com/HUPO-PSI/mzPeak) and example files. It shows that a clean,
> dependency-light JVM reader/writer is feasible — it is **not** a hardened production library.
> mzPeak itself is an unstable pre-1.0 prototype; no on-disk stability is guaranteed upstream.
> Expect rough edges and pin to a specific commit before depending on any behaviour.

It reads the unpacked `*.mzpeak/` directory **and** the single-file `.mzpeak` (STORED ZIP); the `point`,
delta-`chunk`, MS-Numpress `chunk` and coordinate-grid `chunk` layouts; spectra (MS1 + MSn), chromatograms, and
UV/DAD wavelength spectra, with range queries, signal modes, column statistics and pluggable storage. It also
**writes** mzPeak back out — point and Numpress layouts, with precursor activation, scan windows, ion-mobility
and imaging-position columns, derived spectrum columns, wavelength facets, and scan settings. An opt-in gate
runs the independent [`mzpeak-validate`](https://github.com/okohlbacher/mzPeakValidator) validator on its
output; it currently fails, because mzpeak-validate 0.9.25 predates the array-index schema mzPeakJ writes
(HUPO-PSI/mzPeak `0220201`), and whether the CV list of `PACKED` output must declare IMS is undecided, so the
output is not claimed to validate. See [Scope & limitations](#scope--limitations) for what's deferred.

## About the mzPeak format

mzPeak ([paper DOI 10.1021/acs.jproteome.5c00435](https://doi.org/10.1021/acs.jproteome.5c00435)) stores
mass-spectrometry runs as a set of ZSTD-compressed Parquet files plus a `mzpeak_index.json` manifest,
delivered as either an unpacked directory or a single uncompressed (STORED) ZIP archive. It uses Apache
Parquet's columnar layout to enable granular random access along the spectrum index and m/z axes without
loading the whole file. File-level metadata (instrument configuration, software, run, sample, data
processing) is stored as JSON documents in the manifest, and writers also copy them into the Parquet footer
key-value map. CV/user params follow the PSI-MS controlled-vocabulary model.

**Other implementations** include the primary Rust implementation
[`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping), plus official HUPO-PSI repos for
[C#/.NET](https://github.com/HUPO-PSI/mzPeak.NET), [TypeScript](https://github.com/HUPO-PSI/mzpeakts),
Python, and R. mzPeakJ is an unofficial demonstrator; an official Java implementation is planned.

## Requirements & build

- JDK 17+ and Maven 3.9+.
- Set `JAVA_HOME` if `java` is not on your PATH:
  ```bash
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25   # adjust to your JDK
  ```
- Build and test (from the repository root, both modules):
  ```bash
  mvn verify
  ```

The repository is a Maven reactor (parent `pom.xml`) with two modules:

| Module | Artifact | Contents | Jars in `<module>/target/` |
|---|---|---|---|
| `mzpeakj/` | `org.mzpeak:mzpeakj` | the mzPeak library (reader, writer, CLI, examples) | `mzpeakj-<version>.jar`, `mzpeakj-<version>-all.jar` (self-contained), `mzpeakj-<version>-tests.jar` (test helpers) |
| `mzpeakj-mzml/` | `org.mzpeak:mzpeakj-mzml` | mzML ↔ mzPeak converter on top of the library, through jmzML 1.7.11 as is: `MzMLReader` (mzML → mzPeakJ model), `MzMLWriter` (model → plain mzML) and `MzMLConversion` (both directions) in `org.mzpeak.mzml`, and the CLI `org.mzpeak.mzml.cli.MzPeakConvert` (see [mzML conversion](#mzml-conversion)) | `mzpeakj-mzml-<version>.jar`, `mzpeakj-mzml-<version>-all.jar` (library, converter, jmzML and the JAXB runtime, self-contained; no `Main-Class`: run the CLI with `-cp`) |

`mvn -pl mzpeakj verify` builds and tests the library alone. The mzML module needs the library from the same
build: select it with `-pl mzpeakj-mzml -am`, never with `-pl mzpeakj-mzml` alone (Maven would then look for
`org.mzpeak:mzpeakj` in a repository).

`mzpeakj-mzml` resolves jmzML 1.7.11, xxindex 0.23 and their pride parent POMs from the EBI repository
<https://www.ebi.ac.uk/Tools/maven/repos/content/groups/ebi-repo/> (declared in its POM, checksum policy `fail`);
they are not on Maven Central. Without access to that repository, or a `~/.m2` that holds them already, the root
build fails in `mzpeakj-mzml`; `mvn -pl mzpeakj verify` still builds and tests the library.

The build is self-contained: it never calls Hadoop FileSystem, Configuration, native libraries, or any
cluster setup (see [DESIGN.md](DESIGN.md) §6).

### Test coverage

`mvn verify` also measures test coverage with [JaCoCo](https://www.jacoco.org/) (only the project's own
`org.mzpeak` and `ms.numpress` classes are instrumented) and enforces it in each module: `jacoco:check` fails
the build unless every `org.mzpeak` class of the module has 100% line, branch and method coverage. No
`org.mzpeak` class is excluded. The vendored `ms.numpress` codec of the library, which stays unchanged, is held
at the coverage it reaches (207 of 208 lines, 131 of 132 branches, all methods): the one gap,
`MSNumpress.java` line 73-74, is a `Corrupt numPic data!` throw for a negative count that `decodePic` never
returns.

Each module has its own report, in `mzpeakj/target/site/jacoco/` and `mzpeakj-mzml/target/site/jacoco/`. It is
written before the check runs, so it is there when the check fails: open `index.html` and follow the package
and class to the source, where red lines were never run and yellow lines have a branch that was never taken;
`jacoco.csv` has one row per class with the missed and covered counts, and `jacoco.xml` the same per line for
tools. JaCoCo appends to `target/jacoco.exec`, so a run without `clean` can report an earlier run's coverage:
take coverage evidence only from `mvn clean verify`. The gate needs the whole test suite: pass
`-Djacoco.skip=true` for quick local runs, such as a single test:

```bash
mvn -pl mzpeakj-mzml -am -Djacoco.skip=true -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MzMLTermsTest test
```

(`-pl mzpeakj -am` for a library test). See [DESIGN.md](DESIGN.md) §9.5.

## Library usage

### Reading

```java
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Precursor;
import org.mzpeak.model.Spectrum;
import org.mzpeak.model.Chromatogram;
import java.nio.file.Path;

// Works for both an unpacked directory and a single-file .mzpeak ZIP:
try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"))) {
    System.out.println(reader.size() + " spectra");

    // Random access by spectrum index, native id, vendor scan number, or nearest RT
    Spectrum s = reader.getSpectrum(0).orElseThrow();
    reader.getSpectrumByScanNumber(3);
    reader.getSpectrumById("controllerType=0 controllerNumber=1 scan=3");
    reader.getSpectrumByTime(12.5);

    double[] mz = s.mz();           // m/z array (profile points or centroid m/z)
    double[] intensity = s.intensity();
    s.peaks();                       // CentroidPeak list (centroided spectra only)

    // Activation method + collision energy (from the precursor's activation columns and params)
    Precursor p = s.description().primaryPrecursor();   // null for MS1
    if (p != null) System.out.println(p.activation().summary());

    // Chromatograms and UV/DAD wavelength spectra
    reader.getChromatogramById("TIC").ifPresent(tic -> System.out.println(tic.size() + " TIC points"));
    reader.wavelengthSpectra();     // returns List<WavelengthSpectrum>

    // File/run-level metadata from the manifest (the Parquet footer is the fallback)
    var meta = reader.fileMetadata();
    System.out.println("Instrument: " + meta.instrumentConfigurations().get(0));

    // Tolerance-based centroid peak search
    s.findPeak(810.79, org.mzpeak.model.Tolerance.ppm(20));
    s.findPeaksBetween(800.0, 820.0);

    for (Spectrum spec : reader) { /* iterate all spectra in index order */ }
}
```

Units: `Chromatogram.time()` is in minutes, and the drift-time ion mobility arrays among the extra arrays of
spectra and chromatograms (MS:1002477, MS:1002816, MS:1003007, MS:1003153, MS:1003154, MS:1003156) are in
milliseconds, converted from the unit the file's array index declares. Up to mzPeakJ 0.10.3 these values came in
the file's unit.

#### Options, range queries and the other reader features

One short example per feature. [DESIGN.md §8.2](DESIGN.md#82-reader-api) gives the full contract and the places
where mzPeakJ deliberately differs from the Rust and Python readers (exact interval bounds, empty arrays in the
explicit signal modes, no implicit TIC/BPC by chromatogram index, …).

```java
// imports: org.mzpeak.io.*, org.mzpeak.model.*, java.util.*, java.nio.file.Path, java.nio.channels.FileChannel

// Options: stored profile points only, and only one of the two signal members
MzPeakReader.Options options = MzPeakReader.Options.defaults()
        .withReconstructProfile(false)                      // default: true
        .withSignalMode(MzPeakReader.SignalMode.PROFILE);   // BOTH (default), PROFILE or CENTROID
try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"), options)) {
    // PROFILE: mz()/intensity() hold the data arrays or are empty, peaks() is empty. A spectrum read in
    // PROFILE or CENTROID mode is a partial view: do not pass it to MzPeakWriter.
}

try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"))) {
    // Range queries: closed intervals, compared exactly; one SignalSlice per selected spectrum
    SignalQuery query = SignalQuery.timeRange(0.3, 0.4)    // or indexRange(first, last), or all()
            .withMz(800, 820)                              // also withIonMobility(low, high)
            .withMsLevels(1, 1);
    List<SpectrumDescription> selected = reader.select(query);    // metadata only, reads no signal
    reader.extractSignal(query)                                   // queryPeaks(query) reads the centroids
            .forEach(slice -> System.out.println(slice.index() + ": " + slice.size() + " points"));

    // Column statistics and observed ranges
    Optional<DoubleRange> mzRange = reader.observedMzRange();     // Parquet row-group statistics
    Optional<DoubleRange> timeRange = reader.observedTimeRange(); // retention times held in memory
    reader.columnStatistics(MzPeakManifest.EntityType.SPECTRUM, MzPeakManifest.DataKind.SCANS, "MS:1000016")
            .ifPresent(st -> System.out.println(st.column() + ": " + st.min().orElse(Double.NaN) + " .. "
                    + st.max().orElse(Double.NaN)));

    // TIC and BPC: a stored chromatogram of that type, else the one derived from the spectrum metadata
    Optional<Chromatogram> tic = reader.tic();             // stored MS:1000235 chromatogram, else encodedTic()
    Optional<Chromatogram> bpc = reader.bpc();             // stored MS:1000628 chromatogram, else encodedBpc()
    Optional<Chromatogram> encoded = reader.encodedTic();  // retention time + total ion current per spectrum

    // Extra arrays of spectra and chromatograms, keyed by array type (by name for MS:1000786)
    Spectrum s = reader.getSpectrum(0).orElseThrow();
    Map<String, double[]> arrays = s.arrays();          // parallel to mz(), e.g. MS:1003006 ion mobility
    Map<String, double[]> peakArrays = s.peakArrays();  // parallel to the stored peaks, by CentroidPeak.index()
    Optional<double[]> ionMobility = s.ionMobility();   // the first ion mobility array, if any
    tic.ifPresent(c -> System.out.println(
            c.intensityUnit() + " " + c.arrays().keySet()));   // Chromatogram.arrays(): e.g. MS:1000131 [ms level]

    // Isolation-window params, in both metadata layouts
    Precursor precursor = reader.getSpectrum(5).orElseThrow().description().primaryPrecursor();
    if (precursor != null && precursor.isolationWindow() != null) {
        System.out.println(precursor.isolationWindow().parameters());   // List<Param>
    }
}

// Other storage (object store, byte array, ...): implement RandomAccessBytes, a positional read
RandomAccessBytes bytes = RandomAccessBytes.of(FileChannel.open(Path.of("data/run.mzpeak")));
try (MzPeakReader reader = MzPeakReader.open(MzPeakSource.zip("data/run.mzpeak", bytes))) {
    System.out.println(reader.size() + " spectra");
}
// An unpacked dataset whose members a MemberOpener opens one by one, e.g. objects under a URL prefix:
// MzPeakReader.open(MzPeakSource.unpacked("https://host/run.mzpeak/", name -> openObject(name)))
```

Grid-encoded spectra (`MS:1003826`, such as `diaPASEF.grid`) need no option: they decode like the other chunk
layouts. Manifest checksums are not verified yet.

### Writing

```java
import org.mzpeak.io.MzPeakWriter;

// Point layout (default) — to a directory or STORED ZIP:
MzPeakWriter.writeDirectory(Path.of("out.mzpeak"), spectra, chromatograms);
MzPeakWriter.writeArchive(Path.of("out.mzpeak"), spectra, chromatograms);

// Numpress chunk layout (MS-Numpress linear m/z + SLOF intensity):
MzPeakWriter.writeDirectoryNumpress(dir, spectra, chromatograms);
MzPeakWriter.writeArchiveNumpress(archive, spectra, chromatograms);
// Integer ion-count arrays: use NumpressIntensityEncoding.PIC
MzPeakWriter.writeDirectoryNumpress(dir, spectra, chroms,
        fileMetadata, MzPeakWriter.NumpressIntensityEncoding.PIC);
```

The six-argument overloads additionally carry **wavelength (UV/DAD) spectra** and **imaging scan
settings** through the write, so a read → write cycle is lossless for those facets too:

```java
MzPeakWriter.writeArchive(out, spectra, chromatograms,
        wavelengthSpectra, scanSettings, fileMetadata);          // point layout
MzPeakWriter.writeArchiveNumpress(out, spectra, chromatograms,
        wavelengthSpectra, scanSettings, fileMetadata);          // Numpress layout
```

#### Options and the metadata layout

Every overload above is a shorthand for one of the two full forms, which take all settings as an immutable
`MzPeakWriter.Options`: start from `Options.defaults()` and change a setting with its `with` method.

```java
MzPeakWriter.Options options = MzPeakWriter.Options.defaults()
        .withSignalEncoding(MzPeakWriter.SignalEncoding.NUMPRESS)          // POINT (default) or NUMPRESS
        .withNumpressIntensity(MzPeakWriter.NumpressIntensityEncoding.PIC) // SLOF (default) or PIC
        .withRowGroupSize(64L << 20)                                       // bytes; default: parquet-java's
        .withMetadataLayout(MzPeakWriter.MetadataLayout.SPLIT);            // SPLIT (default) or PACKED
MzPeakWriter.writeArchive(out, spectra, chromatograms,
        wavelengthSpectra, scanSettings, fileMetadata, options);       // or writeDirectory(...)
```

Without a row-group size, the `mzpeak.writer.rowGroupSize` system property applies if it is set.

- **`SPLIT`**, the default, writes the current mzPeak layout. Each metadata facet gets its own table:
  `spectra_metadata` with its `_scans`, `_precursors` and `_selected_ions` tables, and likewise for
  chromatograms and wavelength spectra. Every scan, precursor and selected ion of a spectrum is written. The
  manifest uses the current dialect, with column mappings. The HEAD reference Python reader reads this output.
- **`PACKED`** writes the pin-era layout that mzPeakJ 0.10.3 and earlier wrote: one packed struct per facet,
  and only the first scan and the primary precursor of a spectrum; no wavelength-spectrum Params, and no
  chromatogram polarity, data processing reference, Params or precursors (one WARNING for each kind dropped).
  Use it only for readers of that era; the current Python reader rejects it.

#### Round-trip example (read → write)

```java
try (MzPeakReader r = MzPeakReader.open(Path.of("in.mzpeak"))) {
    List<Spectrum> spectra = new ArrayList<>();
    for (int i = 0; i < r.size(); i++) spectra.add(r.getSpectrumAt(i));
    MzPeakWriter.writeArchive(Path.of("out.mzpeak"),
            spectra, r.chromatograms(),
            r.wavelengthSpectra(), r.scanSettings(), r.fileMetadata());
}
```

Writer notes:
- ZSTD-compressed Parquet, fully Hadoop-free; writes format version `0.9.0`, the CV list, an array index in
  every signal file and a SHA-512 checksum for every member.
- Emits the reference metadata schema: derived spectrum columns (observed m/z range, base peak, TIC,
  spectrum type), precursor **activation** (dissociation method + collision energy), **scan windows**,
  typed **ion-mobility** and **imaging-position** columns, and per-spectrum/scan/selected-ion CV params.
- Centroid data goes to `spectra_peaks`: a CENTROID spectrum whose arrays are its peak list is written there
  only; other point arrays go to `spectra_data` and peak lists to `spectra_peaks` (a spectrum may carry both).
  A CENTROID spectrum with a non-finite m/z keeps both files, since `spectra_peaks` would read such a row back
  as an invented peak (a deviation from the spec's routing).
- An m/z, chromatogram time or wavelength axis that is not ascending is written re-sorted, with one WARNING per
  entity. The sort orders rows by `Double.compare`, so rows with a NaN move to the end.
- Numpress intensity (SLOF) is lossy; total ion current is preserved to well under 1%. The Numpress layout
  takes only finite m/z of at least 0 that its fixed point encodes (see `SignalEncoding.NUMPRESS`; sorted m/z up
  to 21474.83 always are) and refuses any other before anything is written. The point layout writes every m/z,
  but reads a NaN m/z back interpolated, as its null marker; so transition data without a product-ion m/z
  (SRM/MRM) keeps its NaN m/z in neither layout.
- Extra signal arrays are written: those of spectra (`arrays()` and `peakArrays()`, such as per-peak ion
  mobility) as extra columns of `spectra_data` and `spectra_peaks` in both signal encodings, those of
  chromatograms (such as the TIC's `ms level`) as extra columns of `chromatograms_data`, which holds the
  intensities of each chromatogram intensity unit in a column of their own, so the unit is kept (a chromatogram
  without points reads back without one). The default `SPLIT` layout also writes chromatogram polarity, data
  processing reference, Params and precursors, and wavelength-spectrum Params.
- Not yet written: the delta-`chunk` layout and chunked chromatograms; the m/z + intensity payload is preserved
  in full. A `PACKED` write logs one WARNING for each kind of content that layout drops (see above).
- Known limits of the HEAD Python reader (HUPO-PSI/mzPeak `2ee2589`) on mzPeakJ's output: it loses the
  selected ions of precursors whose `precursor_index` is null when other precursors in the file keep theirs
  (`reader.py:421`, a pandas groupby drops NaN keys; mzPeakJ and the Rust reference attach them), its
  pynumpress cannot decode a spectrum of one point in the Numpress layout (a 12-byte linear payload; the writer
  never leaves a one-point chunk otherwise), and it cannot open a dataset with 0 spectra, such as one written for
  a chromatogram-only mzML (an `IndexError` in `_read_spectra`, `reader.py:569-573`).

## Running the CLI & examples

```bash
./run-example.sh <fully.qualified.MainClass> [args...]
```

The repository ships two sets of HUPO-PSI example datasets; each directory records its upstream commit in
`UPSTREAM_COMMIT.txt`. The commands below use the current set.

**Current set** — `mzpeakj/src/test/resources/mzpeak/`, the 8 examples of upstream commit `2ee2589` (split
metadata tables, array index, SHA-512 checksums):

| Fixture | Form | Contents |
|---|---|---|
| `small.unpacked.mzpeak/` | unpacked directory | 48 spectra (14 MS1 profile + 34 MS2 centroid), 1 TIC chromatogram (48 points) |
| `small.mzpeak` | single-file STORED ZIP | same, point layout |
| `small.chunked.mzpeak` | single-file STORED ZIP | same data, delta-`chunk` layout; centroid peaks also for the 14 profile spectra |
| `small.numpress.mzpeak` | single-file STORED ZIP | same data, MS-Numpress linear m/z + SLOF intensity chunks |
| `has_uv.mzpeak` | single-file STORED ZIP | 212 MS spectra + 520 UV/DAD spectra + 2 chromatograms (TIC, DAD) |
| `diaPASEF.ref.mzpeak` | single-file STORED ZIP | 9 spectra (1 MS1 + 8 MS2) of a Bruker diaPASEF run with per-peak ion mobility; TIC and BPC |
| `diaPASEF.grid.mzpeak` | single-file STORED ZIP | the same run with grid-encoded peaks (provisional timsTOF grid models); decodes to the same arrays |
| `Example_Processed.img.mzpeak` | single-file STORED ZIP | 9 profile spectra of an imzML imaging run with pixel positions |

**Legacy set** — `mzpeakj/src/test/resources/mzpeak-legacy/`, the 5 examples of upstream commit `29e59b2` in
the packed metadata layout, which mzPeakJ also reads and writes on request (`MetadataLayout.PACKED`):

| Fixture | Form | Contents |
|---|---|---|
| `small.unpacked.mzpeak/` | unpacked directory | 48 spectra (14 MS1 + 34 MS2), 1 TIC chromatogram |
| `small.mzpeak` | single-file STORED ZIP | same, packed (per-entry zip64 extra fields) |
| `small.chunked.mzpeak` | single-file ZIP | same data, delta-`chunk` layout; secondary peaks for all spectra |
| `small.numpress.mzpeak` | single-file ZIP | same data, MS-Numpress linear+SLOF chunk encoding |
| `has_uv.mzpeak` | single-file ZIP | 212 MS spectra + 520 UV/DAD spectra + 2 chromatograms |

### Example tools

All tools share the filter options `--ms-level 1,2`, `--rt-min M`, `--rt-max M`.

#### `MzPeakFileInfo` — file summary (mzPeak port of OpenMS `FileInfo`)

Prints data ranges (RT / m/z / intensity), spectrum & peak counts per MS level, peak type, precursor charge
distribution, activation methods, instrument configuration, and software. `-s` adds a five-number intensity
summary.

```bash
./mzpeak-fileinfo.sh mzpeakj/src/test/resources/mzpeak/small.mzpeak -s
./run-example.sh org.mzpeak.examples.MzPeakFileInfo mzpeakj/src/test/resources/mzpeak/has_uv.mzpeak
```

#### `MzPeakInfo` — quick per-spectrum dump

```bash
./run-example.sh org.mzpeak.cli.MzPeakInfo mzpeakj/src/test/resources/mzpeak/small.mzpeak 6
```

#### `ExtractSpectra` — filter into a new mzPeak file (reads + writes)

```bash
./run-example.sh org.mzpeak.examples.ExtractSpectra \
    mzpeakj/src/test/resources/mzpeak/small.mzpeak /tmp/ms2.mzpeak --ms-level 2 --rt-max 0.1
```
Produces an unpacked directory or STORED ZIP depending on the output name.

#### `ConvertToDta` — MSn spectra → Sequest `.dta` files

```bash
./run-example.sh org.mzpeak.examples.ConvertToDta \
    mzpeakj/src/test/resources/mzpeak/small.mzpeak /tmp/dta --ms-level 2 --default-charge 2
```
One `.dta` per spectrum (`<base>.<scan>.<scan>.<charge>.dta`); first line `<MH+> <charge>`.

#### `ExtractXic` — extracted-ion chromatogram for a target m/z

```bash
./run-example.sh org.mzpeak.examples.ExtractXic \
    mzpeakj/src/test/resources/mzpeak/small.mzpeak /tmp/xic.csv --mz 810.79 --tol-ppm 20
```
Writes `retention_time,intensity` CSV rows (MS1 by default). Tolerance via `--tol-ppm` or `--tol-da`.

## mzML conversion

The module `mzpeakj-mzml` converts mzML 1.1 to mzPeak and mzPeak to mzML through jmzML 1.7.11 as is
(`org.mzpeak.mzml.MzMLConversion`; `MzMLReader` and `MzMLWriter` are public too). The command-line tool runs from
the module's self-contained jar, which `mvn verify` (or `mvn -DskipTests package`) builds:

```bash
java [-Xmx...] -cp mzpeakj-mzml/target/mzpeakj-mzml-<version>-all.jar org.mzpeak.mzml.cli.MzPeakConvert \
    [--help] [options] <input> <output>

jar=$(ls mzpeakj-mzml/target/mzpeakj-mzml-*-all.jar)
java -cp "$jar" org.mzpeak.mzml.cli.MzPeakConvert mzpeakj-mzml/src/test/resources/mzml/tiny.pwiz.1.1.mzML /tmp/tiny.mzpeak
java -Xmx2g -cp "$jar" org.mzpeak.mzml.cli.MzPeakConvert mzpeakj/src/test/resources/mzpeak/small.mzpeak /tmp/small.mzML
```

On JDK 24 and later, `--enable-native-access=ALL-UNNAMED` before `-cp` silences the JDK's warning about the native
zstd library, as `run-example.sh` does.

The direction comes from the names, case-insensitive:

| Input | Output | Written as |
|---|---|---|
| `<input>.mzML` (plain or indexedmzML, not gzipped) | `<output>.mzpeak` | a single-file ZIP archive; with `--directory` an unpacked directory |
| `<input>.mzpeak` (ZIP) or a dataset directory | `<output>.mzML` | plain mzML 1.1.0, 64-bit float arrays, zlib-compressed unless `--no-zlib` |

| Option | Output | Effect |
|---|---|---|
| `--no-zlib` | mzML | arrays without compression (default: zlib) |
| `--directory` | mzPeak | an unpacked directory instead of a ZIP archive |
| `--layout split\|packed` | mzPeak | metadata layout, default `split` (see [Options and the metadata layout](#options-and-the-metadata-layout)) |
| `--numpress-slof`, `--numpress-pic` | mzPeak | spectra in MS-Numpress chunks: linear m/z with SLOF or PIC intensity (one of the two). Values the codecs cannot encode, such as negative or non-finite ones or PIC intensities that round above 2147483647, fail the conversion (`MzPeakWriter.SignalEncoding.NUMPRESS` lists the ranges) |
| `--row-group-size <bytes>` | mzPeak | Parquet row-group size, a positive number |
| `--help` | | the usage, the options, the limits below and the memory rule |

On success, stdout gets one line, `wrote <output>: N spectra, M wavelength spectra, K chromatograms`. stderr gets a
`warning: ` line for each warning of the library as it happens (such as an m/z array written re-sorted), and after
the run one for each limit of the direction and one for each kind of loss, with its count. Exit codes:

- `0`: the output was written (warnings allowed).
- `1`: the conversion failed, with `error: <message>` on stderr: the input cannot be read or holds what the
  converter rejects (below), or the output cannot be written; an unexpected exception gives `error: <class>:
  <message>`, and running out of heap `error: out of memory converting <input>; rerun with a larger -Xmx (see
  --help)`. No output and no temp file is left. For input whose
  tags do not match, xxindex (jmzML's indexer) prints its own diagnostic lines to stdout first.
- `2`: a usage error, with `error: <message>` and the usage line on stderr; nothing is written. An unknown option, an
  option given twice or without its value, a missing or extra argument, an option for the other direction, both
  Numpress options, a layout other than `split` or `packed`, a row-group size that is no positive number, an input
  ending in `.mzML.gz` ("gunzip it first: jmzML 1.7.11 cannot read gzip-compressed mzML"), an input that is neither
  `.mzML`, `.mzpeak` nor a directory, an output with the wrong extension, an output that exists ("remove it first":
  an output is never replaced).

### Limits

jmzML as is sets these limits. Each is printed as a `warning:` line on every conversion in its direction; the texts
are `MzMLReader.LIMITS` and `MzMLWriter.LIMITS`, and `--help` prints them.

Reading mzML:

- **L-1 Index and open files.** jmzML indexes the whole input before it reads the first spectrum (a byte scan at
  about 60 MB/s) and keeps the index in memory while the reader lives, about 0.3-0.5 KB per spectrum. A reader keeps
  two file handles on the input open until the garbage collector finalizes jmzML's objects (never, when finalization
  is disabled); `MzMLReader` has no `close()`. On Windows the input cannot be moved or deleted meanwhile.
- **L-2 Run params.** `cvParam`, `userParam` and `referenceableParamGroupRef` directly under `run` are not read.
- **L-3 C1 controls.** The characters U+0080-U+009F in the input are read as U+FFFD.
- **L-4 userParam units.** `unitAccession`, `unitName` and `unitCvRef` of userParams are not read.
- **L-5 Undeclared encoding.** A file whose XML declaration names no encoding is read as US-ASCII: every byte >= 0x80
  becomes U+FFFD. ProteoWizard and OpenMS always declare the encoding; the converter's own mzML output does not, so
  non-ASCII text in it is read back as U+FFFD.
- **L-6 Lenient input.** The document is taken as jmzML and JAXB read it: elements the schema does not allow are
  skipped, of two singleton children the last is kept, Base64 is decoded leniently (characters outside the alphabet
  skipped, an unpadded final group dropped, misplaced padding decoded into other bytes), a truncated zlib payload
  gives a shorter array, declared array lengths are not checked against the decoded ones, a missing integer attribute
  reads as 0, references are not whitespace-collapsed, and errors carry no line or column (one found while the index
  is built names no element). A zlib payload with a preset dictionary makes the conversion hang (jmzML's decoder never
  returns), and a zlib bomb, a payload that inflates without bound, exhausts the heap (`OutOfMemoryError`): convert
  only input you trust. When tags do not match, xxindex prints diagnostics (its tag stack) to stdout.

Gzip-compressed mzML is not read at all: gunzip it first. Rejected with an `MzPeakException` (exit 1 in the CLI):
gzipped input (a name ending in `.gz`, in any case: "gzip-compressed mzML is not supported by jmzML 1.7.11 as is:
gunzip it first"; the CLI reports a `.mzML.gz` input as a usage error); UTF-16 and UTF-32 documents; a `run`,
`spectrumList` or `chromatogramList` start tag longer than the 2,048 bytes jmzML's index reads of it; arrays
compressed with the combined MS-Numpress-and-zlib terms MS:1002746, MS:1002747 and MS:1002748; MS-Numpress PIC counts
of 2^31 and above; an empty MS-Numpress linear array (an empty or header-only payload) and an empty SLOF payload; m/z,
intensity, wavelength and time arrays in a unit the model cannot carry; and m/z and intensity arrays (or wavelength
or time and intensity arrays) of different declared lengths. Lenient: of two compression or two data type terms on
one array, jmzML decodes by the one it knows, and the other is not checked. Counted under B-40: the attributes of
`run`, `spectrumList` and `chromatogramList` are taken from their start tags as jmzML's index reads them, so a
character or entity reference stays as written and, whatever the declared encoding, each byte >= 0x80 becomes U+FFFD
(a default ref with such a byte then names nothing, while the header lists keep the character).

Writing mzML:

- **L-8 Plain mzML.** The output has no `indexedmzML` wrapper: no index, no offsets, no checksum. Tools that need
  random access (ProteoWizard, OpenMS) index a plain file when they open it.
- **L-9 Whole document in memory.** jmzML has no streaming writer, so the whole document is built in memory before it
  is written: about 0.75 bytes of heap per byte of the arrays' Base64 text plus 13 KB per spectrum.

Every write through jmzML's `MzMLMarshaller` sets the JVM-wide system property `javax.xml.stream.XMLOutputFactory`
to `com.sun.xml.internal.stream.XMLOutputFactoryImpl`. It changes no output, and the CLI's JVM ends with the run, but
code that embeds `MzMLWriter` or `MzMLConversion.toMzML` keeps the property for every later user of the StAX output
factory in its JVM.

### Memory

mzML → mzPeak reads one spectrum at a time and keeps about 2.5 KB per spectrum (jmzML's index and the mzPeak
writer's entries): 300,000 spectra need about `-Xmx2g`. mzPeak → mzML holds the whole mzML document (L-9); give it

**`-Xmx` >= expected mzML size + 13 KB per spectrum + 1 GB**

which errs on the safe side. The largest mzML output for a heap, estimated from the measurements of the converter's
design (it works up to about 90% of `-Xmx`):

| `-Xmx` | spectra like small.mzML's (67.5 KB of mzML each) | spectra of 15 points (3.5 KB of mzML each) |
|---|---|---|
| 1 GB | ≈ 10,700 spectra, ≈ 0.7 GB of mzML | ≈ 48,000 spectra, ≈ 0.16 GB of mzML |
| 4 GB | ≈ 58,500 spectra, ≈ 3.85 GB | ≈ 260,000 spectra, ≈ 0.9 GB |
| 16 GB | ≈ 249,000 spectra, ≈ 16.4 GB | ≈ 1.1 million spectra, ≈ 3.8 GB |

A JVM that runs out of heap while the document is built fails with `OutOfMemoryError`, which leaves no temp file;
the CLI reports it as an `error:` line (exit 1). `--help` prints both memory rules.

### What is lost

Beyond the limits, every conversion reports what the target format cannot carry as it is, one `warning:` line per
kind with its count: B-1 … B-40 for mzML → mzPeak (→ mzML), such as referenceableParamGroups expanded inline, times
converted to minutes or lost array data processing refs, and A-1 … A-31 for mzPeak → mzML, such as required content
synthesized, ids encoded as NCNames or a run start time rewritten by jmzML. Section 6 of the mzML converter design
lists each loss with its reason; the Javadoc of `MzMLReader`, `MzMLWriter` and `MzMLConversion` describes them.
The mzML writer also warns, with a count, about each PSI mapping rule its output breaks because the input lacks what
the rule asks for (it does not invent the data): spectra without exactly one spectrum type and representation
(`spectrum_must`), scanLists of several scans without a combination term (`scan_must`), spectrum precursor
activations without a dissociation method (`precursor_activation_must`), instrument configurations synthesized or
without exactly one instrument model (`instrumentconfiguration_must`) and chromatograms without exactly one type
(`chromatogram_must`). Added after the design's tables:

- A-31: a chromatogram carries one chromatogram type (its type, else the first type among its params); further
  type params that name another type are dropped and counted.

## Architecture

For format internals and decode decisions (manifest roles and zip64, the split metadata tables and their
key join, column resolution by accession, array-index-driven signal resolution, chunk decoding and the
chunk_start-as-a-point rule, coordinate grids, extra arrays, null-marking reconstruction, the CV tables, the
Hadoop-free Parquet path, the legacy packed layout, the reader API and its deviations from the reference
readers, the fixture sets and golden oracles), see **[DESIGN.md](DESIGN.md)**.

| Package | Responsibility |
|---|---|
| `org.mzpeak.model` | Pure data — no I/O deps: `CentroidPeak`, `Spectrum`, `SpectrumDescription`, `Precursor`, `Activation`, `SelectedIon`, `IsolationWindow`, `ScanEvent`, `Chromatogram`, `WavelengthSpectrum`, `Param`, `Tolerance`, `CvTerms`, … |
| `org.mzpeak.model.meta` | File/run-level metadata records: `FileMetadata`, `InstrumentConfiguration`, `Software`, `MsRun`, `DataProcessing`, `Sample`, `FileDescription` |
| `org.mzpeak.io` | `MzPeakReader`, `MzPeakWriter`, `MzPeakManifest`, `SignalQuery`, `ColumnStatistics`, source abstraction (`MzPeakSource`, `RandomAccessBytes`; `DirectorySource` / `ZipSource` / `UnpackedSource`), metadata + array + chromatogram + wavelength stores |
| `org.mzpeak.io.parquet` | Hadoop-free Parquet I/O: `ParquetGroups` (type-variance-safe accessors), `ZstdCompressionCodecFactory`, `ByteArrayInputFile`, `FileSliceInputFile` |
| `org.mzpeak.cli` / `org.mzpeak.examples` | Runnable demonstrator tools |
| `org.mzpeak.mzml` / `org.mzpeak.mzml.cli` (module `mzpeakj-mzml`) | mzML ↔ mzPeak through jmzML: `MzMLReader`, `MzMLWriter`, `MzMLConversion`; the CLI `MzPeakConvert` |

**Parquet without Hadoop:** mzPeak Parquet files are ZSTD-compressed. Rather than using parquet-java's
default Hadoop-based codec factory, mzPeakJ supplies a `ZstdCompressionCodecFactory` backed by the
self-contained `zstd-jni` jar. No Hadoop FileSystem, Configuration, native libraries, or cluster setup is
ever used. A single self-contained `hadoop-client-api` jar must be on the classpath because parquet-java's
classes statically reference Hadoop types — but it is never actually invoked.

## Format notes

- An unpacked `*.mzpeak/` directory holds Parquet files plus a `mzpeak_index.json` manifest. Files are
  resolved by `(entity_type, data_kind)` pair — **never** by filename. The entity types are `spectrum`,
  `chromatogram` and `wavelength_spectrum`; the data kinds include `metadata`, `scans`, `precursors`,
  `selected_ions`, `data_arrays` and `peaks`.
- Spectrum metadata is split into **one table per facet** (`spectra_metadata`, `_scans`, `_precursors`,
  `_selected_ions`), joined by key: `index` for spectra, `source_index` for the others — **not row-aligned**.
  Joining on row position silently mis-assigns precursors. Columns are found by CV accession through each
  member's `column_mapping`.
- Signal columns are resolved through the member's array index (`<entity>_array_index` in the Parquet
  footer). Signal layouts: **point** (one row per peak, `spectrum_index + mz + intensity`) and **chunk** (one
  row per m/z chunk: `spectrum_index + mz_chunk_start + mz_chunk_end + mz_chunk_values + chunk_encoding +
  intensity`). Chunk encoding uses PSI-MS CV terms `MS:1000576` / `MS:1003088` (absolute, no delta) and
  `MS:1003089` ("truncation, delta prediction and zlib compression"). Delta decoding is **null-aware**: a
  value following a null breaks the prediction chain and is read as an absolute re-anchor, per the spec's
  `null_delta_decode`. Numpress uses `MS:1002312` (linear), `MS:1002313` (PIC), `MS:1002314` (SLOF).
  Coordinate-grid chunks (`MS:1003826`, draft) are decoded with the linear (`MS:1003824`) and square-root
  (`MS:1003825`) models and the provisional timsTOF m/z (`MS:9999002`) and 1/K0 (`MS:9999001`) models
  (DESIGN.md §3.3).
- `spectra_peaks.parquet` is **optional** — it stores centroid peaks separate from profile arrays, allowing
  both to coexist for the same spectrum. The chunked fixture includes centroid peaks for its profile spectra
  (e.g. spectrum 0 has 1612 centroid peaks alongside its 13589-point profile array).
- File/run-level metadata is stored as JSON in the manifest's `metadata` object under the keys
  `file_description`, `instrument_configuration_list`, `software_list`, `data_processing_method_list`,
  `sample_list`, `run` and `scan_settings_list`; writers also copy them into the Parquet footer key-value map,
  which mzPeakJ reads for any document the manifest lacks.
- **Legacy layout** (upstream `29e59b2` and earlier, mzPeakJ output so far): the roles are spelled
  `data arrays` and `wavelength spectrum`, and `spectra_metadata.parquet` holds **packed parallel tables** —
  `spectrum` / `scan` / `precursor` / `selected_ion` struct columns joined by `source_index`, where rows with a
  null struct or `source_index` are placeholders. mzPeakJ reads both layouts.
- Tested against both vendored HUPO-PSI example sets: the current set from upstream commit `2ee2589`
  (`mzpeakj/src/test/resources/mzpeak/UPSTREAM_COMMIT.txt`), checked against golden outputs of the upstream
  Python reader, and the legacy set from upstream commit `29e59b2`
  (`mzpeakj/src/test/resources/mzpeak-legacy/UPSTREAM_COMMIT.txt`).

## Scope & limitations

**Implemented**

- Containers: unpacked `*.mzpeak/` directory **and** single-file STORED `.mzpeak` ZIP (in-place seekable
  reads from the STORED members, no whole-member buffering; zip64 extra fields and zip64 end records).
- Layouts: `point`, delta-encoded `chunk`, MS-Numpress `chunk` (linear m/z + SLOF or PIC intensity), and
  coordinate-grid `chunk` (linear, square-root and the provisional timsTOF grid models).
  Numpress encoding is lossy; total ion current is preserved to <1%.
- Spectra (MS1 + MSn): metadata in the split and the legacy packed layout + precursor/selected-ion/activation
  params (facet join by `source_index`), `double[]` m/z + intensity, centroid peaks, extra arrays such as
  per-peak ion mobility, and profile reconstruction of null-marked points using the stored m/z delta-model
  polynomial (linear interpolation fallback when no model is stored).
- Chromatograms (TIC, DAD/absorption, …) — point and chunk time-axis layouts both decoded, with their intensity
  unit and per-point extra arrays (`Chromatogram.arrays()`, such as the TIC's `ms level`).
- Wavelength (UV/DAD) spectra as a dedicated `WavelengthSpectrum` type.
- File/run metadata from the manifest, with the Parquet footer as fallback: instrument configuration,
  software, run, source files, sample, data processing (read + written).
- Per-spectrum, per-scan, and per-selected-ion CV param lists (read + written).
- Activation methods and collision energy from the precursor's activation columns and params (CID, HCD, ETD, …).
- Ion mobility (the 7 PSI-MS ion mobility types, see [DESIGN.md](DESIGN.md) §5) and imaging position (`IMS:1000050`/`IMS:1000051`)
  decoded from dedicated typed scan columns and CV params; accessible via `ScanEvent.ionMobilityValue()` /
  `ScanEvent.imagingPosition()`.
- Scan settings list from the `mzpeak_index.json` manifest (`scanSettingsList`).
- Random access by index, native id, vendor scan number (Thermo/Waters/Agilent/Bruker/SCIEX), or nearest RT.
- Streaming reader — only the row groups covering a requested spectrum are decoded; bounded memory.
- Reader API ([DESIGN.md](DESIGN.md) §8.2): reader options and signal modes (both members, profile only,
  centroid only), range queries by time or index with m/z, ion-mobility and MS-level filters, metadata column
  statistics and observed m/z and time ranges, a TIC and BPC derived from the spectrum metadata, and a storage SPI
  (`RandomAccessBytes`, `MzPeakSource.zip` / `MzPeakSource.unpacked`) for remote or custom storage.
- Writing: ZSTD Parquet (point layout + Numpress chunk layout with SLOF or PIC intensity), directory and
  STORED ZIP, format version `0.9.0`, in the split metadata layout that the HEAD Python reader reads (the
  pin-era packed layout on request). Round-trips the metadata schema — derived spectrum columns, precursor
  activation, scan windows, typed ion-mobility + imaging-position columns, wavelength (UV/DAD) facets, the
  imaging `scan_settings_list`, the extra signal arrays of spectra and chromatograms and the chromatogram
  intensity unit. The opt-in
  `mzpeak-validate` gate currently fails (validator 0.9.25 predates the array-index schema; whether the CV
  list of `PACKED` output must declare IMS is undecided), so validation is not claimed.
- Runnable example tools: file summary, spectrum extractor, DTA converter, XIC extractor.
- mzML ↔ mzPeak conversion in both directions (`mzpeakj-mzml`, CLI `MzPeakConvert`), see
  [mzML conversion](#mzml-conversion).
- Tests on both vendored fixture sets, checked against golden outputs of the upstream Python reader
  (JDK 17 + 21 via GitHub Actions CI). Opt-in corpus gates
  (`CorpusTest`, `ValidatedRoundTripTest`, `ForwardBackwardCorpusTest`, enabled with
  `-Dmzpeak.roundtrip=true`) expand to hundreds of cases over the local HUPO-PSI + vendor corpus
  (539 `.mzpeak` files; `-Dmzpeak.corpus.dir=<dir>` names it; pass an absolute path, since a relative one
  resolves against the module directory, `mzpeakj/`); cross-validated against the Rust `mzpeak_prototyping`
  reference fixtures and independently verified with pyarrow. The forward-backward test reads each corpus file, re-writes it, runs
  `mzpeak-validate`, and re-reads to assert field-by-field fidelity.

**Deferred / known limitations:**

- **Writing**: the delta-`chunk` layout (the m/z + intensity payload is preserved in full). The `PACKED`
  compatibility layout writes only the primary scan / precursor / selected ion of a spectrum (with a warning),
  so combineIMS frames lose their secondary mobility-scan metadata there, and no wavelength-spectrum Params or
  chromatogram polarity, data processing reference, Params or precursors (with a warning each).
- **Chromatogram chunk writing** (chunk time-axis layout is decoded but not written).
- **Null-marking re-encoding** (the null/sparse profile layout is reconstructed on read; the writer emits
  the resulting arrays, not the original sparse layout).
- **Integrity**: the manifest's SHA-512 checksums are not verified on read.
- **Grid models**: the timsTOF grid accessions `MS:9999001` and `MS:9999002` are provisional; grids are
  decoded in spectrum signal members only.
- **Predicate/page-level pushdown** within a row group (whole row groups are skipped by min/max stats, but all pages within a selected group are decoded).
- **mzML conversion** (`mzpeakj-mzml`): jmzML 1.7.11 as is indexes the whole input, reads it leniently and cannot
  read gzip; the mzML output is plain mzML, built in memory as a whole. See [mzML conversion](#mzml-conversion) for
  the limits L-1 … L-6, L-8 and L-9, the rejected input and the memory rule.
- No **Maven Central** publication yet.

## Development

External adversarial reviews (the `codex` and `vibe` CLIs) were run at each phase boundary; findings drove
hardening of the zip parser, ZSTD codec, the null-aware delta-chunk decoder, boolean param decoding, the
facet-join, and write↔read fidelity. The `ForwardBackwardCorpusTest` closes the loop end-to-end: it reads
every corpus file, re-writes it in each layout, validates the output with the independent
[`mzpeak-validate`](https://github.com/okohlbacher/mzPeakValidator) tool, and re-reads to assert
field-by-field fidelity, aggregating all findings into `mzpeakj/target/forward-backward-report.jsonl`.

Project site: <https://okohlbacher.github.io/mzPeakJ/>

## License

[MIT](LICENSE) © 2026 Oliver Kohlbacher. Bundles:
- Apache-2.0 [MS-Numpress](https://github.com/ms-numpress/ms-numpress) decoders (`mzpeakj/src/main/java/ms/numpress/`)
- Apache-2.0 HUPO-PSI mzPeak example fixtures (`mzpeakj/src/test/resources/`)
- mzML test fixtures and the PSI mzML schemas (`mzpeakj-mzml/src/test/resources/mzml/`)
- CC BY 4.0 PSI-MS instrument-model accessions (`mzpeakj-mzml/src/main/resources/org/mzpeak/mzml/`, also in the
  converter's jars)
- in the two `-all` jars only, the runtime libraries: parquet-java 1.17.1, Jackson 2.18.2, hadoop-client-api 3.5.0,
  snappy-java 1.1.10.7, aircompressor 2.0.2 and commons-pool 1.6 (Apache-2.0); zstd-jni 1.5.7-3 (BSD-2-Clause);
  jts-core 1.20.0 (EPL-2.0 or EDL-1.0); javax.annotation-api 1.3.2 (CDDL + GPLv2 with Classpath Exception);
  slf4j-api and slf4j-nop 1.7.33 (MIT); and the native code compiled into the native libraries of two of them:
  zstd 1.5.7 in zstd-jni's (BSD-3-Clause; zstd is dual-licensed BSD or GPLv2), snappy 1.1.10 (BSD-3-Clause) and
  bitshuffle 0.3.4 (MIT) in snappy-java's. Each `-all` jar carries their licence and notice files (`META-INF/`,
  those of several jars concatenated), this project's as `META-INF/LICENSE.mzpeakj` and `META-INF/NOTICE.mzpeakj`,
  and the texts of [licenses/](licenses/) as `META-INF/LICENSE.<file name>`.
- in `mzpeakj-mzml-<version>-all.jar` only, also the converter's dependencies: jmzML 1.7.11, xxindex 0.23,
  fastutil 6.5.1 and commons-io 2.22.0 (Apache-2.0); and the JAXB RI 2.3.9 set, jaxb-runtime 2.3.9, txw2 2.3.9,
  istack-commons-runtime 3.0.12, jakarta.xml.bind-api 2.3.3 and jakarta.activation 1.2.2 (EDL-1.0, their texts in
  the jar's `META-INF/LICENSE.md` and `NOTICE.md`).

See [NOTICE](NOTICE).
