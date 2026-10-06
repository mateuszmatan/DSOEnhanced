#!/usr/bin/env bash
cd "$(dirname "$0")/../.." || exit 1
found="$(LC_ALL=C grep -rnI '[^ -~	]' src vars resources)"
if [ -n "$found" ]; then
  echo "ASCII CHECK FAILED - characters outside printable ASCII, tab and newline:"
  echo "$found" | cut -c1-160 | sed 's/^/  /'
  exit 1
fi
echo "ASCII CHECK PASSED - src, vars and resources hold printable ASCII, tabs and newlines only"
