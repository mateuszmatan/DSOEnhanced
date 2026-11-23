#!/usr/bin/env bash
# Keeps docs/USER_GUIDE_PORTAL.html and docs/USER_GUIDE_WITHOUT_PORTAL.html the rendering of
# their Markdown files, which are the single source of the user guides. The pages are rendered
# with the markdown version of tools/requirements-docs.txt; another version skips the check.
cd "$(dirname "$0")/../.." || exit 1
if ! python3 -c "import markdown" > /dev/null 2>&1; then
  echo "USER GUIDES CHECK SKIPPED - python3 with the markdown module is required to render the user guides"
  exit 0
fi
wanted="$(sed -n 's/^markdown==//p' tools/requirements-docs.txt 2> /dev/null)"
installed="$(python3 -c 'import markdown; print(markdown.__version__)' 2> /dev/null)"
if [ -n "$wanted" ] && [ "$installed" != "$wanted" ]; then
  echo "USER GUIDES CHECK SKIPPED - the pages are rendered with markdown $wanted of tools/requirements-docs.txt, python3 has markdown $installed (pip install -r tools/requirements-docs.txt)"
  exit 0
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
bash tools/render-user-guides.sh "$TMP" > /dev/null || exit 1
changed=""
for guide in USER_GUIDE_PORTAL USER_GUIDE_WITHOUT_PORTAL; do
  if ! diff -q "$TMP/$guide.html" "docs/$guide.html" > /dev/null 2>&1; then
    cp "$TMP/$guide.html" "docs/$guide.html"
    changed="$changed $guide.html"
  fi
done
if [ -z "$changed" ]; then
  echo "USER GUIDES CHECK PASSED - the pages of docs match their Markdown files"
else
  echo "USER GUIDES CHECK REGENERATED -$changed rebuilt from Markdown"
fi
