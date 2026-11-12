#!/usr/bin/env bash
#
# Repository policy check (design D-2): resolves every plugin and dependency of the reactor into a fresh local
# repository and fails unless
#   1. every artifact was downloaded from `central` or `ebi-repo` (a repository a dependency POM declares and that
#      serves anything fails here, so a missing .mvn/rrf/groupId-<id>.txt cannot go unnoticed);
#   2. `ebi-repo` was asked only for uk/ac/ebi/jmzml/, uk/ac/ebi/pride/architectural/ and psidev/psi/tools/;
#   3. the resolver loaded the group filter of ebi-repo and both trusted-checksum summary files (7 entries each:
#      every artifact ebi-repo serves), from <root>/.mvn/rrf and <root>/.mvn/checksums.
#
# Usage: tools/check-repo-policy.sh            (with the Maven Wrapper)
#        MVN=mvn tools/check-repo-policy.sh    (with another Maven, e.g. the local one)
# The Maven log is kept; its path is printed at the end. Needs network access (a fresh local repository).
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
MVN="${MVN:-$ROOT/mvnw}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/mzpeakj-repo-policy.XXXXXX")"
LOG="$WORK/maven.log"
EBI_URL='https://www.ebi.ac.uk/Tools/maven/repos/content/groups/ebi-repo/'

cd "$ROOT"
echo "repository policy: $MVN, fresh local repository $WORK/repo"
# Resolution only: without clean, jacoco:check would judge a stale target/jacoco.exec of an earlier build.
if ! "$MVN" -B -X -Dmaven.repo.local="$WORK/repo" verify -DskipTests -Djacoco.skip=true >"$LOG" 2>&1; then
  tail -n 40 "$LOG"
  echo "FAIL: the build failed; log: $LOG" >&2
  exit 1
fi

failures=0
fail() { echo "FAIL: $*" >&2; failures=$((failures + 1)); }

# 1. Downloads only from central and ebi-repo.
foreign="$(grep -E 'Downloaded from [^:]+:' "$LOG" | grep -vE 'Downloaded from (central|ebi-repo):' || true)"
[ -z "$foreign" ] || fail "downloads from a repository other than central and ebi-repo:"$'\n'"$foreign"
grep -qE 'Downloaded from central:' "$LOG" || fail "nothing downloaded from central: the local repository was not fresh"

# 2. ebi-repo asked and served only for jmzML's groups.
ebi="$(grep -E '(Downloading|Downloaded) from ebi-repo: ' "$LOG" \
  | grep -vF -e "from ebi-repo: ${EBI_URL}uk/ac/ebi/jmzml/" \
             -e "from ebi-repo: ${EBI_URL}uk/ac/ebi/pride/architectural/" \
             -e "from ebi-repo: ${EBI_URL}psidev/psi/tools/" || true)"
[ -z "$ebi" ] || fail "ebi-repo asked for a group outside uk.ac.ebi.jmzml, uk.ac.ebi.pride.architectural, psidev.psi.tools:"$'\n'"$ebi"
grep -qF "Downloaded from ebi-repo: ${EBI_URL}uk/ac/ebi/jmzml/jmzml/" "$LOG" || fail "jmzML was not downloaded from ebi-repo"

# 3. Filter and summary files loaded from the project's .mvn. The resolver logs the filter rules it loaded per
#    repository (resolver 1.9: "Loaded 3 groupId for remote repository ebi-repo"; 2.0: "... 3 group rules ...") and
#    the summary files with their paths; the basedir of the filter shows in the -X dump of the session properties.
grep -qE '\[INFO\] Loaded 3 group(Id| rules) for remote repository ebi-repo$' "$LOG" \
  || fail "the group filter of ebi-repo (.mvn/rrf/groupId-ebi-repo.txt, 3 groups) was not loaded"
grep -qxF "[DEBUG] aether.remoteRepositoryFilter.groupId.basedir: $ROOT/.mvn/rrf" "$LOG" \
  || fail "the group filter basedir is not $ROOT/.mvn/rrf"
for alg in sha512 sha1; do
  grep -qxF "[INFO] Loaded 7 trusted checksums from $ROOT/.mvn/checksums/checksums.$alg" "$LOG" \
    || fail "the trusted checksums were not loaded from $ROOT/.mvn/checksums/checksums.$alg"
done

if [ "$failures" -ne 0 ]; then
  echo "repository policy: $failures check(s) failed; log: $LOG" >&2
  exit 1
fi
echo "repository policy: OK ($(grep -cE 'Downloaded from central:' "$LOG") downloads from central," \
  "$(grep -cE 'Downloaded from ebi-repo:' "$LOG") from ebi-repo); log: $LOG"
