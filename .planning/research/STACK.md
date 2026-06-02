# Stack Research — mzPeakJ

Distilled from source-verified research (see `PROJECT-BRIEF.md`). Confidence: High unless noted.

## Recommended stack

| Concern | Choice | Coordinates / version | Rationale | Confidence |
|---|---|---|---|---|
| Language/runtime | Java 17 bytecode target on JDK 25 | Homebrew `openjdk@25` 25.0.2 | JDK 25 is what's installed; target 17 for FragPipe embeddability (FragPipe ships Java apps). Decide final target in P0. | High |
| Build | Maven | 3.9.15 (installed) | Already present; Gradle absent. Simpler single-module → multi-module later. | High |
| Parquet I/O | **parquet-java (parquet-mr)**, Hadoop-free | `org.apache.parquet:parquet-hadoop` + `parquet-column` + `parquet-common` **1.17.0** | Pure JVM, best-in-class LIST + projection + predicate pushdown, reads via `InputFile` (no Hadoop `Path` needed using `PlainParquetConfiguration` + `LocalInputFile`). Exclude `hadoop-common`/`hadoop-mapreduce` transitively. | High |
| JSON (manifest + footer metadata) | Jackson | `com.fasterxml.jackson.core:jackson-databind` 2.18.x | Parse `mzpeak_index.json` and Parquet footer key-value JSON. Ubiquitous, no native deps. | High |
| FragPipe interop | MSFTBX (optional/adapter module) | `com.github.chhh:msftbx` ~1.8.8, Apache-2.0 | The actual FragPipe/MSFragger I/O layer (`umich.ms.datatypes`). Keep as a separate adapter module so core has zero FragPipe dep. Verify exact latest tag in P0. | Med (version) |
| Tests | JUnit 5 + AssertJ | `org.junit.jupiter` 5.11.x, `org.assertj` 3.26.x | Standard. Drive from HUPO-PSI example data. | High |

## Explicitly NOT using (and why)

- **DuckDB JDBC** — native JNI (~tens of MB, per-platform), and can't read Parquet sliced from inside the STORED ZIP. Rejected to keep the artifact pure-JVM and FragPipe-embeddable. (Was a viable fast-prototype alternative; user chose pure JVM.)
- **Apache Arrow Java (arrow-dataset)** — off-heap memory management + JNI native libs; overkill for materializing plain `double[]`.
- **parquet-floor** — nested LIST support undocumented/unverified; dealbreaker for m/z+intensity arrays.
- **Hadoop** (`hadoop-common` et al.) — heavy transitive bloat; avoided via the Hadoop-free parquet-java path.
- **Carpet** — ergonomic record wrapper over parquet-mr, but limited predicate pushdown; reconsider only if record-mapping ergonomics dominate.

## Key integration risk to validate early (P0/P2)

parquet-java's Hadoop-free path requires `PlainParquetConfiguration` + a custom/`Local` `InputFile`. The
"packed parallel tables" schema (top-level struct columns) and `large_list`/`large_string` logical types must
decode correctly. **Spike this against `small.unpacked.mzpeak/` in P0/P2 before committing the reader design.**
