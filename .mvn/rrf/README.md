# Remote repository filter (design D-2)

`.mvn/maven.config` turns on the Maven resolver's groupId filter with this directory as its basedir. A file
`groupId-<repository id>.txt` lists the only group ids (one per line, exact) the repository with that id may serve; a
repository without a file is not filtered. `tools/check-repo-policy.sh` checks on a fresh local repository that only
`central` and `ebi-repo` download anything; the root POM's enforcer execution `repository-policy` fails the build
when the filter is not active.

- `groupId-ebi-repo.txt`: the EBI repository declared in `mzpeakj-mzml/pom.xml` serves only `uk.ac.ebi.jmzml`,
  `uk.ac.ebi.pride.architectural` and `psidev.psi.tools` (jmzML, its parent POMs and xxindex).
- Every other `groupId-<id>.txt` holds the single line `org.mzpeak.repository-denied`, a group that does not exist,
  so that repository serves nothing. `central` has no file.

Denied repositories: the ids the resolver reported in
`./mvnw -B -X -Dmaven.repo.local=<fresh dir> verify -DskipTests` (Maven 3.9.16 and 3.10.0, macOS aarch64,
2026-11-11), with the POMs that declare them:

| id | declared in |
|---|---|
| `JBoss` | `uk.ac.ebi.jmzml:jmzml:1.7.11` |
| `springsource-repo` (http, mirrored to `maven-default-http-blocker`) | `uk.ac.ebi.jmzml:jmzml:1.7.11` |
| `nexus-ebi-release-repo`, `nexus-ebi-snapshot-repo` (http, mirrored) | `uk.ac.ebi.pride.architectural:pride-core:1.0.1` |
| `sammoa-group` (http, mirrored) | `psidev.psi.tools:xxindex:0.23` |
| `apache.snapshots` | `org.apache:apache` parent POMs, `org.apache.maven:maven-parent`, `maven-shared-components`, `commons-parent` |
| `plexus.snapshots` | `org.apache.maven.plugins:maven-compiler-plugin:3.13.0` |
| `sonatype-nexus-snapshots` | `com.fasterxml.jackson:jackson-bom:2.23.0` |
| `central-snapshots` | Jackson 2.23 POMs (`jackson-bom`, `jackson-parent`, `jackson-core`, `jackson-databind`, `jackson-annotations`) |
| `ow2-snapshot` | `org.ow2:ow2:1.5.1` (parent of ASM) |
| `maven-default-http-blocker` | Maven's own mirror of every http repository |

Denied as well, though not reported in those runs:

| id | declared in | why not reported |
|---|---|---|
| `jitpack.io` | `org.apache.parquet:parquet-hadoop:1.18.2`, profile `non-aarch64` | the profile is active on x86_64 (CI) only |
| `repository.jboss.org`, `apache.snapshots.https` | `org.apache.hadoop:hadoop-main:3.5.0` | no dependency resolved through that POM's repositories |
| `pst-release` | `uk.ac.ebi.pride.architectural:pride-core:1.0.1` | `distributionManagement` only (never resolved from) |

A new dependency that brings another repository: add `groupId-<id>.txt` with `org.mzpeak.repository-denied`, list it
here, and rerun `tools/check-repo-policy.sh`.
