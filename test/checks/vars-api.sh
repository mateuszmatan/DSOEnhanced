#!/usr/bin/env bash
# Checks that every method the pipelines call on the shared vars exists, and that
# each entry point declares the stages it must run. Groovy resolves these calls
# dynamically, so neither groovyc nor the sandbox lint sees a typo here.
cd "$(dirname "$0")/../.." || exit 1
python3 - <<'PY' || exit 1
import re, glob, sys, os

def declared(path):
    src = open(path).read()
    return set(re.findall(r'^\s*(?:private\s+)?(?:def|void|Map|String|List|boolean|int)\s+(\w+)\s*\(', src, re.M))

api = declared('vars/devSecOpsApi.groovy')
steps = declared('vars/devSecOpsSteps.groovy')
problems = []
for f in sorted(glob.glob('vars/*.groovy')):
    src = open(f).read()
    for m in re.finditer(r'\bdevSecOpsApi\.(\w+)\s*\(', src):
        if m.group(1) not in api:
            problems.append("%s:%d calls devSecOpsApi.%s() which is not declared" % (f, src[:m.start()].count('\n') + 1, m.group(1)))
    for m in re.finditer(r'\bdevSecOpsSteps\.(\w+)\s*\(', src):
        if m.group(1) not in steps:
            problems.append("%s:%d calls devSecOpsSteps.%s() which is not declared" % (f, src[:m.start()].count('\n') + 1, m.group(1)))

monitor = 'Monitor source changes (download sources)'
security = [monitor, 'Unit tests', 'Dependencies scan (Nexus IQ)',
            'SAST - Static Application Security Tests - HCL AppScan', 'SCA (SonarQube)',
            'Nexus delivery (Static analysis passed)']
extended = [monitor, 'Lower test region deployment', 'Regression tests (>60% user stories coverage)',
            'Smoke tests', 'Performance tests', 'DAST - Dynamic Application Security Tests - HCL AppScan',
            'Nexus delivery - Safe Artifact - 0 known Security Vulnerabilities', 'Higher test environment deployment']
expected = {
    'vars/devSecOpsPipeline.groovy': security + extended[1:],
    'vars/devSecOpsSecurityPipeline.groovy': security,
    'vars/devSecOpsExtendedPipeline.groovy': extended,
    'vars/devSecOpsSASTScanningPipeline.groovy': [monitor, 'SAST - Static Application Security Tests - HCL AppScan'],
}
for f, want in expected.items():
    got = re.findall(r"^\s+stage\('(.+?)'\)", open(f).read(), re.M)
    if got != want:
        problems.append("%s declares stages %s but should declare %s" % (f, got, want))

wiring = open('vars/devSecOpsApi.groovy').read()
for f in sorted(glob.glob('src/com/bbh/remediation/updater/*Updater.groovy')):
    name = os.path.basename(f)[:-7]
    if ('new %s()' % name) not in wiring:
        problems.append("vars/devSecOpsApi.groovy does not wire %s into the GoldenFix service" % name)

if problems:
    print("VARS API CHECK FAILED:")
    for p in problems:
        print("  " + p)
    sys.exit(1)
print("VARS API CHECK PASSED - shared methods resolve and the full pipeline equals security plus extended")
PY
