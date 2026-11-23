#!/usr/bin/env bash
# Compares the files this repository shares with the other library branch and with ITSS COE
# OneView, checked out by the CI build, and writes build/ci/junit/shared-files.xml.
# Usage: bash ci/shared-files.sh <folder> [<folder>...]
cd "$(dirname "$0")/.." || exit 1
mkdir -p build/ci/junit
[ -f build/ci/env.sh ] && . build/ci/env.sh
bash tools/check-shared-files.sh --junit build/ci/junit/shared-files.xml "$@"
