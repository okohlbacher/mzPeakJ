# mzPeakJ — Roadmap

Two tracks: **(A) MS-Numpress** (mostly landed) and **(B) Metadata modeling** (the next substantial feature).
This is a demonstrator; phases are sized to be small, independently shippable, and test-driven against the
vendored HUPO-PSI fixtures.

---

## Track A — MS-Numpress

**Status: largely complete.** `linear` (m/z) + `SLOF` (intensity) chunk decoding is implemented (vendored
Apache-2.0 `ms.numpress` decoders; `SpectrumArrayStore.acceptNumpressChunk`). `small.numpress.mzpeak` reads —
counts match the point layout (spectrum 0 = 13589, 5 = 650, 25 = 789); intensities match within ~1% (SLOF is
lossy by design).

| Phase | Scope | Effort | Acceptance |
|---|---|---|---|
| A1 ✅ | Linear + SLOF read | done | numpress fixture reads; cross-container count test + closeness test green |
| A2 | **Numpress PIC** (`MS:1002313`) decode for integer arrays | S | a PIC fixture (or synthetic) decodes to expected integers; wire the existing `decodeNumpress` branch |
| A3 | **Writing** Numpress (encode linear/SLOF in `MzPeakWriter`) | M | write → read round-trip within Numpress tolerance; opt-in via a writer flag |
| A4 | Robustness: mixed chunks (numpress mz + delta intensity, or vice-versa), corrupt-buffer errors | S | unit tests for malformed buffers; clear `MzPeakException` |

Track A is essentially "done + nice-to-haves." A2/A3 only matter if a real PIC or Numpress-writing use case appears.

---

## Track B — Metadata modeling

**Goal:** parse the file/run-level metadata mzPeakJ currently ignores, so `MzPeakFileInfo` can print
instrument name/model, mass-analyzer types + resolution, activation methods, and software — instead of
`"Instrument / activation metadata: not parsed by mzPeakJ (stored in the Parquet footer)"`.

### Where the data lives (from research)

File/run metadata is **JSON in the Parquet footer key-value metadata** of `spectra_metadata.parquet`, under
**per-document keys** (not one blob): `file_description`, `instrument_configuration_list`,
`data_processing_method_list`, `sample_list`, `software_list`, `run`. Read via
`reader.getFooter().getFileMetaData().getKeyValueMetaData()` → `Map<String,String>`, parsed with Jackson
(already a dependency). No new I/O path — `SpectrumMetadataDecoder` already holds the metadata `InputFile`.

The uniform shape: every object = typed identity/reference fields **plus a `List<Param>`** carrying all CV
detail (analyzer type, resolution, activation method, collision energy, software name are all CV params, not
dedicated fields). `Param = {name, accession (CURIE|null), value, unit (CURIE|null)}`.

### Proposed model (`org.mzpeak.model.meta`)

```java
record Param(String name, String accession, Object value, String unit) {}
record SourceFile(String id, String name, String location, List<Param> parameters) {}
record FileDescription(List<Param> contents, List<SourceFile> sourceFiles) {}
enum   ComponentType { ION_SOURCE, ANALYZER, DETECTOR, UNKNOWN }
record Component(ComponentType componentType, int order, List<Param> parameters) {}
record InstrumentConfiguration(int id, List<Component> components, String softwareReference, List<Param> parameters) {}
record Software(String id, String version, List<Param> parameters) {}
record ProcessingMethod(int order, String softwareReference, List<Param> parameters) {}
record DataProcessing(String id, List<ProcessingMethod> methods) {}
record Sample(String id, String name, List<Param> parameters) {}
record MsRun(String id, Integer defaultInstrumentId, String defaultDataProcessingId,
             String defaultSourceFileId, String startTime, List<Param> parameters) {}
record FileMetadata(FileDescription fileDescription, List<InstrumentConfiguration> instrumentConfigurations,
                    List<DataProcessing> dataProcessingMethods, List<Sample> samples,
                    List<Software> software, MsRun run) {}
```
Plus a `Params` helper: `Optional<Param> byAccession(List<Param>, String)`, `has(...)`, and a small CV
constants holder (`MS:1000028` resolution; analyzer/source/detector parents `MS:1000443`/`MS:1000008`/
`MS:1000026`; `MS:1000044` dissociation method; `MS:1000045` collision energy; `MS:1000799` custom software).

### Phases

| Phase | Scope | Effort | Acceptance (against `has_uv.mzpeak` / `small.mzpeak`) |
|---|---|---|---|
| **B1 — Param + footer reader** | `Param` record; `FooterMetadata.read(InputFile)` returns the raw `Map<String,String>`; Jackson-deserialize the 6 keys leniently (missing key → empty). Expose `FileMetadata MzPeakReader.fileMetadata()`. | M | unit test: the 6 keys present in a fixture parse without error; absent keys yield empty objects; no failure on a file lacking them |
| **B2 — Software + instrument config** | Resolve software name (`MS:1000799` value or name accession) + version; walk `instrument_configuration_list` components → ion-source/analyzer/detector types + resolution (`MS:1000028`) + instrument model. | M | `MzPeakFileInfo` prints a real "Software:" list and "Instrument: … / Mass analyzer: … (resolution …)" for a fixture that has them; replaces the hardcoded "not parsed" line |
| **B3 — Activation + collision energy** | Thread the precursor `activation.parameters` and `selected_ion`/`precursor` param lists through `SpectrumMetadataDecoder` into the model; aggregate dissociation methods (`MS:1000044` children) + collision energies across precursors. | M | `MzPeakFileInfo` prints an "Activation methods" breakdown (CV name + count) and energies; values match the fixture |
| **B4 — Completeness** | `file_description` (source files, contents), `run` (start time, default refs), `sample_list`, `data_processing_method_list`; surface in a `--meta`/verbose FileInfo mode. | S–M | each document round-trips into the model; verbose summary renders them |

### Risks / notes
- Example files may omit footer keys → **be lenient** (empty/absent, never throw).
- `InstrumentConfiguration.id` is an **integer** (matches `run.default_instrument_id`); other refs are strings.
- Analyzer/resolution/activation are **CV params**, resolved by accession — don't invent typed fields.
- A PSI-MS CV name lookup (accession → human name) would make the output prettier; the `Param.name` field
  usually already carries the name, so a full CV file isn't required for B2/B3.

### Suggested order
B1 → B2 (the headline FileInfo win) → B3 (activation) → B4. Track A is parked at A1 unless PIC/writing is needed.

---

*Background research and exact schema/field references are summarized in commit history and DESIGN.md.
Reference model: mzdata (`meta`/`params`) and `mzpeak_prototyping` (`src/param.rs`, `src/reader/metadata.rs`).*
