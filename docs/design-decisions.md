# Design decisions

The decisions of 2026-11-07 to 2026-11-11 that still shape mzPeakJ's code, each with its reason and date. "Maintainer"
marks a decision of the maintainer; the others are defaults the design rounds adopted and the maintainer did not
overrule. Decisions that a later one replaced are listed only where the replacement is not obvious. How the format
works and how mzPeakJ reads and writes it is described in [DESIGN.md](../DESIGN.md); the converter in
[mzML converter](mzml-converter.md).

## 2026-11-07: reader and writer for the current upstream format

The upstream update to HUPO-PSI/mzPeak `2ee2589` (spec snapshot `0220201`, mzdata 0.68.0).

- **Fail loudly.** A read returns correct data or throws an `MzPeakException` naming the member, the entity index and
  the feature. Only secondary extra arrays may be dropped, with a warning; m/z and intensity never are. *Why:* a
  silently wrong m/z or intensity array is the worst outcome for a reader whose core value is correct peaks.
- **One decoder for both metadata layouts.** The legacy packed layout stays readable through the same assembler and
  row decoders as the split layout; legacy behaviour is data (`ColumnTable` fallbacks, an inferred `ArrayIndex`), not a
  second decoder. *Why:* all mzPeakJ output up to 0.10.3 and the corpus are packed; with one decoder the keyed join,
  typed ion mobility and the CV tables exist once.
- **The layout comes from each member's schema** (`MetadataShape` PACKED, SPLIT, EMPTY), never from
  `metadata.version` or from which facet members the manifest lists. *Why:* `metadata.version` is 0.9.0 on both sides
  of the layout change, and a split archive without facet members would be misclassified.
- **Members are found by manifest role, columns by accession.** Metadata columns are resolved through
  `ColumnResolver`, signal columns through the array index (`SignalPlan`), never by file or column name. *Why:* the
  spec makes the array index authoritative and file names free.
- **Null-pair fill keeps mzPeakJ's rule (Q-U4)**, a deliberate deviation from both reference readers, with 5e-5 Da as
  the golden tolerance for filled points. *Why:* Q-U4 is about 1e-6 Da from the only ground truth (small.numpress),
  the reference rule up to 4.3e-5 Da.
- **Unknown encodings throw.** An unknown non-null `chunk_encoding` or main-axis `grid_type` throws; an unknown
  secondary grid drops that extra array with a warning. Grid models are one constant table with provisional
  accessions. *Why:* Rust and Python reject unknown encodings; decoding them as deltas gave wrong arrays silently.
- **Extra arrays.** `Spectrum.arrays()` is an unmodifiable map keyed by `array_type`, or by `array_name` for
  MS:1000786; further value columns of one key are kept as `<key>@<field>`. A bad extra is dropped with a warning,
  never thrown; two value-bearing main-axis entries still throw. *Why:* the spec allows several arrays of one type, so
  throwing would make conformant files unreadable, m/z and intensity included.
- **Chromatograms carry their extra arrays and intensity unit** (`Chromatogram.arrays()`, `intensityUnit`). *Why:*
  every current TIC carries a per-point 'ms level' array, which was dropped without a warning.
- **Logging through `java.lang.System.Logger`** (WARNING, DEBUG). *Why:* in the JDK, no dependency, and embedders can
  plug in their own backend.
- **The writer writes the split layout by default**; `MzPeakWriter.Options` is an immutable class with `with`
  methods, and `MetadataLayout.PACKED` is an opt-in compatibility layout. PACKED is the frozen pin-era dialect and only
  takes additive or ignored changes. *Why:* the HEAD readers read only the split layout; adding components to a public
  record would break callers.
- **Centroid routing.** A CENTROID spectrum whose arrays are a copy of its peaks (by `CentroidPeak.index()`) is written
  to the peaks member only; PROFILE, UNKNOWN and null continuity stay in the data member. *Why:* this matches the
  reference files and keeps profile data where profile readers look.
- **Unsorted main axes are re-sorted on write**, with one stable permutation of the axis, intensity and every extra,
  and one warning per entity; the caller's arrays are not modified. *Why:* the array index declares sorting rank 1 and
  the spec says the writer MUST sort; re-sorting loses no pair, rejecting would fail writes on user data.
- **Entities are renumbered 0..n-1 on write**, `precursor_index` remapped (null when the parent is not written); in the
  split layout two precursors of one spectrum that share the written key `(source_index, precursor_index)` may not both
  carry selected ions (thrown before any I/O). *Why:* both reference joins attach ions to the first matching key, so
  exactly that case would move ions silently.
- **Precursor join.** Key `(source_index, precursor_index)`, null equal to null, in both layouts; ions go to the first
  matching precursor, unmatched ions to the first precursor, orphan ions form a precursor-less `Precursor`. *Why:*
  legacy-compatible and identical to the references on every fixture.
- **Run key.** The reader takes `default_instrument_configuration_id`, else `default_instrument_id`; the writer writes
  only `default_instrument_id`. *Why:* the spec's `ms_run.json` still requires `default_instrument_id`, and writing both
  makes serde reject a duplicate field.
- **Unknown CURIE prefixes.** The writer warns and never throws; the reader keeps the literal value without a warning.
  *Why:* writes must not fail on user data, and the conformance text asks readers to keep unknown terms.
- **Imaging.** `KnownCvs` is a verbatim copy of the reference's CV list (IMS on its master branch); the reader resolves
  every position spelling by accession; the writer writes `opt_IMS_1000050_position_x` and
  `opt_IMS_1000051_position_y`. *Why:* parity with the only producer seen; the draft imaging profile disagrees with
  the reference. Confirmed by D-14.
- **`mz_chunk_end` is the chunk's last value (inclusive).** *Why:* the HEAD reference writes it so, and no reader
  depends on exclusivity.
- **Numeric types in split tables.** Index and count columns UINT64, other integers INT32, floats DOUBLE. *Why:* UINT64
  is a spec MUST; the Rust visitors accept INT32 for the other integers.
- **Integrity report** (`verifyIntegrity`): outcome VALID, INVALID or UNVERIFIABLE; a mismatch or a missing member is
  always INVALID; the static `MzPeakReader.verifyIntegrity(Path)` checks a dataset without opening a reader. *Why:*
  upstream hides a mismatch whenever any checksum is missing, and a corrupt metadata member makes `open` fail.
- **Version-check opt-out.** `MzPeakReader.Options.withVersionCheck(boolean)`, whose default reads the system property
  `mzpeak.reader.ignoreVersion`. *Why:* tests avoid global state; the property serves tools without code changes.
- **Reader API.** Signal modes, range queries with exact closed intervals, column statistics, explicit `tic()` and
  `encodedTic()`, and a storage SPI (`RandomAccessBytes`) follow the spec text; each deviation from the Rust reader is
  documented in DESIGN.md. *Why:* requested features, kept measurable against the reference.
- **mzdata 0.68.** Only `IsolationWindow.parameters` is mirrored. *Why:* every precursor of current small stores it;
  the rest is covered or has no fixture.

## 2026-11-08 and 2026-11-09: mzML converter

Defaults of the converter design, revision 4 (2026-11-09), and the maintainer's decisions of 2026-11-09 that it
follows. The converter page lists the [losses](mzml-converter.md#losses) and [limits](mzml-converter.md#limits) these
decisions cause.

- **jmzML 1.7.11 as is** (maintainer, 2026-11-09): reading through `MzMLUnmarshaller`, writing plain mzML through
  `MzMLMarshaller` with the whole document in memory, jmzML's binary codec, no own StAX cursor, codec or indexedmzML
  envelope; lenient input as jmzML and JAXB read it. *Why:* the fewest own lines; jmzML is the established Java mzML
  library. Confirmed by D-19.
- **Three guards only** (maintainer, 2026-11-09): an empty array is never zlib-compressed, the combined Numpress terms
  MS:1002746/7/8 are rejected, PIC counts of 2^31 and above are rejected. *Why:* only where jmzML would hang or corrupt
  data silently; the project rule is to fail loudly.
- **jmzML from the EBI repository** with checksum policy `fail`, no vendored jar (maintainer, 2026-11-09). Tightened by
  D-2.
- **CLI class `org.mzpeak.mzml.cli.MzPeakConvert`** (2026-11-09). *Why:* no split package with the library.
- **Gzip input is rejected** with "gunzip it first" (2026-11-09). *Why:* jmzML 1.7.11 cannot read gzip at all; a
  later unit could inflate to a temp file.
- **No fourth guard for zlib payloads with a preset dictionary or without bound** (2026-11-09): listed under limit L-6.
  *Why:* the decision names three guards.
- **XML-safe strings and a filled software reference on write** (A-28, A-25) are mapping duties, not guards
  (2026-11-09). *Why:* jmzML would otherwise write a document that cannot be read or fails the XSD.
- **mzML 1.1.0 output with NCName ids** (2026-11-08). *Why:* the widest validator support; OpenMS validates against
  1.1.0.
- **Rule IC for instrument configuration ids** (`IC<n+1>`, `IC_<n>`), no reserved params (2026-11-08). *Why:* exact
  for ProteoWizard output and every fixture.
- **Required content is synthesized only when absent, and warned** (2026-11-08). *Why:* mzML requires it; adding it
  always would break exact round trips.
- **cvParam values are typed by content** (2026-11-08), `INF`, `-INF` and `NaN` included. *Why:* as the reference
  converter does.
- **Wavelength spectra after the MS spectra** in the mzML spectrumList (2026-11-08). *Why:* matches has_uv's source.
- **One representation per mzML spectrum**: peaks that are not a row copy of the arrays are dropped and warned (A-1)
  (2026-11-08). *Why:* mzML holds one representation per spectrum.
- **Selected ions of precursors sharing a parent move to the first of them** (B-19) (2026-11-08). *Why:* that is where
  both reference readers attach them.
- **Chromatogram products are not modelled** (B-27) (2026-11-08). *Why:* no reference writer writes them, the Rust
  reader panics on them, and spec HEAD redefines them.
- **No array descriptor API; fixed default units per array key** (`CvTerms.DEFAULT_ARRAY_UNITS`); an array in a unit
  the model cannot carry is converted, dropped or rejected, never relabelled (2026-11-08). *Why:* never write a unit the
  data were not in.
- **Spectrum intensity arrays outside MS:1000131 are rejected** (2026-11-08). *Why:* the model has no spectrum
  intensity unit.
- **No overwriting**: the converter, the CLI, `MzMLWriter.open` and the public session API refuse an existing target;
  the batch `MzPeakWriter.write*` overloads keep replacing (2026-11-08). *Why:* a conversion must never destroy data;
  the batch overloads keep their published behaviour.
- **The chromatogram data-processing column is written `data_processing_ref`**, read as that or Rust's
  `data_processing_id` (2026-11-08). *Why:* the spec spelling.
- **A NaN m/z in mzML input is warned (B-34), not rejected** (2026-11-08); with mzPeak Numpress output it is refused.
  *Why:* it carries no information, and rejecting the whole file would fail conversions other tools accept.
- **mzML output is always 64-bit float, zlib by default, no MS-Numpress** (2026-11-09). *Why:* exact for `double[]`;
  jmzML's Numpress encoder writes wrong values without an error for values it cannot hold.
- **Param order follows jmzML** (an element's own params before its groups') (2026-11-09). *Why:* order carries no
  meaning in the model, and a reorder would be own code.
- **userParam units, run params and the encoding declaration are not worked around** (L-2, L-4, L-5, A-29, A-30)
  (2026-11-09). *Why:* each would need own code around jmzML's reader or writer.
- **Array keys outside the known array types are written as MS:1000786** with the key as name (2026-11-09). *Why:* they
  come back under the same key and need only the MS CV.
- **`toMzML` checks the target before reading the input** (2026-11-09). *Why:* fail before any work.

## 2026-11-11: decisions for 0.12.0

The maintainer's decisions D-1 to D-20 and the "fix cheaply" items F-1 to F-5. The code and the build cite them by
these ids.

| # | Decision | Reason |
|---|---|---|
| D-1 | Update every dependency with known advisories to the newest fixed version: Jackson 2.23.0, parquet 1.18.2 (with fixed snappy-java and aircompressor and a newer shaded Jackson), assertj 3.27.7; enforcer floors fail the build on a vulnerable version | Jackson 2.18.2 had HIGH advisories, and the fixes exist on three branches, so the floor is the chosen version itself |
| D-2 | Restrict the EBI repository to jmzML's group ids, deny POM-declared repositories, fail closed when the filter is not active, pin the jmzML jar's checksum; Maven pinned by the Maven Wrapper (`./mvnw`, 3.9.x) | the EBI repository must not serve any other artifact; the resolver filter has no prefix wildcard, so the groups are listed exactly |
| D-3 | Converter jar: of fastutil only the classes jmzML needs (81 instead of 11,781), `Main-Class` and `Enable-Native-Access` in the manifest, so that `java -jar mzpeakj-mzml-<version>-all.jar <in> <out>` works | about 15 MB smaller; `minimizeJar` was rejected because it could drop classes loaded by name |
| D-4 | CLAUDE.md describes the current state and the agent-team process | it described an earlier state. Planned for 0.12.0; not yet done when this page was written |
| D-5 | Numpress intensities stay full doubles; no float32 narrowing | the difference is at most 5.95e-8 relative, and arrays are `double[]` throughout |
| D-6 | Split facet rows whose `source_index` names no spectrum are dropped silently | as the Rust reader does |
| D-7 | Manifest parsing as strict as the Rust reader's serde: malformed manifests are rejected with a message naming the entry and field, duplicate known fields included | a tolerant reader let a wrongly typed document shadow a valid one |
| D-8 | A null or blank `ion_mobility_type` next to a value throws | it silently became drift time; Rust panics there |
| D-9 | Packed spectra keep their unconsumed scalar columns as Params (D-9a), and per-row unit columns are resolved for leftover Params and collision energy (D-9b) | the spectrum counterpart of the packed wavelength rule; Rust reads unit columns per row |
| D-10 | Recover the read-path slowdown by resolving metadata columns once per member; bar 0.90 × base on the metadata path, not lowered | measured on the 295,811-spectrum Astral file: 2674 ms → 2232 ms (0.835 × base) |
| D-11 | The Numpress encoder rejects non-finite m/z or intensity and negative SLOF/PIC intensities, with the spectrum index, before anything is written | the vendored codec encodes such values as garbage without an error |
| D-12 | Chromatograms carry their time unit (`Chromatogram.timeUnit()`); the reader returns times as stored, the writer writes the unit, conversion only on request (`withTimeUnit`); the converter keeps the unit (D-12c) | the writer declared minutes for values in the file's unit |
| D-13 | Close every known write loss: ion mobility columns in scans and selected ions, wavelength scans with the full scan schema, chromatogram auxiliary arrays | the values must survive a round trip in the columns readers look at. Planned for 0.12.0; not yet in the code when this page was written |
| D-14 | Imaging output keeps following the Rust reference (IMS URI, `opt_` names, no `is_imaging`) | the reference is the only producer; the profile is a draft |
| D-15 | Duplicate ZIP member names: the last wins, with a warning; archives without spectrum metadata open; a manifest major version above 0 is rejected (`Options.withVersionCheck`) | as the Rust reader; a future major version is a different format |
| D-16 | `verifyIntegrity()` checks the SHA-512 member checksums and reports per member (`IntegrityReport`) | the checksums were written but never checked |
| D-17 | No dangling run, software or data-processing references in written footers | the spec schemas require them to resolve. Planned for 0.12.0; not yet in the code when this page was written |
| D-18 | The pinned Rust reference reader is built in CI and reads every split output mzPeakJ writes ([known issues](upstream-issues.md)) | "readable by the Rust reference" becomes a tested claim |
| D-19 | The converter stays as decided on 2026-11-09 (jmzML as is) | see above |
| D-20 | The converter design and these decisions are committed under `docs/`; the working files of the design rounds stay out of git | the long round files are not documentation |
| F-1 | Signal-file index keys are read unsigned-aware | UINT32 keys of 2^31 and above failed with a wrong message |
| F-2 | The central-directory allocation is bounded: 64 MiB at most, and 196,651 bytes per header | a corrupt record could force an allocation of up to 2 GiB |
| F-3 | Extra arrays keep their stored cells at null-filled m/z positions (pinned by a test) | the design was silent |
| F-4 | `ContentDigest` renders locale-independently, manifests use LF line ends | the schema digest failed under `tr_TR`, and the manifest used the platform's line separator |
| F-5 | The Python oracle environment of the opt-in mzML checks is pinned by a lock file (CPython 3.13.12) | reproducible oracle results |

Kept as they are (the main session's "keep as is" defaults of 2026-11-11, not overruled): split datasets may hold packed-shaped facet members;
`<entity>_count` is archive-wide, as the reference writes it; the packed writer stays frozen; the Numpress dispatch on
an empty linear column keeps failing loudly; `ColumnStatistics.RowGroup` keeps nullable values; signal decode errors
stay per row-group run; the converter's class sizes are accepted. One item of the list, "a split round trip of a legacy
file derives summary values", is reversed by D-9a (below).

## 2026-11-11: defaults of the 0.12.0 round

Questions the round answered itself, by the default closest to the decisions above.

1. D-1 includes parquet 1.18.2, since parquet-jackson 1.17.1 shades a Jackson with the same advisories; zstd-jni stays
   1.5.7-3 (no advisory).
2. D-2 lists the exact EBI group ids (`uk.ac.ebi.jmzml`, `uk.ac.ebi.pride.architectural`, `psidev.psi.tools`); the
   Maven Wrapper's version (newest 3.9.x, at least 3.9.11) is also the build's minimum Maven version; a JUnit test pins
   the jmzML jar's SHA-256.
3. D-3: the jar is checked by an integration test; the usage text says `-jar`.
4. D-7: CURIE syntax is checked by shape, not against a CV list; `parameters` items need `accession` and `unit` keys,
   as serde does; metadata documents are checked for their JSON type only; a non-string `metadata.version` counts as
   absent; duplicate known struct fields are rejected, duplicate unknown keys accepted (as serde).
5. D-8 throws exactly where the drift-time default applied: a finite value without an ion mobility type.
6. **D-9a deviates from the "keep as is" item "a split round trip of a legacy file derives summary values".** With the
   packed spectrum leftovers as Params, a split write of a legacy dataset keeps the stored (float32-widened) summary
   values instead of deriving them from the arrays. Keeping the derivation would have contradicted D-9 (hold back five
   columns) or needed a provenance flag in the public model. Pinned by `legacySplitWritePromotesStoredSummaries`; the
   maintainer may confirm or overrule it.
7. D-9b: a unit path that names no column throws (Rust logs it and reads the unit as unknown); a scan start time with a
   unit path still throws.
8. D-10: the bar is 0.90 × base on the metadata path, not lowered; measured on OpenJDK 27.0.1.
9. D-12: `timeUnit` is the last record component; a file without a time unit reads as minutes; the reader takes the
   unit of the values column, else of the start column; the writer refuses chromatograms of mixed time units; the
   converter keeps chromatogram time units (B-15 narrows).
10. D-13: ion mobility is promoted only for the four types whose reader name and unit are fixed; chromatogram metadata
    gets the `ARROW:schema` hint with its auxiliary arrays.
11. D-17: every dangling footer reference gets a stub with a WARNING, a dangling `default_instrument_id` included; stub
    ids are numeric; entity-level `data_processing_ref` cells are not checked.
12. F-2: 64 MiB central-directory cap and 196,651 bytes per header.
13. F-3: the decoder keeps the stored cell at a null-filled position; a test pins it.
14. F-4 covers `ContentDigest` and the manifest line separator only; `FileMetadata.ComponentType.parse` lower-cases in
    the default locale and is backlog.
15. F-5: lock with hashes, CPython 3.13.12 as the golden environment.
16. The upstream issue table is committed as [upstream-issues.md](upstream-issues.md).
