# Research Summary — mzPeakJ

Source-verified synthesis (HUPO-PSI `doc/index.md` + actual example files, docs.rs/mzpeaks, mzdata,
mzpeak_prototyping, MSFTBX). Full detail in `PROJECT-BRIEF.md` and the dimension files in this directory.

## Stack (recommended)
- **Java** (target bytecode 17, build on JDK 25 / Homebrew `openjdk@25`), **Maven** multi-module.
- **parquet-java 1.17.0, Hadoop-free** for Parquet (pure JVM, LIST + projection + pushdown; `InputFile` abstraction).
- **Jackson** for `mzpeak_index.json` + Parquet footer JSON. **JUnit 5 + AssertJ** for tests.
- **MSFTBX** (`com.github.chhh:msftbx`) only in an isolated `fragpipe` adapter module.
- Rejected: DuckDB/Arrow (native JNI), Hadoop (bloat), parquet-floor (no nested LIST).

## What mzPeak is
A set of Parquet files + a `mzpeak_index.json` manifest (unpacked dir or STORED ZIP). Metadata is "packed
parallel tables" (`spectrum`/`scan`/`precursor`/`selected_ion` struct columns joined by integer keys;
`spectrum.index` PK). Signal is long/tall `point` rows (`spectrum_index, mz, intensity`); centroids in a
parallel peaks file. **No scan-number or RT index** — random access via Parquet row-group/page stats on the
sorted `spectrum_index` column. Unstable prototype → pin a commit; never assume physical types.

## Table stakes (v1)
Open unpacked dir → list spectra (index/MS-level/RT/polarity/precursor) → materialize `double[]` m/z +
intensity by index (point layout, with pushdown) → iterate → convert to MSFTBX `IScan`/`ISpectrum`. Tests on
`small.unpacked.mzpeak/`.

## Deliberately deferred
Writing, single-file ZIP, chunk/numpress/delta, chromatograms, profile reconstruction, scan#/RT lookup beyond basic maps.

## Architecture (3 layers, mirrors Rust)
`org.mzpeak.model` (pure data: peak primitives + interfaces + Spectrum/SpectrumDescription/Precursor) →
`org.mzpeak.io` (MzPeakManifest, MzPeakReader, metadata decoder, point array reader, Hadoop-free InputFile) →
`org.mzpeak.fragpipe` (MsftbxAdapter, isolated). Build order: model → manifest+metadata → array reader+random
access → adapter. Intensity stored as `double[]` throughout (zero-copy to MSFTBX).

## Watch out for (top pitfalls)
1. Don't hardcode Parquet physical types (f32/f64, list/large_list) — drive off logical type + array-index annotation.
2. Don't assume scan#/RT indices exist — address by `index`; build maps yourself.
3. Keep Hadoop out of parquet-java (PlainParquetConfiguration + custom InputFile; exclude hadoop-*; assert classpath).
4. Join packed parallel facets by integer key (`source_index`), never by row position.
5. Push down (row-group + page + RowFilter) — naive full scans are O(n²).
6. Pin upstream commit + vendor fixtures (moving prototype).

## Process (user-mandated)
Run a **codex CLI adversarial review at the start and end of every phase** (`codex exec --skip-git-repo-check`
for design review, `codex exec review` for diffs). Tests must use existing HUPO-PSI example data.
