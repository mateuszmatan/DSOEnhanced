#!/usr/bin/env bash
# Keeps documentation.md, the Markdown copy that Bitbucket and GitHub display, a faithful
# rendering of documentation.confluence, the single source of the documentation.
cd "$(dirname "$0")/../.." || exit 1
if ! command -v python3 > /dev/null 2>&1; then
  echo "DOCS MD CHECK SKIPPED - python3 is required to render documentation.md"
  exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
python3 tools/build-documentation-html.py --md "$TMP/documentation.md" > /dev/null || exit 1
if diff -q "$TMP/documentation.md" documentation.md > /dev/null 2>&1; then
  echo "DOCS MD CHECK PASSED - documentation.md matches documentation.confluence"
else
  cp "$TMP/documentation.md" documentation.md
  echo "DOCS MD CHECK REGENERATED - documentation.md rebuilt from documentation.confluence"
fi
