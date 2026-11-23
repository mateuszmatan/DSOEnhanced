"""Turns the log of test/run-all.sh into a JUnit XML file for the CI build.

One test suite per '=== title ===' section of the log. One test case per 'PASS  <name>' and
'FAIL  <name>' line, the indented lines after a FAIL being its failure text, and one per
'<NAME> CHECK|LINT|SCAN PASSED|FAILED|SKIPPED|REGENERATED' line of the static checks.
REGENERATED counts as a failure, because the committed file did not match its source, and
SKIPPED as skipped. A last test case test/run-all.sh fails unless the log ends with
ALL CHECKS PASSED.

Usage: python3 -I ci/junit.py <run-all.log> <junit.xml>
"""

import re
import sys
from xml.sax.saxutils import escape, quoteattr

CHECK = re.compile(r'^([A-Z][A-Z0-9 ./-]*?) (CHECK|LINT|SCAN) (PASSED|FAILED|SKIPPED|REGENERATED)(?: - (.*))?$')
CASE = re.compile(r'^(PASS|FAIL)  (.*)$')
SECTION = re.compile(r'^=== (.*) ===$')
NOT_XML = re.compile('[\x00-\x08\x0b\x0c\x0e-\x1f]')


def parse(lines):
    suites = []
    current = None
    last = None
    for raw in lines:
        line = NOT_XML.sub('', raw.rstrip('\n'))
        section = SECTION.match(line)
        if section:
            current = {'name': section.group(1), 'cases': []}
            suites.append(current)
            last = None
            continue
        if current is None:
            current = {'name': 'setup', 'cases': []}
            suites.append(current)
        case = CASE.match(line)
        if case:
            last = {'name': case.group(2), 'status': 'passed' if case.group(1) == 'PASS' else 'failed', 'text': []}
            current['cases'].append(last)
            continue
        check = CHECK.match(line)
        if check:
            outcome = check.group(3)
            status = {'PASSED': 'passed', 'FAILED': 'failed', 'SKIPPED': 'skipped', 'REGENERATED': 'failed'}[outcome]
            last = {'name': '%s %s' % (check.group(1), check.group(2)), 'status': status,
                    'text': ['%s %s %s - %s' % (check.group(1), check.group(2), outcome, check.group(4) or '')]}
            current['cases'].append(last)
            continue
        if last is not None and last['status'] != 'passed' and line.startswith('  '):
            last['text'].append(line.strip())
            continue
        if line.strip():
            last = None
    ended = [line.strip() for line in lines if line.strip()]
    passed = bool(ended) and ended[-1] == 'ALL CHECKS PASSED'
    suites.append({'name': 'summary', 'cases': [{'name': 'test/run-all.sh', 'status': 'passed' if passed else 'failed',
                                                  'text': [] if passed else ['the log does not end with ALL CHECKS PASSED']}]})
    return suites


def render(suites):
    out = ['<?xml version="1.0" encoding="UTF-8"?>']
    total = sum(len(s['cases']) for s in suites)
    failures = sum(1 for s in suites for c in s['cases'] if c['status'] == 'failed')
    skipped = sum(1 for s in suites for c in s['cases'] if c['status'] == 'skipped')
    out.append('<testsuites name="test/run-all.sh" tests="%d" failures="%d" skipped="%d">' % (total, failures, skipped))
    for suite in suites:
        cases = suite['cases']
        out.append('  <testsuite name=%s tests="%d" failures="%d" skipped="%d">' % (
            quoteattr(suite['name']), len(cases), sum(1 for c in cases if c['status'] == 'failed'),
            sum(1 for c in cases if c['status'] == 'skipped')))
        for case in cases:
            head = '    <testcase classname=%s name=%s' % (quoteattr(suite['name']), quoteattr(case['name']))
            if case['status'] == 'passed':
                out.append(head + '/>')
            elif case['status'] == 'skipped':
                out.append(head + '><skipped message=%s/></testcase>' % quoteattr(' '.join(case['text'])))
            else:
                text = '\n'.join(case['text'])
                out.append(head + '><failure message=%s>%s</failure></testcase>' % (
                    quoteattr(case['text'][0] if case['text'] else case['name']), escape(text)))
        out.append('  </testsuite>')
    out.append('</testsuites>')
    return '\n'.join(out) + '\n'


def main(argv):
    if len(argv) != 3:
        sys.stderr.write('usage: python3 -I ci/junit.py <run-all.log> <junit.xml>\n')
        return 2
    with open(argv[1], encoding='utf-8', errors='replace') as handle:
        lines = handle.readlines()
    suites = parse(lines)
    with open(argv[2], 'w', encoding='utf-8') as handle:
        handle.write(render(suites))
    failed = sum(1 for s in suites for c in s['cases'] if c['status'] == 'failed')
    print('[CI] %s: %d test cases, %d failed' % (argv[2], sum(len(s['cases']) for s in suites), failed))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
