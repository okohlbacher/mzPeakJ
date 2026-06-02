# Requirements — mzPeakJ (Milestone 1: minimal reader)

Scope: a reader-only Java library for unpacked `*.mzpeak` directories, `point` layout, spectra only, with a
FragPipe/MSFTBX adapter. All v1 requirements are hypotheses until shipped and validated.

## v1 Requirements

### Build & Fixtures (BUILD)
- [ ] **BUILD-01**: Project builds with Maven on JDK 25 (`JAVA_HOME=/opt/homebrew/opt/openjdk@25`) with parquet-java 1.17.0 resolved Hadoop-free (no `org.apache.hadoop` on the runtime classpath)
- [ ] **BUILD-02**: HUPO-PSI example mzPeak fixtures (incl. `small.unpacked.mzpeak/`) are vendored into the repo and the upstream commit hash is pinned/recorded

### Data Model (MODEL)
- [ ] **MODEL-01**: Peak primitives exist — interfaces `CoordinateLike`, `IntensityMeasurement`, `Indexed`, `KnownCharge`, and classes `CentroidPeak(mz, intensity, index)` and `DeconvolutedPeak(neutralMass, intensity, charge, index)`
- [ ] **MODEL-02**: Spectrum model exists — `Spectrum{description, double[] mz, double[] intensity, List<CentroidPeak> peaks}`, `SpectrumDescription{id, index, msLevel, polarity, signalContinuity, scans, precursors}`, plus `Precursor`, `SelectedIon`, `IsolationWindow`, `ScanEvent`, `ScanWindow`, `Tolerance(PPM|Da)`; intensity is stored as `double[]`

### Manifest & Container (MAN)
- [ ] **MAN-01**: Reader parses `mzpeak_index.json` and resolves member Parquet files by `(entity_type, data_kind)` role rather than hardcoded filenames

### Metadata Reader (META)
- [ ] **META-01**: Reader reads `spectra_metadata.parquet` into a spectrum list exposing index, id (nativeID), MS level, retention time, polarity, and signal continuity (centroid/profile)
- [ ] **META-02**: Reader joins the `precursor` / `selected_ion` facets by integer key (`source_index == spectrum.index`) to expose precursor selected-ion m/z, charge state, and isolation window for MSn spectra (null for MS1)

### Peak/Array Reader (PEAK)
- [ ] **PEAK-01**: Reader materializes `point`-layout `spectra_data.parquet` into `double[]` m/z and `double[]` intensity for a requested spectrum index, decoding off Parquet logical types (no hardcoded f32/f64)
- [ ] **PEAK-02**: Reader materializes centroid peaks from `spectra_peaks.parquet` when the manifest declares a `peaks` data_kind
- [ ] **PEAK-03**: Reader resolves a spectrum's rows by index using Parquet row-group/page min-max statistics on `spectrum_index` plus binary search / `RowFilter` (predicate pushdown, not full scan)

### Reader API (API)
- [ ] **API-01**: Public `MzPeakReader` exposes `open(Path)`, `size()`, `getMetadata(index)`, `getSpectrum(index)`, and `iterator()` over spectra

### FragPipe Adapter (FRAG)
- [ ] **FRAG-01**: An isolated adapter converts a `Spectrum` into MSFTBX `umich.ms.datatypes.scan.IScan` + `umich.ms.datatypes.spectrum.ISpectrum` (`SpectrumDefault`) with no widening copies of the intensity array

### Tests (TEST)
- [ ] **TEST-01**: JUnit 5 tests run against the vendored `small.unpacked.mzpeak/` fixture and assert correct spectrum count, metadata (MS level, RT, precursor m/z), and m/z+intensity arrays for at least one MS1 and one MSn spectrum, plus an MSFTBX adapter round-trip

## v2 / Deferred (tracked, not in this milestone)
- Lookup by vendor scan number (parse nativeID) and by retention time (RT→index map)
- Single-file STORED-ZIP `.mzpeak` random access (offset slicing into Parquet members)
- `chunk` layout + MS-Numpress / delta-encoded array decoding
- Chromatograms / wavelength (UV/DAD) spectra
- Profile reconstruction (zero-run / null-marking fill via `mz_delta_model`)
- Row-group caching + detail-level (metadata-only vs full) loading
- Tolerance-based peak search on a spectrum

## Out of Scope (milestone 1, with reasoning)
- **Writing mzPeak** — milestone 1 is reader-only; writing is a separate, larger effort
- **Native dependencies (DuckDB/Arrow JNI)** — rejected to keep the artifact pure-JVM and FragPipe-embeddable
- **Thread-safe concurrent reads** — single-dataset, single-thread documented for v1; concurrency is a later concern

## Traceability
*(Filled by the roadmapper — maps each REQ-ID to a phase.)*
