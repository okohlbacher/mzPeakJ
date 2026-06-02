#!/usr/bin/env bash
#
# Build mzPeakJ (once) and run one of its CLI / example main classes against the bundled fixtures.
#
# Usage:
#   ./run-example.sh <fully.qualified.MainClass> [args...]
#
# Examples:
#   ./run-example.sh org.mzpeak.examples.MzPeakFileInfo src/test/resources/mzpeak/small.mzpeak -s
#   ./run-example.sh org.mzpeak.cli.MzPeakInfo src/test/resources/mzpeak/small.unpacked.mzpeak
#
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

# Locate a *working* JDK 17+. On macOS, /usr/bin/java is a stub that exists even with no JDK installed, so we
# verify each candidate actually runs `java -version` before accepting it.
works() { [[ -x "$1" ]] && "$1" -version >/dev/null 2>&1; }

JAVA=""
candidates=()
[[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin/java")
if [[ -x /usr/libexec/java_home ]]; then
  jh="$(/usr/libexec/java_home 2>/dev/null || true)"
  [[ -n "$jh" ]] && candidates+=("$jh/bin/java")
fi
candidates+=(
  /opt/homebrew/opt/openjdk@25/bin/java
  /opt/homebrew/opt/openjdk/bin/java
  /usr/local/opt/openjdk/bin/java
  "$(command -v java 2>/dev/null || true)"
)
for c in "${candidates[@]}"; do
  if [[ -n "$c" ]] && works "$c"; then JAVA="$c"; break; fi
done
if [[ -z "$JAVA" ]]; then
  echo "error: no working JDK 17+ found. Set JAVA_HOME, e.g.:" >&2
  echo "  export JAVA_HOME=/opt/homebrew/opt/openjdk@25" >&2
  exit 1
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <fully.qualified.MainClass> [args...]" >&2
  echo "  e.g. $0 org.mzpeak.examples.MzPeakFileInfo src/test/resources/mzpeak/small.mzpeak -s" >&2
  exit 2
fi

# Build the self-contained shaded jar if it's missing, then run from it.
SHADED="$(ls "$DIR"/target/mzpeakj-*-all.jar 2>/dev/null | head -1 || true)"
if [[ -z "$SHADED" ]]; then
  echo "Building mzPeakJ (one-time)..." >&2
  mvn -q -f "$DIR/pom.xml" -DskipTests package
  SHADED="$(ls "$DIR"/target/mzpeakj-*-all.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$SHADED" ]]; then
  echo "error: shaded jar not found after build (expected target/mzpeakj-*-all.jar)." >&2
  exit 1
fi

exec "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$SHADED" "$@"
