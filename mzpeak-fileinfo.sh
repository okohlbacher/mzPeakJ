#!/usr/bin/env bash
#
# Print a summary of an mzPeak file (an mzPeak-only port of the OpenMS FileInfo tool).
#
# Usage:
#   ./mzpeak-fileinfo.sh <file.mzpeak | dir.mzpeak/> [-s]
#
#   -s   also print intensity statistics (min / quartiles / median / max / mean / count)
#
# Examples:
#   ./mzpeak-fileinfo.sh src/test/resources/mzpeak/small.mzpeak
#   ./mzpeak-fileinfo.sh src/test/resources/mzpeak/has_uv.mzpeak -s
#
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

if [[ $# -lt 1 ]]; then
  echo "Usage: $(basename "$0") <file.mzpeak | dir.mzpeak/> [-s]" >&2
  echo "  -s   add intensity statistics (five-number summary)" >&2
  exit 2
fi

# Delegate to the shared runner (handles building + JDK/classpath detection).
exec "$DIR/run-example.sh" org.mzpeak.examples.MzPeakFileInfo "$@"
