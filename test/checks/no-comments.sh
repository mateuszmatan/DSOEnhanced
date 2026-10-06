#!/usr/bin/env bash
cd "$(dirname "$0")/../.." || exit 1
python3 - <<'PY' || exit 1
import glob, sys

def code_only(text):
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith('"""', i) or text.startswith("'''", i):
            quote = text[i:i + 3]
            end = text.find(quote, i + 3)
            end = n if end < 0 else end + 3
            out.append('\n' * text.count('\n', i, end))
            i = end
            continue
        ch = text[i]
        if ch in '"\'':
            j = i + 1
            while j < n and text[j] != ch and text[j] != '\n':
                j += 2 if text[j] == '\\' else 1
            i = j + 1
            out.append('""')
            continue
        out.append(ch)
        i += 1
    return ''.join(out)

problems = []
for f in sorted(glob.glob('src/**/*.groovy', recursive=True)) + sorted(glob.glob('vars/*.groovy')) + sorted(glob.glob('resources/**/*.java', recursive=True)):
    for number, line in enumerate(code_only(open(f).read()).split('\n'), 1):
        stripped = line.strip()
        if stripped.startswith('//') or stripped.startswith('/*') or stripped.startswith('*/') or ' // ' in line:
            problems.append("%s:%d %s" % (f, number, stripped[:100]))

if problems:
    print("NO-COMMENTS CHECK FAILED - comments or commented-out code found:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print("NO-COMMENTS CHECK PASSED - no comments and no commented-out code in src, vars and resources/**/*.java")
PY
