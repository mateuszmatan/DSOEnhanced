#!/usr/bin/env bash
# Keeps documentation.html a faithful rendering of documentation.confluence, which is
# the single source of the documentation and is written in Confluence wiki markup.
cd "$(dirname "$0")/../.." || exit 1
if ! command -v python3 > /dev/null 2>&1; then
  echo "DOCS HTML CHECK SKIPPED - python3 is required to render documentation.html"
  exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
python3 tools/build-documentation-html.py "$TMP/documentation.html" > /dev/null || exit 1
if diff -q "$TMP/documentation.html" documentation.html > /dev/null 2>&1; then
  echo "DOCS HTML CHECK PASSED - documentation.html matches documentation.confluence"
else
  cp "$TMP/documentation.html" documentation.html
  echo "DOCS HTML CHECK REGENERATED - documentation.html rebuilt from documentation.confluence"
fi
