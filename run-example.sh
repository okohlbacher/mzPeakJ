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

# Locate a JDK: prefer $JAVA_HOME, else `java` on PATH.
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="java"
else
  echo "error: no JDK found. Set JAVA_HOME (JDK 17+) or put 'java' on your PATH." >&2
  exit 1
fi

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <fully.qualified.MainClass> [args...]" >&2
  echo "  e.g. $0 org.mzpeak.examples.MzPeakFileInfo src/test/resources/mzpeak/small.mzpeak -s" >&2
  exit 2
fi

# Compile classes if needed, and cache the dependency classpath.
if [[ ! -e "$DIR/target/classes/org/mzpeak/io/MzPeakReader.class" ]]; then
  echo "Building mzPeakJ..." >&2
  mvn -q -f "$DIR/pom.xml" -DskipTests package
fi
CP_FILE="$DIR/target/classpath.txt"
if [[ ! -s "$CP_FILE" ]]; then
  mvn -q -f "$DIR/pom.xml" dependency:build-classpath -Dmdep.outputFile="$CP_FILE" >/dev/null
fi

exec "$JAVA" --enable-native-access=ALL-UNNAMED \
  -cp "$DIR/target/classes:$(cat "$CP_FILE")" "$@"
