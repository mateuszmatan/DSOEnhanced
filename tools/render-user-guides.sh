#!/usr/bin/env bash
# Renders the two user guides of docs/ from their Markdown source to HTML.
# The Markdown files are the single source; the same two files and their pages are kept
# identical in both branches of the library and in ITSS COE OneView.
# Needs python3 with the "markdown" module (pip install markdown).
# Usage: tools/render-user-guides.sh [output folder]   (default: docs)
cd "$(dirname "$0")/.." || exit 1
OUT="${1:-docs}"
python3 tools/render_doc.py docs/USER_GUIDE_PORTAL.md "$OUT/USER_GUIDE_PORTAL.html" \
  "ITSS COE OneView & DSOEnhanced" "User Guide (Portal-Integrated Version)" || exit 1
python3 tools/render_doc.py docs/USER_GUIDE_WITHOUT_PORTAL.md "$OUT/USER_GUIDE_WITHOUT_PORTAL.html" \
  "DevSecOps Shared Library (Standalone Version)" "User Guide (Without Portal Version)" || exit 1
