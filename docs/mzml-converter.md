# mzML converter

The module `mzpeakj-mzml` (artifact `org.mzpeak:mzpeakj-mzml`) converts mzML 1.1 to mzPeak and mzPeak to mzML. This
page distils the converter's design as of mzPeakJ 0.12.0: scope, the decision behind it, the mapping between mzML and
mzPeakJ's model, what is lost, the limits, the memory rule and the command line. The Javadoc of `MzMLReader`,
`MzMLWriter`, `MzMLConversion` and `MzPeakConvert` is the reference for the details; the decisions are listed with
their reasons in [Design decisions](design-decisions.md).

Terms used below:

- **Direction B** is mzML → mzPeak → mzML; **direction A** is mzPeak → mzML → mzPeak.
- **The model** is mzPeakJ's public model: `org.mzpeak.model.*`, `FileMetadata`, `ScanSettings` and the manifest's
  `cv_list`. The converter uses only the library's public API.
- A **limit** (L-n) is something jmzML as is cannot do. It is printed as a fixed warning line on every conversion in
  its direction. A **loss** (B-n, A-n) is content that is dropped or changed. It is printed as one warning line per
  kind, with its count, when it happens, unless the table marks it *silent*.

## Scope

In scope: mzML 1.1 documents, bare or wrapped in `indexedmzML`, read into the model and written to mzPeak; any mzPeak
dataset the library reads, written as plain mzML 1.1.0. MS spectra, wavelength (UV/DAD) spectra, chromatograms and the
file-level metadata are converted.

Out of scope: imzML/.ibd, mzML 1.0, indexedmzML output, MS-Numpress in mzML output, `.mzML.gz` input (jmzML 1.7.11
cannot read it; the reader and the CLI reject it with "gunzip it first"), mzMLb, peak picking and filtering.

## jmzML as is

The maintainer decided on 2026-11-09 to use jmzML 1.7.11 (`uk.ac.ebi.jmzml:jmzml`) as is, with the fewest own lines,
and confirmed it on 2026-11-11 (decision D-19):

1. **Reading** goes through jmzML's `MzMLUnmarshaller`. Accepted: about 0.5 KB of index memory per spectrum, no
   gzip input, loss of run-level params, C1 control characters replaced by U+FFFD, time-zone dependent date parsing.
   There is no own StAX cursor.
2. **Writing** goes through jmzML's `MzMLMarshaller`: plain (non-indexed) mzML only, with the whole document in memory,
   since jmzML has no streaming writer. jmzML's binary codec decodes and encodes the arrays; there is no own codec and
   no own indexedmzML envelope. The combined MS-Numpress terms MS:1002746, MS:1002747 and MS:1002748 are rejected.
3. jmzML is resolved from the EBI https repository with checksum policy `fail`. The repository is restricted to
   jmzML's group ids and the jar's SHA-256 is pinned by a test (D-2).
4. **Lenient input**: whatever jmzML and JAXB do with duplicate singleton children and unpadded Base64 is accepted;
   no extra rejection checks.
5. The command-line tool is `org.mzpeak.mzml.cli.MzPeakConvert`.
6. **Three guards** of own code, only where jmzML would hang or corrupt data silently: an empty array is never
   zlib-compressed (jmzML's encoder does not return on one), the combined Numpress terms are rejected, and MS-Numpress
   PIC counts of 2^31 and above are rejected (jmzML's decoder returns them negative).

Everything these decisions make impossible is a [limit](#limits) or a [loss](#losses), warned at run time. The core
library stays free of jmzML, JAXB and logging frameworks; an enforcer rule fails the build otherwise.

## Command line

The module's shaded jar `mzpeakj-mzml-<version>-all.jar` (built by `./mvnw verify` or `./mvnw -DskipTests package`)
runs with `-jar`; its manifest names the main class and enables native access for the zstd library:

```bash
java [-Xmx...] -jar mzpeakj-mzml-<version>-all.jar [--help] [options] <input> <output>

java -jar mzpeakj-mzml-<version>-all.jar tiny.pwiz.1.1.mzML tiny.mzpeak
java -Xmx2g -jar mzpeakj-mzml-<version>-all.jar small.mzpeak small.mzML
```

The direction comes from the names, case-insensitive:

| Input | Output | Written as |
|---|---|---|
| `<input>.mzML` (plain or indexedmzML, not gzipped) | `<output>.mzpeak` | a single-file ZIP archive; with `--directory` an unpacked directory |
| `<input>.mzpeak` (ZIP) or a dataset directory | `<output>.mzML` | plain mzML 1.1.0, 64-bit float arrays, zlib-compressed unless `--no-zlib` |

| Option | Output | Effect |
|---|---|---|
| `--no-zlib` | mzML | arrays without compression (default: zlib) |
| `--directory` | mzPeak | an unpacked directory instead of a ZIP archive |
| `--layout split\|packed` | mzPeak | metadata layout, default `split` |
| `--numpress-slof`, `--numpress-pic` | mzPeak | spectra in MS-Numpress chunks: linear m/z with SLOF or PIC intensity (one of the two) |
| `--row-group-size <bytes>` | mzPeak | Parquet row-group size, a positive number |
| `--help` | | the usage, the options, the limits and the memory rule |

Exit codes: `0` the output was written (stdout gets `wrote <output>: N spectra, M wavelength spectra, K chromatograms`,
stderr one `warning: ` line per limit and per kind of loss); `1` the conversion failed (`error: <message>` on stderr;
no output and no temp file is left); `2` a usage error (`error: <message>` and the usage line; nothing is written).
An output that exists is never replaced. The same conversions are available from Java through
`MzMLConversion.toMzPeak` and `MzMLConversion.toMzML`, whose `Result` carries the warning lines.

## Mapping

Each mzML construct maps to exactly one model field or to one listed loss. The reader and the writer are each other's
inverse on the model, which the fixpoint tests check byte for byte after one normalization pass. Every mzML element
is read and written through one jmzML class of `uk.ac.ebi.jmzml.model.mzml`; no jmzML or JAXB type appears in the
converter's public API. How the model is stored in mzPeak is described in [DESIGN.md](../DESIGN.md).

### File-level metadata

| mzML | Model | Notes |
|---|---|---|
| `indexedmzML`, `indexList`, `fileChecksum` | — | B: ignored and not verified (B-25). A: never written (L-8) |
| `mzML@version` | — | must start with `1.1`, else rejected; written as `1.1.0` |
| `mzML@id`, `mzML@accession` | — | lost (B-3) |
| `cvList/cv` | `MzPeakManifest.CvEntry` | B: handed to the mzPeak writer, which declares MS, UO and the prefixes in use (B-2). A: the dataset's list plus every prefix in use (A-17) |
| `cvParam@cvRef` other than the accession prefix | — | B: replaced by the prefix (B-30) |
| `fileDescription/fileContent` | `FileDescription.contents` | |
| `sourceFile` (id, name, location, params) | `SourceFile` | `location` is xs:anyURI; invalid values are percent-encoded on write (A-22) |
| `contact` | `Contact` (name from MS:1000586, affiliation from MS:1000590, other params) | name and affiliation are taken verbatim, not typed |
| `referenceableParamGroupList` and refs | params expanded inline by jmzML | B-1; a dangling ref is rejected |
| `sample` | `Sample` | |
| `software` | `Software` | at least one is written (A-8) |
| `scanSettings` (params, `sourceFileRef`, `target`) | `ScanSettings(id, params, sourceFileRefs, targets)` | |
| `instrumentConfiguration@id` | `InstrumentConfiguration.id` (int) | rule IC: `IC<n+1>` for id n ≥ 0, `IC_<n>` for a negative one; other string ids are numbered by position (B-5) |
| `instrumentConfiguration` params, `componentList` | `InstrumentConfiguration.parameters`, `Component(ION_SOURCE\|ANALYZER\|DETECTOR, order, params)` | A: `UNKNOWN` components and lists missing a category are dropped (A-12) |
| `instrumentConfiguration/softwareRef` | `softwareReference` | A: a missing one becomes the first software (A-25) |
| `instrumentConfiguration@scanSettingsRef` | — | lost (B-6) |
| `dataProcessing`, `processingMethod` | `DataProcessing`, `ProcessingMethod` | at least one with a method is written (A-8) |
| `run@id` | `MsRun.id` | A: encoded as an NCName (A-2); `mzpeakj_run` when absent (A-8) |
| `run@defaultInstrumentConfigurationRef` | `MsRun.defaultInstrumentId` | B: a ref naming nothing reads as null (B-39). A: redirected to the first configuration (A-18) |
| `run@defaultSourceFileRef` | `MsRun.defaultSourceFileId` | B: added by the mzPeak writer when absent (B-24) |
| `run@startTimeStamp` | `MsRun.startTime` (String) | B: verbatim. A: only a valid xs:dateTime, through jmzML's `CalendarAdapter` (A-10, A-26) |
| `run@sampleRef` | — | lost (B-7) |
| `run` params | `MsRun.parameters` | B: not read (L-2). A: written, but not read back (A-30) |
| `spectrumList@defaultDataProcessingRef` | `MsRun.defaultDataProcessingId` | A: the first data processing when the run names none (A-8) |
| `chromatogramList@defaultDataProcessingRef` | `Chromatogram.dataProcessingRef` where it differs from the run default | written as list default or per chromatogram, equivalent (B-8) |

Ids are kept as jmzML gives them: JAXB collapses the whitespace of header ids, references and the attributes of
`run`, `spectrumList` and `chromatogramList` are verbatim (the latter as written in their start tags, B-40). On write,
one registry allocates every id, so ids are unique, NCNames where mzML asks for one (A-2, A-3, A-21, A-24) and every
reference resolves (A-18).

### Params

cvParams and userParams become `Param(name, accession, value, unit)` in jmzML's order: an element's cvParams, then
those of its referenced groups, then its userParams and its groups'. A userParam's declared type decides its value's
type; every other value is typed by content (integer, decimal, boolean or text; `INF`, `-INF` and `NaN` included), as
the reference converter does. userParam types are reduced to four, and lexical forms are not kept (B-14). On write, params are written in a canonical order, a cvParam without name
is named from the term table, and every string is made XML-safe first (A-27, A-28, A-29).

### Spectra

| mzML | Model | Notes |
|---|---|---|
| `spectrum` with a wavelength array and no m/z array, or with no arrays and a UV spectrum type | `WavelengthSpectrum` | see below; every other spectrum is an MS spectrum |
| `spectrum@index` | — | B: MS spectra and wavelength spectra are numbered in their own index spaces (B-11). A: MS spectra first, then wavelength spectra |
| `spectrum@id` | `SpectrumDescription.id` | A: an id that is null, empty, malformed or taken is written `index=<i>` (A-3) |
| `@defaultArrayLength` | `numberOfDataPoints` or `numberOfPeaks` | recomputed on write (B-12) |
| `@spotID`, `@sourceFileRef`, `@dataProcessingRef` | — | lost (B-10) |
| MS:1000511 ms level | `msLevel` | A: written when above 0 |
| MS:1000130 / MS:1000129 | `polarity` | both present: unknown, the terms kept as params (warned) |
| MS:1000127 / MS:1000128 | `signalContinuity` | both present: unknown, the terms kept as params (warned) |
| spectrum type, summary params (MS:1000528, MS:1000527, MS:1000504, MS:1000505, MS:1000285), other params | `parameters` | B: the mzPeak writer adds what is missing (B-24). A: a type derived from the MS level when there is none (A-16) |
| `scanList` combination term | `parameters` | A: moved back to the scanList; MS:1000795 'no combination' added for one scan (A-7) |
| other `scanList` params | `parameters` | come back on the spectrum (B-13) |
| `productList` | — | lost (B-20) |
| retention time | `retentionTime` = the first scan's start time in minutes | A: another value is lost (A-9) |
| peak list | `peaks()`, `peakArrays()` | A: written only as a row copy of the arrays; otherwise dropped (A-1) |

### Scans

| mzML | Model (`ScanEvent`) | Notes |
|---|---|---|
| `scan` | one `ScanEvent` per scan | B: the mzPeak writer adds one to a spectrum without scans (B-24) |
| MS:1000016 scan start time (min, s, ms, h) | `startTime` in minutes | B: converted (B-15); another unit is rejected. A: a spectrum-level scan start time is dropped (A-32) |
| MS:1000927 ion injection time (ms) | `injectionTime` | in another unit kept as a param (warned) |
| MS:1000512 filter string | `filterString` | verbatim |
| MS:1000616 preset scan configuration | `presetScanConfiguration` | a non-integer value stays a param |
| `@instrumentConfigurationRef` | `instrumentConfigurationRef` (int, rule IC) | a ref naming nothing reads as null (B-39) |
| `@spectrumRef`, `@externalSpectrumID` | `spectrumReference` | one string; its kind can change on the way back (B-33, B-41, B-42) |
| `@sourceFileRef` | — | lost (B-16) |
| `scanWindow` MS:1000501 / MS:1000500 | `ScanWindow(lower, upper)` in m/z | windows lacking a limit in m/z, and other window params, lost (B-17) |
| other params (ion mobility included) | `parameters` | A: the ion mobility terms first |

### Precursors

| mzML | Model | Notes |
|---|---|---|
| `precursor` | `Precursor` | A: a spectrum precursor's reference follows its `precursorIndex` (A-18, A-23) |
| `isolationWindow` MS:1000827 / 828 / 829 | `IsolationWindow.targetMz`, `lowerOffset`, `upperOffset` | promoted only in m/z or without unit; other params in `IsolationWindow.parameters` |
| `selectedIon` MS:1000744 | `SelectedIon.mz` | a selected ion without m/z in MS:1000040 is lost (B-18) |
| MS:1000041 charge state | `charge` | a non-integer value stays a param |
| MS:1000042 peak intensity | `intensity` | in MS:1000131 or without unit (B-14) |
| `activation` params | `Activation.parameters` | A: dissociation methods first, then MS:1000045 collision energy; always written |
| several precursors with the same parent | — | B: their selected ions move to the first of them (B-19) |

### Chromatograms

| mzML | Model (`Chromatogram`) | Notes |
|---|---|---|
| `@index`, `@id` | `index`, `id` | A: an id that is null, empty or taken is written `index=<i>` (A-3) |
| `@dataProcessingRef` | `dataProcessingRef` | see the file-level table |
| chromatogram type (MS:1000626 descendant) | `typeCurie` | A: one type per chromatogram (A-31) |
| MS:1000130 / MS:1000129 | `polarity` | |
| other params | `parameters` | |
| `precursor` | `precursors` (one) | A: further precursors dropped (A-11), `precursorIndex` not written (A-19) |
| `product` | — | lost (B-27) |
| time array (min, s, ms, h) | `time()` as stored, `timeUnit` | kept in its unit since 0.12.0; B: a chromatogram whose unit differs from the first chromatogram's is converted to that unit (B-15) |
| intensity array and unit | `intensity()`, `intensityUnit` | the unit is kept as given; lost for a chromatogram without points (B-36) |
| other arrays | `arrays()` | as for spectra |

No chromatogram is ever synthesized. An mzPeak dataset without chromatograms gives an mzML without
`chromatogramList`.

### Wavelength spectra

| mzML | Model (`WavelengthSpectrum`) | Notes |
|---|---|---|
| spectrum classified as wavelength | one `WavelengthSpectrum`, index = UV ordinal | A: written after all MS spectra |
| `@id` | `id` | |
| the first scan's MS:1000016 | `time` (min) | other scan content lost (B-23) |
| MS:1003812 lambda max | `lambdaMax` | in nm or without unit, else kept as a param |
| other params | `parameters` | |
| wavelength array, intensity array | `wavelength()`, `intensity()` | nm and MS:1000131; further arrays dropped (B-28) |

### Binary data arrays

| mzML | Model | Notes |
|---|---|---|
| MS:1000514 m/z array | `Spectrum.mz()` | in MS:1000040 or without unit, else rejected; a NaN m/z is interpolated by the mzPeak read (B-34) |
| MS:1000515 intensity array | `intensity()` | spectra: in MS:1000131 or without unit, else rejected |
| MS:1000617 wavelength, MS:1000595 time | the axis of a wavelength spectrum or chromatogram | wavelength in nm; time see chromatograms |
| any other array type | `arrays()` keyed by the CURIE | see the units below |
| MS:1000786 non-standard data array | `arrays()` keyed by its name | B: a name that is an array-type accession is rejected. A: every key that is not a known array type is written as MS:1000786 with the key as name; a nameless one gets the name 'non-standard data array' (A-33) |
| a second array of a type already present | — | dropped (B-29) |
| data types MS:1000521, 523, 519, 522 | `double[]` | B: the type is lost (B-21, silent); 64-bit integers above 2^53 round (B-31). A: always 64-bit float |
| MS:1001479 null-terminated ASCII | — | dropped (B-21) |
| MS:1000520 16-bit float, no data type | — | rejected |
| compression none, zlib | — | decoded by jmzML (B-21, silent). A: zlib unless `--no-zlib`; an empty array is never compressed (guard 1) |
| MS-Numpress MS:1002312, 1002313, 1002314 (alone or with zlib as two terms) | — | decoded by jmzML; PIC counts of 2^31 and above rejected (guard 3); never written |
| MS:1002746, 1002747, 1002748 (Numpress and zlib in one term) | — | rejected (guard 2) |
| any other compression (pwiz truncation, zstd, grid) | — | rejected |
| `@arrayLength`, `@encodedLength` | — | declared lengths classify extra arrays and must agree between the main arrays; decoded lengths are not checked (L-6) |
| array params other than type, data type and compression; `@dataProcessingRef` | — | lost (B-21) |

The model has no array units; the unit of each key is fixed by `CvTerms.DEFAULT_ARRAY_UNITS`:

| Key | Unit |
|---|---|
| MS:1000514 m/z | MS:1000040 |
| MS:1000515 intensity | MS:1000131 |
| MS:1000595 time | UO:0000031 minute |
| MS:1000617 wavelength | UO:0000018 nanometer |
| MS:1002477, MS:1002816, MS:1003007, MS:1003153, MS:1003154, MS:1003156 (drift-time arrays) | UO:0000028 millisecond |
| MS:1003006, MS:1003008, MS:1003155 (inverse reduced ion mobility arrays) | MS:1002814 volt-second per square centimeter |
| `ms level` (MS:1000786 name) | UO:0000186 dimensionless unit |

Reading mzML, an extra array of a key with a default unit is taken in that unit when it declares none or the same; a
drift-time array in seconds or minutes is converted to milliseconds (B-15); any other declared unit drops the array
(B-21). A key without a default unit keeps its values and loses its declared unit (B-21). Writing mzML, every array
gets the default unit of its key, or none; a chromatogram's time array gets its `timeUnit` and its intensity array its
`intensityUnit`.

### Content mzML requires (direction A)

Written only when absent, each warned (A-7, A-8, A-16, A-18):

| Required by mzML | Written when absent |
|---|---|
| a `software` | `mzPeakJ`, version `unknown`, MS:1000799 'custom unreleased software tool' |
| a `dataProcessing` with a `processingMethod` | `mzpeakj_conversion`, MS:1000544 'Conversion to mzML' |
| an `instrumentConfiguration` | `IC1` without params |
| `run@id` | `mzpeakj_run` |
| `run@defaultInstrumentConfigurationRef` | the first instrument configuration |
| the list default data processings | the run's default, else the first data processing |
| a spectra combination term for one scan | MS:1000795 'no combination' |

`MzMLConversion.toMzML` first removes the stubs the mzPeak writer adds when a file has none: the source file
`mzpeakj_source` (named `unknown` at `file:///unknown`) and the data processing `mzpeakj_export`.

PSI mapping rules that would need invented data are not repaired; each violation is warned with a count: spectra
without exactly one spectrum type and representation (`spectrum_must`), scanLists of several scans without a
combination term (`scan_must`), activations without a dissociation method (`precursor_activation_must`), instrument
configurations synthesized or without exactly one instrument model (`instrumentconfiguration_must`) and chromatograms
without exactly one type (`chromatogram_must`).

## Limits

Copied from the constants `MzMLReader.LIMITS` and `MzMLWriter.LIMITS`. Each line is printed as a warning on every
conversion in its direction, before the losses, and by `--help`.

Reading mzML (`MzMLReader.LIMITS`):

```text
L-1 the whole input is indexed before the first element is read, and the index stays in memory while the reader lives (about 0.3-0.5 KB per spectrum; a byte scan of the whole file at about 60 MB/s); the reader keeps two file handles on the input open until the garbage collector finalizes jmzML's objects (never with finalization disabled), so on Windows the input cannot be moved or deleted meanwhile
L-2 run-level params (cvParam, userParam, referenceableParamGroupRef directly under run) are not read
L-3 C1 control characters U+0080-U+009F in the input are read as U+FFFD
L-4 userParam units (unitAccession, unitName, unitCvRef) are not read
L-5 a file whose XML declaration names no encoding is read as US-ASCII: each byte >= 0x80 becomes U+FFFD
L-6 the input is taken as jmzML and JAXB read it: elements the schema does not allow are skipped; of two singleton children the last is kept; Base64 is decoded leniently (characters outside the alphabet skipped, an unpadded final group dropped, misplaced padding decoded into other bytes); a truncated zlib payload gives a shorter array; a zlib payload with a preset dictionary is never decoded (the conversion does not end) and a zlib payload is inflated without bound (a zip bomb ends in OutOfMemoryError); declared array lengths are not checked against the decoded ones; a missing integer attribute reads as 0; refs are not whitespace-collapsed; errors carry no line or column, and an error found while the index is built names no element; xxindex prints its tag stack to stdout when tags do not match
```

Writing mzML (`MzMLWriter.LIMITS`):

```text
L-8 the output is plain mzML: no index, no offsets, no checksum
L-9 the whole mzML document is held in memory before it is written: about 0.75 bytes of heap per byte of the arrays' Base64 text plus 13 KB per spectrum; with zlib, jmzML creates a Deflater per array and never ends it, so resident memory can reach 2-4.5 times -Xmx (without zlib it stays near -Xmx)
```

L-7 (gzip input read without streaming) was withdrawn: jmzML 1.7.11 cannot read gzip input at all, so it is rejected.
Every write through jmzML's `MzMLMarshaller` also sets the JVM-wide system property `javax.xml.stream.XMLOutputFactory`
to `com.sun.xml.internal.stream.XMLOutputFactoryImpl`; it changes no output.

## Losses

One line per loss as of 0.12.0. "Counted by" names the class whose warning line counts it; the line starts with the
loss id. The README's [What is lost](../README.md#what-is-lost) gives examples.

### Direction B (mzML → mzPeak → mzML)

| # | Lost or changed | Counted by |
|---|---|---|
| B-1 | referenceableParamGroups, expanded inline (the group's params after the element's own) | `MzMLReader` |
| B-2 | CV declarations no accession or unit uses | silent |
| B-3 | `mzML@id` and `mzML@accession` | `MzMLReader` |
| B-4 | the input encoding; the output is UTF-8 (a file without encoding declaration: L-5) | silent |
| B-5 | instrument configuration ids other than `IC<n>` and `IC_<n>`, numbered by position | `MzMLReader` |
| B-6 | `instrumentConfiguration@scanSettingsRef` | `MzMLReader` |
| B-7 | `run@sampleRef` | `MzMLReader` |
| B-8 | `chromatogramList@defaultDataProcessingRef` written as list default or per chromatogram (equivalent) | silent |
| B-9 | the arrays of a spectrum, wavelength spectrum or chromatogram without its axis or intensity array | `MzMLReader` |
| B-10 | `spectrum@spotID`, `@sourceFileRef` and `@dataProcessingRef` | `MzMLReader` |
| B-11 | the interleaving of MS and wavelength spectra; spectra whose index is not their position renumbered | `MzMLReader` |
| B-12 | `defaultArrayLength`, recomputed on write | silent |
| B-13 | scanList params other than the combination term, moved to the spectrum | `MzMLReader` |
| B-14 | lexical forms of values, userParam types, unit names; unitless summary, peak-intensity and collision-energy params given their default unit (warned: type mismatches kept as text, units set) | `MzMLReader` |
| B-15 | scan start times converted to minutes and drift-time arrays to milliseconds; chromatogram times kept in their unit, except that a chromatogram in another unit than the first chromatogram's is converted to that unit | `MzMLReader`, `MzMLConversion` |
| B-16 | `scan@sourceFileRef` and `precursor@sourceFileRef` | `MzMLReader` |
| B-17 | scan windows without both limits in m/z, and scan window params other than the limits | `MzMLReader` |
| B-18 | selected ions without an m/z in MS:1000040, which the mzPeak read drops | `MzMLReader` |
| B-19 | selected ions of later precursors with the same parent, moved to the first precursor | `MzMLConversion` |
| B-20 | the spectrum `productList` | `MzMLReader` |
| B-21 | array data type and compression (silent); warned: other array params, array `dataProcessingRef`, declared units of arrays without default unit, arrays in a unit other than their default, arrays of another declared length, ASCII arrays, arrays without array type | `MzMLReader` |
| B-22 | m/z, time or wavelength arrays that are not ascending, written sorted | library warning |
| B-23 | wavelength spectrum content other than the first scan's start time | `MzMLReader` |
| B-24 | additions of the mzPeak writer: spectrum types, the five summary params, a scan for spectra without one, the run's default source file | `MzMLConversion` |
| B-25 | the input index and checksum, not verified | silent |
| B-26 | extra arrays without a value other than NaN, which the mzPeak read drops | `MzMLConversion` |
| B-27 | chromatogram products | `MzMLReader` |
| B-28 | extra arrays of wavelength spectra | `MzMLReader` |
| B-29 | second arrays of a type already present | `MzMLReader` |
| B-30 | cvRef and unitCvRef aliases, replaced by the accession prefix | `MzMLReader` |
| B-31 | 64-bit integer array values above 2^53, rounded to the nearest double | `MzMLReader` |
| B-32 | ids renamed on the mzML write to keep them unique (a header or CV id equal to a written instrument configuration id) | `MzMLWriter`, as A-21 or A-24 |
| B-33 | spectrum references whose kind (local or external) changes on the way back | `MzMLReader` |
| B-34 | NaN m/z values, which the mzPeak read interpolates | `MzMLConversion` |
| B-35 | non-finite file-level param values, which come back as the Strings `Infinity`, `-Infinity` or `NaN` | `MzMLConversion` |
| B-36 | the intensity unit of a chromatogram without points | `MzMLConversion` |
| B-37 | elements the schema does not allow where they stand: part of limit L-6 | not counted |
| B-38 | withdrawn (ids and refs are taken as jmzML gives them, L-6) | — |
| B-39 | instrument configuration refs that name no instrument configuration, read as null | `MzMLReader` |
| B-40 | `run`, `spectrumList` and `chromatogramList` attribute values with `&` kept as written, or with bytes >= 0x80 read as U+FFFD | `MzMLReader` |
| B-41 | an `externalSpectrumID` next to a `spectrumRef`; the model holds one reference | `MzMLReader` |
| B-42 | a `spectrumRef` that names no spectrum of the file, kept without index; it comes back as `externalSpectrumID` | `MzMLReader` |

### Direction A (mzPeak → mzML → mzPeak)

| # | Lost or changed | Counted by |
|---|---|---|
| A-1 | peak lists that are not a row copy of the spectrum's arrays | `MzMLWriter` |
| A-2 | ids that are not NCNames, encoded by rule NC | `MzMLWriter` |
| A-3 | spectrum and chromatogram ids that are null, empty, malformed or taken, written as `index=<i>` | `MzMLWriter` |
| A-4 | param order (canonical) and names of term markers filled | silent |
| A-5 | String cvParam values that the mzML read types as numbers, booleans or non-finite values | `MzMLWriter` |
| A-6 | empty param values, which the mzML read gives as none | `MzMLWriter` |
| A-7 | MS:1000795 'no combination' added to one-scan spectra without a combination term | `MzMLWriter` |
| A-8 | required content synthesized (see above) | `MzMLWriter` |
| A-9 | retention times other than the first scan's start time | `MzMLWriter` |
| A-10 | run start times that are no xs:dateTime | `MzMLWriter` |
| A-11 | chromatogram precursors after the first | `MzMLWriter` |
| A-12 | instrument components of type UNKNOWN, and component lists without a source, analyzer or detector | `MzMLWriter` |
| A-13 | array units and data types as stored in mzPeak, which the converter does not see: arrays are written as 64-bit floats in the default unit of their key (a chromatogram's time in its `timeUnit`), or without unit | `MzMLWriter`, fixed line |
| A-14 | `auxiliary_arrays` of the mzPeak input, which the library does not read | `MzMLWriter`, fixed line |
| A-15 | withdrawn (no mzML output option changes values) | — |
| A-16 | spectrum types derived from the MS level of spectra without one | `MzMLWriter` |
| A-17 | CVs in use but not in the cv list, declared | `MzMLWriter` |
| A-18 | references that name nothing, dropped or redirected; spectrum precursors without index written as `externalSpectrumID` | `MzMLWriter` |
| A-19 | chromatogram precursor indexes | `MzMLWriter` |
| A-20 | withdrawn (chromatogram products are not modelled) | — |
| A-21 | header ids renamed | `MzMLWriter` |
| A-22 | invalid xs:anyURI values, percent-encoded | `MzMLWriter` |
| A-23 | spectrum precursor ids replaced by the id of the spectrum their index names | `MzMLWriter` |
| A-24 | CV ids renamed because the id was taken | `MzMLWriter` |
| A-25 | instrument configurations without software reference, given the first software | `MzMLWriter` |
| A-26 | run start times rewritten by jmzML's `CalendarAdapter` (zone added, fraction cut or padded to milliseconds) | `MzMLWriter` |
| A-27 | values with tab, LF or CR, which XML readers read back as spaces | `MzMLWriter` |
| A-28 | characters XML cannot carry and C1 controls, replaced by U+FFFD | `MzMLWriter` |
| A-29 | values with characters outside ASCII, which jmzML-based readers read as U+FFFD | `MzMLWriter` |
| A-30 | run params and userParam units, written but not read back (L-2, L-4) | not counted |
| A-31 | chromatogram type params other than the chromatogram type | `MzMLWriter` |
| A-32 | spectrum-level scan start times that differ from the first scan's | `MzMLWriter` |
| A-33 | non-standard data arrays without a name, given the name 'non-standard data array' | `MzMLWriter` |

Besides the numbered losses, the reader warns about spectra and chromatograms with both polarities, spectra with both
representations and ion injection times not in milliseconds; the writer about cv list entries without id, CVs
completed from the known CVs, params with neither name nor accession, and cvParams named by their accession.

## Rejected input

`MzMLReader` throws an `MzPeakException` naming the file and, when there is one, the element (`spectrum #<n> (<id>)`
or `chromatogram #<n> (<id>)`), before the mzPeak target is created where it can: a name ending in `.gz`; a path that
is not a readable regular file; a document jmzML cannot index (UTF-16 and UTF-32 included); an mzML version other than
1.1; a document without `run`; a `run`, `spectrumList` or `chromatogramList` start tag longer than 2,048 bytes; a
dangling `referenceableParamGroupRef`; m/z, intensity, wavelength and time arrays and scan start times in a unit the
model cannot carry; main arrays of different declared or decoded lengths; a non-standard data array named with an
array-type accession; the combined Numpress terms; data types and compressions jmzML cannot decode; an MS-Numpress
payload too short for its header; PIC counts of 2^31 and above. With mzPeak Numpress output, values the Numpress
encoder cannot encode fail the conversion. Every exception of jmzML, JAXB or the model is wrapped, with the original
as its cause; a failed conversion leaves no output.

## Memory

mzML → mzPeak reads one spectrum at a time and keeps

**about 2.5 KB per spectrum: `-Xmx2g` per 300,000 spectra**

(jmzML's index and the mzPeak writer's entries). mzPeak → mzML holds the whole mzML document (L-9); give it

**`-Xmx` >= expected mzML size + 13 KB per spectrum + 1 GB**

which errs on the safe side. With zlib, jmzML's `Deflater`s (one per array, never ended) take memory outside the heap,
so the resident memory can reach 2-4.5 times `-Xmx`; `--no-zlib` keeps it near `-Xmx`. Both rules are printed by
`--help`.

The measured heap is about 0.75 bytes per byte of the arrays' Base64 text plus about 11 KB of jmzML objects and 1.9 KB
of reader metadata per spectrum, plus one input row-group run; the converter works up to about 90% of `-Xmx`. The
largest mzML output for a heap, from that measurement:

| `-Xmx` | spectra like small.mzML's (67.5 KB of mzML each) | spectra of 15 points (3.5 KB of mzML each) |
|---|---|---|
| 1 GB | ≈ 10,700 spectra, ≈ 0.7 GB of mzML | ≈ 48,000 spectra, ≈ 0.16 GB of mzML |
| 2 GB | ≈ 26,600 spectra, ≈ 1.75 GB | ≈ 119,000 spectra, ≈ 0.4 GB |
| 4 GB | ≈ 58,500 spectra, ≈ 3.85 GB | ≈ 260,000 spectra, ≈ 0.9 GB |
| 8 GB | ≈ 122,000 spectra, ≈ 8.0 GB | ≈ 545,000 spectra, ≈ 1.9 GB |
| 16 GB | ≈ 249,000 spectra, ≈ 16.4 GB | ≈ 1.1 million spectra, ≈ 3.8 GB |

A JVM that runs out of heap fails with `OutOfMemoryError`, which leaves no temp file; the CLI reports it as an
`error:` line (exit 1). Disk: an mzPeak archive is built in a temp directory next to the target (about twice the
output size); the mzML output is written to a temp file next to the target and then moved.
