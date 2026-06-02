# Architecture Research — mzPeakJ

Mirrors the Rust reference's 3-layer split (`mzpeaks` → `mzdata` → `mzpeak_prototyping`).

## Components & boundaries

```
┌─────────────────────────────────────────────────────────────┐
│ org.mzpeak.model        (no I/O deps — pure data)            │
│   interfaces: CoordinateLike, IntensityMeasurement,         │
│               Indexed, KnownCharge                           │
│   peaks:      CentroidPeak(mz,intensity,index),             │
│               DeconvolutedPeak(neutralMass,intensity,charge,index) │
│   spectrum:   Spectrum{desc, double[] mz, double[] intensity,│
│               List<CentroidPeak> peaks}                      │
│   desc:       SpectrumDescription{id,index,msLevel,polarity, │
│               signalContinuity, List<ScanEvent>, List<Precursor>} │
│   support:    Precursor, SelectedIon, IsolationWindow,      │
│               ScanEvent, ScanWindow, Tolerance(PPM|Da)       │
└─────────────────────────────────────────────────────────────┘
                         ▲ produced by
┌─────────────────────────────────────────────────────────────┐
│ org.mzpeak.io           (parquet-java + jackson)            │
│   MzPeakManifest        parse mzpeak_index.json → file roles │
│   MzPeakReader          open(Path)/size()/getMetadata(i)/    │
│                         getSpectrum(i)/iterator()            │
│   internal: SpectrumMetadataDecoder (packed parallel tables  │
│             → SpectrumDescription; join facets on source_index)│
│             PointArrayReader (spectra_data/peaks → arrays,    │
│             row-group/page pushdown on spectrum_index)        │
│             ParquetInputFile (Hadoop-free InputFile)         │
└─────────────────────────────────────────────────────────────┘
                         ▲ consumed by
┌─────────────────────────────────────────────────────────────┐
│ org.mzpeak.fragpipe     (depends on msftbx — ISOLATED)      │
│   MsftbxAdapter         Spectrum → umich.ms.datatypes.IScan  │
│                         + ISpectrum (SpectrumDefault)        │
└─────────────────────────────────────────────────────────────┘
```

## Data flow (read path for `getSpectrum(i)`)

1. `open(dir)` → read `mzpeak_index.json`, resolve `spectra_metadata`/`spectra_data`/`spectra_peaks` files,
   open Parquet footers (schema + row-group stats + key-value JSON metadata).
2. Metadata: project `spectrum`/`scan`/`precursor`/`selected_ion` struct columns from `spectra_metadata.parquet`;
   decode rows into `SpectrumDescription`; join facets on integer keys (`source_index == spectrum.index`).
   Cache the small `time`/`id` columns → build RT/scan# maps (deferred-friendly).
3. Random access: find row group whose `spectrum_index` [min,max] contains `i`; narrow via page index;
   binary-search the contiguous block `== i`.
4. Materialize: with a `ProjectionMask` (mz,intensity) + `RowFilter`(`spectrum_index == i`), read rows →
   `double[]` m/z + `double[]` intensity. Profile → `spectra_data`; centroid → `spectra_peaks`
   (choose by `signalContinuity` + which files exist).
5. Wrap arrays + description into `Spectrum`. FragPipe layer converts on demand.

## Build order implications (→ phases)

- **model** has no I/O deps → build & unit-test first (P1).
- **io.MzPeakManifest + SpectrumMetadataDecoder** next — the spine; testable against `small.unpacked.mzpeak/` (P2).
- **io.PointArrayReader + random access** — highest risk (Parquet pushdown, type variance) (P3).
- **fragpipe.MsftbxAdapter** last — mechanical once model is stable (P4).
- **P0** scaffolds Maven modules, Hadoop-free parquet-java deps, JDK/JAVA_HOME, and acquires test fixtures.

## Cross-cutting decisions

- Drive all decoding off Parquet logical types + the array-index annotations (footer JSON), never assume
  physical types (m/z f32/f64, intensity f32/i32/f64, list/large_list).
- Keep `msftbx` strictly behind the `fragpipe` module so core stays dependency-light.
- Reader is single-dataset, not thread-safe in v1 (document it); caching is a v1.x concern.
