#!/usr/bin/env bash
# Fails when the working tree holds changes other than the example reports and the
# <name>.actual.txt files of a failing scenario, and writes them to build/ci/changed-files.diff.
# Usage: bash ci/generated-files.sh [repository folder]   (default: this repository)
cd "${1:-$(dirname "$0")/..}" || exit 1
mkdir -p build/ci
changed="$(git status --porcelain --untracked-files=all | awk '
  substr($0, 1, 3) == " M " && substr($0, 4) ~ /^examples\/reports\// { next }
  substr($0, 1, 3) == "?? " && substr($0, 4) ~ /\.actual\.txt$/ { next }
  { print }')"
if [ -n "$changed" ]; then
  echo "GENERATED FILES CHECK FAILED - test/run-all.sh changed committed files; run it on the branch and commit them:"
  echo "$changed" | sed 's/^/  /'
  git diff > build/ci/changed-files.diff
  exit 1
fi
echo "GENERATED FILES CHECK PASSED - only the example reports and the files of failing scenarios differ after the run"
