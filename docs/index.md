# mzPeakJ

A pure-JVM **Java reader and writer** for [mzPeak](https://www.psidev.info/mzpeak) — the next-generation
mass-spectrometry data format built on Apache Parquet, under development by the
[HUPO-PSI](https://www.psidev.info/).

> **Demonstrator / proof-of-concept.** mzPeakJ is vibe-coded by following the mzPeak reference
> implementations (primarily the Rust crate
> [`mzpeak_prototyping`](https://github.com/mobiusklein/mzpeak_prototyping) and the HUPO-PSI
> [spec + example files](https://github.com/HUPO-PSI/mzPeak)). It is meant for exploration and to show
> that a clean, dependency-light JVM reader/writer is feasible. mzPeak itself is explicitly an unstable
> prototype ("no stability is guaranteed at this point").

[View on GitHub](https://github.com/okohlbacher/mzPeakJ){: .btn }
&nbsp;
[Design notes](https://github.com/okohlbacher/mzPeakJ/blob/main/DESIGN.md){: .btn }
&nbsp;
[Releases](https://github.com/okohlbacher/mzPeakJ/releases){: .btn }

---

## About mzPeak

mzPeak ([paper doi:10.1021/acs.jproteome.5c00435](https://doi.org/10.1021/acs.jproteome.5c00435)) is an
Apache Parquet–based archive format for mass-spectrometry instrument runs, designed by Joshua Klein et al.
and submitted to HUPO-PSI for standardisation. It stores spectra, chromatograms, and UV/DAD wavelength
spectra as ZSTD-compressed Parquet files alongside a `mzpeak_index.json` manifest, delivered either as an
unpacked directory or a single uncompressed (STORED) ZIP archive.

Reference implementations exist in Rust, Python, R, C# (.NET), and TypeScript. mzPeakJ is an unofficial
Java demonstrator; an official Java implementation is planned by the HUPO-PSI.

---

## Highlights

- **Reads** unpacked `*.mzpeak/` directories and single-file STORED `.mzpeak` ZIPs — STORED members are
  read in-place from the archive (seekable file slice, no whole-member buffering).
- **Three signal layouts**: `point` (one row per data point), delta-`chunk` (delta-encoded fixed-width
  m/z chunks), and MS-Numpress `chunk` (lossless linear m/z + lossy SLOF/PIC intensity compression).
- **Spectra** (MS1 + MSn): profile and centroid, with metadata, precursor, activation params (CID/HCD/ETD
  + collision energy), isolation window, per-spectrum/scan CV param lists, secondary centroid peaks, and
  polynomial profile reconstruction from the stored `mz_delta_model`.
- **Chromatograms** (TIC, DAD/absorption, …) and **UV/DAD wavelength spectra**.
- **File/run-level metadata** from the Parquet footer: instrument configuration (analyzer types, resolution),
  software list, run, source files, data processing, sample.
- **Writes** point and Numpress chunk layouts, with footer metadata and CV param lists.
- **No Hadoop runtime** — custom `ZstdCompressionCodecFactory` means no Hadoop FileSystem, Configuration,
  or native libraries are ever touched.
- **123 tests**, cross-validated against the Rust and Python reference test suites. CI on JDK 17 + 21.

---

## Quick start

```java
import org.mzpeak.io.MzPeakReader;
import org.mzpeak.model.Spectrum;

// Directory or single-file .mzpeak ZIP — same API:
try (MzPeakReader reader = MzPeakReader.open(Path.of("data/run.mzpeak"))) {
    System.out.println(reader.size() + " spectra");

    Spectrum s = reader.getSpectrum(0).orElseThrow();
    double[] mz = s.mz();
    double[] intensity = s.intensity();
    s.peaks();         // CentroidPeak list (centroided or secondary-centroid spectra)

    // File/run metadata
    var meta = reader.fileMetadata();
    meta.software().forEach(sw -> System.out.println(sw.displayName()));
}
```

```java
import org.mzpeak.io.MzPeakWriter;

// Point layout (default):
MzPeakWriter.writeArchive(Path.of("out.mzpeak"), spectra, chromatograms);

// Numpress chunk layout:
MzPeakWriter.writeArchiveNumpress(Path.of("out.mzpeak"), spectra, chromatograms);
```

CLI (no classpath assembly needed with the shaded jar):

```bash
java --enable-native-access=ALL-UNNAMED \
  -cp mzpeakj-0.10.2-all.jar org.mzpeak.cli.MzPeakInfo run.mzpeak
# or with the helper script:
./run-example.sh org.mzpeak.examples.MzPeakFileInfo run.mzpeak -s
```

---

## Example tools

All tools share `--ms-level`, `--rt-min`, `--rt-max` filter options.

| Tool | What it does |
|---|---|
| `MzPeakFileInfo` | File summary: data ranges, peak counts, activation methods, instrument config, software — OpenMS `FileInfo` style |
| `ExtractSpectra` | Filter spectra into a new mzPeak file (reads + writes) |
| `ConvertToDta` | MSn → Sequest `.dta` files |
| `ExtractXic` | Extracted-ion chromatogram CSV for a target m/z |

---

## Bundled fixtures

Five HUPO-PSI example datasets are included under `src/test/resources/mzpeak/`:

| Fixture | Layout | Contents |
|---|---|---|
| `small.unpacked.mzpeak/` | point, directory | 48 spectra (14 MS1 + 34 MS2), TIC |
| `small.mzpeak` | point, STORED ZIP | same, packed (per-entry zip64 extra fields) |
| `small.chunked.mzpeak` | delta-chunk, STORED ZIP | same data + secondary centroid peaks for every spectrum |
| `small.numpress.mzpeak` | Numpress chunk, STORED ZIP | same data, MS:1002312 linear m/z + MS:1002314 SLOF intensity |
| `has_uv.mzpeak` | point, STORED ZIP | 212 MS spectra + 520 UV/DAD spectra + TIC & DAD chromatograms |

---

## Status

Current version: **0.10.2** (see [releases](https://github.com/okohlbacher/mzPeakJ/releases)).

**Implemented** — all four container/layout variants; streaming row-group reader; file/run metadata
read+write; CV param write-back; Numpress linear+SLOF+PIC read/write; exact `mz_delta_model` reconstruction;
tolerance-based centroid peak search; vendor-agnostic scan-number lookup; OpenMS-FileInfo-style summary tool.

**Known deferred** — chunk/delta write, wavelength spectra write, page-level pushdown,
detail-level loading, Maven Central publication.

MIT-licensed. Bundles the Apache-2.0 [MS-Numpress](https://github.com/ms-numpress/ms-numpress) decoders
and HUPO-PSI example fixtures. See [NOTICE](https://github.com/okohlbacher/mzPeakJ/blob/main/NOTICE).
