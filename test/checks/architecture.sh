#!/usr/bin/env bash
cd "$(dirname "$0")/../.." || exit 1
python3 - <<'PY' || exit 1
import re, glob, sys, os

ALLOWED = {
    'utils': set(),
    'core': {'utils'},
    'config': {'core', 'utils'},
    'build': {'core', 'utils'},
    'deploy': {'core', 'build', 'utils'},
    'metrics': {'core'},
    'report': {'core', 'utils'},
    'scanner': {'core', 'build', 'utils', 'remediation.port', 'remediation.model'},
    'remediation': {'core', 'utils', 'remediation.port', 'remediation.model'},
    'remediation.port': set(),
    'remediation.model': set(),
    'remediation.updater': {'remediation.port', 'remediation.model'},
    'scm': {'remediation.port', 'remediation.model', 'utils'},
}

def package_of(path):
    rel = os.path.dirname(path).replace('src/com/bbh/', '').replace('/', '.')
    return rel

def target_of(imported):
    parts = imported.split('.')[:-1]
    for n in range(len(parts), 0, -1):
        key = '.'.join(parts[:n])
        if key in ALLOWED:
            return key
    return '.'.join(parts)

problems = []
for f in sorted(glob.glob('src/com/bbh/**/*.groovy', recursive=True)):
    pkg = package_of(f)
    if pkg not in ALLOWED:
        problems.append("%s: package %s has no dependency rule" % (f, pkg))
        continue
    allowed = ALLOWED[pkg]
    if allowed is None:
        continue
    for m in re.finditer(r'^import\s+com\.bbh\.([\w.]+)', open(f).read(), re.M):
        target = target_of(m.group(1))
        if target != pkg and target not in allowed:
            problems.append("%s: %s must not depend on %s (import com.bbh.%s)" % (f, pkg, target, m.group(1)))

if problems:
    print("ARCHITECTURE CHECK FAILED - dependency rules of the ports and adapters layout are broken:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print("ARCHITECTURE CHECK PASSED - adapters depend on the core and the ports, never the other way round")
PY
