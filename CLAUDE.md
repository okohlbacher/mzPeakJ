<!-- GSD:project-start source:PROJECT.md -->

## Project

**mzPeakJ**

mzPeakJ is a **demonstrator** Java library that reads and writes the [mzPeak](https://www.psidev.info/mzpeak)
mass-spectrometry data format — a HUPO-PSI prototype format built on Apache Parquet. It is vibe-coded by
following the Rust reference implementations (`mzpeaks` / `mzdata` / `mzpeak_prototyping` by mobiusklein) and
the HUPO-PSI spec + example files. It opens an unpacked `*.mzpeak` directory or single-file ZIP, lists spectra,
materializes per-spectrum m/z + intensity arrays, and can write mzPeak back out. Built for exploration, not production.

**Core Value:** Given an mzPeak dataset, return correct per-spectrum **m/z + intensity arrays** and spectrum
metadata (index, MS level, RT, precursor). If everything else fails, reading a spectrum's peaks correctly must work.

### Constraints

- **Tech stack**: Java (Maven build), Parquet via `parquet-java` (org.apache.parquet) — Why: pure-JVM; no Hadoop FileSystem/Configuration or native code is used (a self-contained `hadoop-client-api` jar is on the classpath only because parquet-java's classes reference Hadoop types).
- **Data structures**: store intensity as `double[]` so consumers get arrays directly with no widening copy.
- **Process**: run a `codex` CLI adversarial review at the **start** (plan/design) and **end** (diff) of every phase — Why: user-mandated external quality gate. `codex exec --skip-git-repo-check "<prompt>"` / `codex exec review`.
- **Testing**: tests must use the existing HUPO-PSI example `.mzpeak` data — Why: validate against real fixtures, not synthetic.
- **Dependencies**: avoid Hadoop transitive bloat and all native code — Why: keep the artifact lean and portable.

<!-- GSD:project-end -->

<!-- GSD:stack-start source:research/STACK.md -->

## Technology Stack

## Recommended stack

| Concern | Choice | Coordinates / version | Rationale | Confidence |
|---|---|---|---|---|
| Language/runtime | Java 17 bytecode target on JDK 25 | Homebrew `openjdk@25` 25.0.2 | JDK 25 is what's installed; target 17 for broad JVM compatibility. | High |
| Build | Maven | 3.9.15 (installed) | Already present; Gradle absent. Simpler single-module → multi-module later. | High |
| Parquet I/O | **parquet-java (parquet-mr)**, Hadoop-free | `org.apache.parquet:parquet-hadoop` + `parquet-column` + `parquet-common` **1.17.0** | Pure JVM, best-in-class LIST + projection + predicate pushdown, reads via `InputFile` (no Hadoop `Path` needed using `PlainParquetConfiguration` + `LocalInputFile`). Exclude `hadoop-common`/`hadoop-mapreduce` transitively. | High |
| JSON (manifest + footer metadata) | Jackson | `com.fasterxml.jackson.core:jackson-databind` 2.18.x | Parse `mzpeak_index.json` and Parquet footer key-value JSON. Ubiquitous, no native deps. | High |
| Tests | JUnit 5 + AssertJ | `org.junit.jupiter` 5.11.x, `org.assertj` 3.26.x | Standard. Drive from HUPO-PSI example data. | High |

## Explicitly NOT using (and why)

- **DuckDB JDBC** — native JNI (~tens of MB, per-platform), and can't read Parquet sliced from inside the STORED ZIP. Rejected to keep the artifact pure-JVM. (Was a viable fast-prototype alternative; user chose pure JVM.)
- **Apache Arrow Java (arrow-dataset)** — off-heap memory management + JNI native libs; overkill for materializing plain `double[]`.
- **parquet-floor** — nested LIST support undocumented/unverified; dealbreaker for m/z+intensity arrays.
- **Hadoop** (`hadoop-common` et al.) — heavy transitive bloat; avoided via the Hadoop-free parquet-java path.
- **Carpet** — ergonomic record wrapper over parquet-mr, but limited predicate pushdown; reconsider only if record-mapping ergonomics dominate.

## Key integration risk to validate early (P0/P2)

<!-- GSD:stack-end -->

<!-- GSD:conventions-start source:CONVENTIONS.md -->

## Conventions

Conventions not yet established. Will populate as patterns emerge during development.
<!-- GSD:conventions-end -->

<!-- GSD:architecture-start source:ARCHITECTURE.md -->

## Architecture

Architecture not yet mapped. Follow existing patterns found in the codebase.
<!-- GSD:architecture-end -->

<!-- GSD:skills-start source:skills/ -->

## Project Skills

No project skills found. Add skills to any of: `.claude/skills/`, `.agents/skills/`, `.cursor/skills/`, `.github/skills/`, or `.codex/skills/` with a `SKILL.md` index file.
<!-- GSD:skills-end -->

<!-- GSD:workflow-start source:GSD defaults -->

## GSD Workflow Enforcement

Before using Edit, Write, or other file-changing tools, start work through a GSD command so planning artifacts and execution context stay in sync.

Use these entry points:

- `/gsd:quick` for small fixes, doc updates, and ad-hoc tasks
- `/gsd:debug` for investigation and bug fixing
- `/gsd:execute-phase` for planned phase work

Do not make direct repo edits outside a GSD workflow unless the user explicitly asks to bypass it.
<!-- GSD:workflow-end -->

<!-- GSD:profile-start -->

## Developer Profile

> Profile not yet configured. Run `/gsd:profile-user` to generate your developer profile.
> This section is managed by `generate-claude-profile` -- do not edit manually.
<!-- GSD:profile-end -->
