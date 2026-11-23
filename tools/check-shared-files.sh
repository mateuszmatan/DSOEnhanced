#!/usr/bin/env bash
# Checks that the files the three sources share are the same: DSOEnhanced on the branch
# DSOEnhancedWithoutPortal, DSOEnhanced on main and ITSS COE OneView. The same file is in the
# three sources. It compares this repository with each folder given, and never changes a file.
#   In the three sources: TODO.md, docs/MANAGEMENT_OVERVIEW.md, the four docs/USER_GUIDE_* files,
#   tools/render-user-guides.sh, tools/render_doc.py, tools/requirements-docs.txt and this file.
#   Between the two library branches: ci/ and the scripts of the tests that ci/ runs.
#   Between main and OneView: the three database configurations of the OneView tests.
# With python3 and the markdown version of tools/requirements-docs.txt it also renders the
# user guides of this repository again and compares them with their pages.
# Usage: tools/check-shared-files.sh [--junit FILE] <folder> [<folder>...]
cd "$(dirname "$0")/.." || exit 1
junit=""
if [ "${1:-}" = "--junit" ]; then
  junit="${2:-}"
  shift 2
fi
if [ "$#" -eq 0 ]; then
  echo "usage: tools/check-shared-files.sh [--junit FILE] <folder> [<folder>...]"
  exit 2
fi

kind_of() {
  if [ -f "$1/backend/build.gradle" ]; then echo oneview
  elif [ -f "$1/test/oneview/query-scenarios.sh" ]; then echo main
  elif [ -f "$1/test/sandbox/ConfigScenarios.groovy" ]; then echo wp
  else echo unknown
  fi
}

ALL="TODO.md docs/MANAGEMENT_OVERVIEW.md docs/USER_GUIDE_PORTAL.md docs/USER_GUIDE_PORTAL.html
docs/USER_GUIDE_WITHOUT_PORTAL.md docs/USER_GUIDE_WITHOUT_PORTAL.html tools/render-user-guides.sh
tools/render_doc.py tools/requirements-docs.txt tools/check-shared-files.sh"
LIBRARY="ci/Jenkinsfile ci/prepare.sh ci/suite.sh ci/generated-files.sh ci/shared-files.sh ci/junit.py
test/setup-tools.sh test/ci/ci-scenarios.sh"
DATABASE="database-product-config.yaml database-gradle-product-config.yaml database-maven-product-config.yaml"

here="$(kind_of .)"
cases=()
failed=0
record() {
  cases+=("$1|$2|$3")
  if [ "$2" = "failed" ]; then
    failed=$((failed + 1))
    echo "SHARED FILES CHECK FAILED - $3"
  fi
}
compare() {
  local mine="$1" folder="$2" theirs="$3" label="$4"
  if [ ! -f "$mine" ]; then
    record "$label" failed "$mine is missing in this repository"
  elif [ ! -f "$folder/$theirs" ]; then
    record "$label" failed "$folder/$theirs is missing"
  elif cmp -s "$mine" "$folder/$theirs"; then
    record "$label" passed ""
  else
    record "$label" failed "$mine differs from $folder/$theirs"
  fi
}

for folder in "$@"; do
  folder="${folder%/}"
  there="$(kind_of "$folder")"
  if [ "$there" = "unknown" ]; then
    record "$folder" failed "$folder is neither a branch of DSOEnhanced nor ITSS COE OneView"
    continue
  fi
  for file in $ALL; do
    compare "$file" "$folder" "$file" "$file ($there)"
  done
  if [ "$here" != "oneview" ] && [ "$there" != "oneview" ]; then
    for file in $LIBRARY; do
      compare "$file" "$folder" "$file" "$file ($there)"
    done
  fi
  if [ "$here" = "main" ] && [ "$there" = "oneview" ]; then
    for file in $DATABASE; do
      compare "test/fixtures/database/oneview/$file" "$folder" "backend/src/regressionTest/resources/regression/$file" "$file ($there)"
    done
  elif [ "$here" = "oneview" ] && [ "$there" = "main" ]; then
    for file in $DATABASE; do
      compare "backend/src/regressionTest/resources/regression/$file" "$folder" "test/fixtures/database/oneview/$file" "$file ($there)"
    done
  fi
done

wanted="$(sed -n 's/^markdown==//p' tools/requirements-docs.txt 2> /dev/null)"
installed="$(python3 -c 'import markdown; print(markdown.__version__)' 2> /dev/null)"
if [ -n "$wanted" ] && [ "$installed" = "$wanted" ]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  if bash tools/render-user-guides.sh "$TMP" > /dev/null 2>&1; then
    for guide in USER_GUIDE_PORTAL USER_GUIDE_WITHOUT_PORTAL; do
      if cmp -s "$TMP/$guide.html" "docs/$guide.html"; then
        record "docs/$guide.html rendered" passed ""
      else
        record "docs/$guide.html rendered" failed "docs/$guide.html is not the rendering of docs/$guide.md"
      fi
    done
  else
    record "user guides rendered" failed "tools/render-user-guides.sh failed"
  fi
else
  echo "[SHARED] The pages of the user guides are not rendered again: markdown ${installed:-is not installed}, tools/requirements-docs.txt pins ${wanted:-no version}"
fi

if [ -n "$junit" ]; then
  mkdir -p "$(dirname "$junit")"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo "<testsuite name=\"shared files\" tests=\"${#cases[@]}\" failures=\"$failed\">"
    for entry in "${cases[@]}"; do
      name="${entry%%|*}"
      rest="${entry#*|}"
      status="${rest%%|*}"
      text="${rest#*|}"
      name="$(printf '%s' "$name" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/"/\&quot;/g')"
      text="$(printf '%s' "$text" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/"/\&quot;/g')"
      if [ "$status" = "passed" ]; then
        echo "  <testcase classname=\"shared files\" name=\"$name\"/>"
      else
        echo "  <testcase classname=\"shared files\" name=\"$name\"><failure message=\"$text\"/></testcase>"
      fi
    done
    echo '</testsuite>'
  } > "$junit"
fi

if [ "$failed" -gt 0 ]; then
  echo "SHARED FILES CHECK FAILED - $failed of ${#cases[@]} comparisons differ"
  exit 1
fi
echo "SHARED FILES CHECK PASSED - ${#cases[@]} comparisons, every shared file is the same"
