#!/usr/bin/env bash
# Fails when the library uses constructs that the Jenkins script sandbox rejects
# unless an administrator approves the signature under In-process Script Approval.
cd "$(dirname "$0")/../.." || exit 1
FORBIDDEN='new (LinkedHashMap|LinkedHashSet|HashSet|ArrayList|TreeMap|BigInteger|BigDecimal|IllegalArgumentException|JsonSlurper|JsonSlurperClassic|JsonOutput|File|URL)\b'
FORBIDDEN="$FORBIDDEN|JsonOutput\.|JsonSlurper"
FORBIDDEN="$FORBIDDEN|(^|[^A-Za-z0-9_.])Pattern\.(compile|quote|matches)"
FORBIDDEN="$FORBIDDEN|java\.util\.regex\.(Pattern|Matcher)"
FORBIDDEN="$FORBIDDEN|(^|[^A-Za-z0-9_.])(URLEncoder|URLDecoder)\."
HITS=$(grep -rnE "$FORBIDDEN" src vars --include='*.groovy' || true)
if [ -n "$HITS" ]; then
  echo "SANDBOX LINT FAILED - these constructs need an administrator approval in Jenkins:"
  echo "$HITS"
  exit 1
fi

HITS=$(grep -rnE '\."\$\{' src vars --include='*.groovy' || true)
if [ -n "$HITS" ]; then
  echo "SANDBOX LINT FAILED - under the sandbox map.\"\${key}\" looks the GString up and returns null, use map[key]:"
  echo "$HITS"
  exit 1
fi

python3 - <<'PY' || exit 1
# A static field of a library class is read through a synthetic getter, which the
# sandbox rejects when the class initializer runs while the CPS engine serializes
# the program. The library therefore declares no static fields at all.
import re, glob, sys

files = sorted(glob.glob('src/**/*.groovy', recursive=True)) + sorted(glob.glob('vars/*.groovy'))
classes = set()
for f in files:
    for m in re.finditer(r'^\s*(?:@\w+\s+)?(?:abstract\s+)?(?:class|interface|trait|enum)\s+(\w+)', open(f).read(), re.M):
        classes.add(m.group(1))

problems = []
for f in files:
    for i, line in enumerate(open(f).read().split('\n'), 1):
        for m in re.finditer(r'\b([A-Z]\w*)\.([A-Z][A-Z_0-9]{2,})\b', line):
            if m.group(1) in classes:
                problems.append("%s:%d reads the static constant %s.%s" % (f, i, m.group(1), m.group(2)))
        if re.match(r'\s*(?:private |protected |public )?static (?:final )?(?!(?:def|void|boolean|int|long|String|Map|List|Object|[A-Z]\w*(?:<[^>]*>)?)\s+\w+\s*\()', line) \
                and re.match(r'\s*(?:private |protected |public )?static (?:final )?[\w<>\[\], ]+\s+\w+\s*(=|$)', line):
            problems.append("%s:%d declares a static field, use an instance field or a method returning a literal" % (f, i))

if problems:
    print("SANDBOX LINT FAILED - static state of library classes is not allowed:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
PY

echo "SANDBOX LINT PASSED - no construct requiring script approval, no static fields"
