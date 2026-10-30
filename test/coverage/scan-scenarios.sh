#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

python3 - "$ROOT/src/com/bbh/build/CoverageReports.groovy" "$ROOT/src/com/bbh/build/BuildService.groovy" "$WORK" <<'PY'
import ast, hashlib, os, re, subprocess, sys, time

source, build, work = sys.argv[1:4]
script = re.search(r"static String scanScript\(\) \{\s*return '''(.*?)'''", open(source).read(), re.S).group(1)
old_classes = re.search(r"private List readJaCoCoPerClass\(String xmlPath\) \{.*?<<'PYEOF'\n(.*?)PYEOF", open(build).read(), re.S).group(1)
scan = os.path.join(work, 'scan.py')
open(scan, 'w').write(script)
failures = []


def check(name, ok, detail=''):
    print(('PASS  ' if ok else 'FAIL  ') + name)
    if not ok:
        print('      ' + str(detail))
        failures.append(name)


def put(tree, rel, text):
    path = os.path.join(work, tree, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', newline='', encoding='utf-8') as f:
        f.write(text)


def jacoco(classes, groups=None):
    def package(items):
        return '<package name="com/bbh/x">' + ''.join(
            '<class name="com/bbh/x/%s" sourcefilename="%s.java"><counter type="LINE" missed="%d" covered="%d"/></class>'
            % (name, name, missed, covered) for name, covered, missed in items) + '</package>'
    every = [c for _, items in groups for c in items] if groups else classes
    body = ''.join('<group name="%s">%s</group>' % (g, package(items)) for g, items in groups) if groups else package(classes)
    covered = sum(c for _, c, _ in every)
    missed = sum(m for _, _, m in every)
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd"><report name="r">'
            + body + '<counter type="LINE" missed="%d" covered="%d"/></report>' % (missed, covered))


def raw(tree, *args, env=None, limit=120, program=scan):
    try:
        return subprocess.run([sys.executable, '-I', program] + list(args), cwd=os.path.join(work, tree),
                              capture_output=True, env=env, timeout=limit).stdout.decode('utf-8').splitlines()
    except subprocess.TimeoutExpired:
        return ['timed out after %d seconds' % limit]


def run(tree, *args, **options):
    out = raw(tree, *args, **options)
    return ['\t'.join(line.split('\t')[:7]) for line in out if line.startswith('R\t')], [line for line in out if line.startswith('C\t')], \
        [line for line in out if not line.startswith(('R\t', 'C\t'))]


def rows(*items):
    return ['\t'.join(['R'] + [str(x) for x in item] + [''] * (6 - len(item))) for item in items]


check('the script has no backslash, is ASCII and parses with the Python 3.6 grammar',
      '\\' not in script and all(ord(c) < 128 for c in script) and ast.parse(script, feature_version=(3, 6)) is not None)

put('fixture', 'build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Cert', 824, 176)]))
put('fixture', 'target/site/jacoco/jacoco.xml', jacoco([('Api', 840, 160)]))
gradle, _, head = run('fixture', '--root', '.', '--own', 'gradle', '--others', 'maven')
maven, _, _ = run('fixture', '--root', '.', '--own', 'maven', '--others', 'gradle')
check('two services in one folder each take the report of their own build tool',
      head == ['S\t0'] and gradle == rows(('jacoco', 'build/reports/jacoco/test/jacocoTestReport.xml', 824, 1000))
      and maven == rows(('jacoco', 'target/site/jacoco/jacoco.xml', 840, 1000)), head + gradle + maven)
put('fixture', 'gui/coverage/gui/lcov.info', 'TN:\nSF:src/app/a.ts\nDA:1,1\nDA:2,0\nend_of_record\n')
gradle, _, _ = run('fixture', '--root', '.', '--own', 'gradle', '--others', 'maven')
maven, _, _ = run('fixture', '--root', '.', '--own', 'maven', '--others', 'gradle')
node, _, _ = run('fixture', '--root', '.', '--own', 'maven', '--others', 'gradle', '--others', 'node')
check('in a shared folder a report no service of the folder builds counts for each and is marked as shared',
      gradle == rows(('jacoco', 'build/reports/jacoco/test/jacocoTestReport.xml', 824, 1000),
                     ('lcov', 'gui/coverage/gui/lcov.info', 1, 2, '', 'node'))
      and maven == rows(('lcov', 'gui/coverage/gui/lcov.info', 1, 2, '', 'node'), ('jacoco', 'target/site/jacoco/jacoco.xml', 840, 1000))
      and node == rows(('jacoco', 'target/site/jacoco/jacoco.xml', 840, 1000)), gradle + maven + node)

for rel in ['app', 'core', 'util']:
    put('modules', rel + '/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([(rel.title(), 9, 1)]))
put('modules', 'release/core/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Core', 9, 1)]))
put('modules', 'release/2.3/util/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Util', 5, 5)]))
put('modules', 'build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml',
    jacoco(None, groups=[('app', [('App', 9, 1)]), ('core', [('Core', 9, 1)]), ('util', [('Util', 9, 1)])]))
put('modules', 'web/coverage/web/lcov.info', 'TN:\nSF:src/app/a.ts\nDA:1,1\nDA:2,0\nend_of_record\n')
put('modules', 'release/web/coverage/web/lcov.info', 'TN:\nSF:src/app/a.ts\nDA:1,1\nDA:2,0\nend_of_record\n')
put('modules', 'web/node_modules/lib/coverage/lcov.info', 'TN:\nSF:x.js\nDA:1,1\nend_of_record\n')
put('modules', 'gui@tmp/coverage/lcov.info', 'TN:\nSF:x.js\nDA:1,1\nend_of_record\n')
put('modules', '.gradle/jacoco/jacoco.xml', jacoco([('Hidden', 1, 0)]))
put('modules', 'docs/jacoco/pom.xml', '<project/>')
put('modules', 'docs/manual.info', 'This is manual.info, produced by makeinfo\n')
put('modules', 'empty/build/reports/jacoco/test/jacocoTestReport.xml', '<?xml version="1.0"?><report name="e"></report>')
put('modules', 'broken/build/reports/jacoco/test/jacocoTestReport.xml', '<?xml version="1.0"?><report name="x"><package')
found, classes, _ = run('modules', '--root', '.', '--own', 'gradle')
check('a multi-module build gives one row per module, finds release folders and leaves out tool folders, copies and the aggregate',
      found == rows(('jacoco', 'app/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10),
                    ('jacoco', 'broken/build/reports/jacoco/test/jacocoTestReport.xml', 0, 0, 'unreadable'),
                    ('jacoco', 'build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml', 27, 30, 'aggregate:3'),
                    ('jacoco', 'core/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10),
                    ('jacoco', 'empty/build/reports/jacoco/test/jacocoTestReport.xml', 0, 0, 'empty'),
                    ('jacoco', 'release/2.3/util/build/reports/jacoco/test/jacocoTestReport.xml', 5, 10),
                    ('jacoco', 'release/core/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10,
                     'copy:core/build/reports/jacoco/test/jacocoTestReport.xml'),
                    ('lcov', 'release/web/coverage/web/lcov.info', 1, 2, 'copy:web/coverage/web/lcov.info'),
                    ('jacoco', 'util/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10),
                    ('lcov', 'web/coverage/web/lcov.info', 1, 2)), found)
fields = [line.split('\t') for line in raw('modules', '--root', '.', '--own', 'gradle') if line.startswith('R\t')]
digest = hashlib.sha1(open(os.path.join(work, 'modules', 'app/build/reports/jacoco/test/jacocoTestReport.xml'), 'rb').read()).hexdigest()
check('each row ends with the SHA-1 of its report, empty when the report could not be read',
      all(len(f) == 8 for f in fields) and ['app/build/reports/jacoco/test/jacocoTestReport.xml', digest] in [f[2::5] for f in fields]
      and ['broken/build/reports/jacoco/test/jacocoTestReport.xml', ''] in [f[2::5] for f in fields], fields)
check('the class listing holds the counted JaCoCo reports only',
      sorted(c.split(', Class: ')[1].split(',')[0] for c in classes) == ['com.bbh.x.App', 'com.bbh.x.Core', 'com.bbh.x.Util', 'com.bbh.x.Util'],
      classes)
found, _, _ = run('modules', '--root', '.', '--own', 'gradle', '--exclude', 'web', '--exclude', 'release')
check('the folders of other services are left out',
      not any('web/' in r or 'release/' in r for r in found) and len(found) == 6, found)
found, _, _ = run('modules', '--root', '.', '--own', 'gradle', '--exclude', 'release', '--exclude', 'release/web', '--and', '--root', 'release/web', '--own', 'node')
check('the folders of one service are searched each with its own build tool, also a folder inside the folder of another service',
      [r.split('\t')[2] for r in found] == ['app/build/reports/jacoco/test/jacocoTestReport.xml',
                                             'broken/build/reports/jacoco/test/jacocoTestReport.xml',
                                             'build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml',
                                             'core/build/reports/jacoco/test/jacocoTestReport.xml',
                                             'empty/build/reports/jacoco/test/jacocoTestReport.xml',
                                             'util/build/reports/jacoco/test/jacocoTestReport.xml',
                                             'web/coverage/web/lcov.info', 'release/web/coverage/web/lcov.info'], found)
found, _, _ = run('modules', '--root', 'web', '--own', 'node')
check('a search root holds the search to that folder', found == rows(('lcov', 'web/coverage/web/lcov.info', 1, 2)), found)
found, _, head = run('modules', '--root', 'missing', '--own', 'gradle')
check('a search root that does not exist finds nothing', found == [] and head == ['S\t0'], found + head)
found, _, head = run('modules', '--pattern', 'nothing/*.xml', '--then', '--root', 'missing', '--then', '--root', 'web', '--own', 'node',
                     '--then', '--root', '.', '--own', 'gradle')
none, _, last = run('modules', '--pattern', 'nothing/*.xml', '--then', '--root', 'missing')
check('the steps run in order until one finds a report, and the S line names it',
      head == ['S\t2'] and found == rows(('lcov', 'web/coverage/web/lcov.info', 1, 2)) and none == [] and last == ['S\t1'],
      head + found + last)

found, _, _ = run('modules', '--pattern', '**/core/build/reports/jacoco/test/*.xml', '--pattern', 'web/coverage/web/lcov.info',
                  '--pattern', 'nothing/*.xml', '--pattern', 'docs/manual.info')
check('coverage.reportPath patterns name exactly the reports that count, copies included, in the order given',
      found == rows(('jacoco', 'core/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10),
                    ('jacoco', 'release/core/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10),
                    ('lcov', 'web/coverage/web/lcov.info', 1, 2)), found)
found, _, _ = run('modules', '--pattern', 'web/node_modules/lib/coverage/lcov.info', '--pattern', '**/lib/coverage/lcov.info')
check('a pattern does not walk into tool folders, an exact path does',
      found == rows(('lcov', 'web/node_modules/lib/coverage/lcov.info', 1, 1)), found)

put('stale', 'core/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Core', 9, 1), ('X', 9, 1), ('Y', 9, 1)]))
put('stale', 'release/2.3/core/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Core', 5, 5), ('X', 5, 5)]))
put('stale', 'release/2.2/core/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Core', 2, 8)]))
put('stale', 'a/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Gen', 5, 5)]))
put('stale', 'b/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Gen', 5, 5), ('B', 10, 0)]))
found, _, _ = run('stale', '--root', '.', '--own', 'gradle')
check('an older release report with fewer classes, and a module whose classes contain those of another, all count',
      [r.split('\t')[5] for r in found] == [''] * 5 and len(found) == 5, found)

put('rest', 'app/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('App', 9, 1)]))
put('rest', 'core/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Core', 3, 1)]))
put('rest', 'build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml',
    jacoco([('App', 9, 1), ('Core', 3, 1), ('Legacy', 0, 20), ('Old', 0, 0)]))
found, classes, _ = run('rest', '--root', '.', '--own', 'gradle')
check('the classes of an aggregate that no other report holds count as one more row of that aggregate',
      found == rows(('jacoco', 'app/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10),
                    ('jacoco', 'build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml', 12, 34, 'aggregate:2'),
                    ('jacoco', 'build/reports/jacoco/testCodeCoverageReport/testCodeCoverageReport.xml', 0, 20, 'rest:2'),
                    ('jacoco', 'core/build/reports/jacoco/test/jacocoTestReport.xml', 3, 4))
      and 'C\tPackage: com.bbh.x, Class: com.bbh.x.Legacy, Coverage: 0.0, Missed: 20, Covered: 0' in classes and len(classes) == 4,
      found + classes)

put('android', 'app/build/reports/coverage/test/release/report.xml', jacoco([('Main', 6, 4)]))
put('android', 'app/build/reports/coverage/test/debug/report.xml', jacoco([('Main', 6, 4)]))
put('android', 'lib/build/reports/kover/report.xml', jacoco([('Lib', 1, 1)]))
put('android', 'app/build/test-results/TEST-x.xml', '<testsuite/>')
found, _, _ = run('android', '--root', '.', '--own', 'gradle')
check('Android and Kover reports in JaCoCo format are found; the debug and release reports of one variant with the same numbers count once',
      found == rows(('jacoco', 'app/build/reports/coverage/test/debug/report.xml', 6, 10),
                    ('jacoco', 'app/build/reports/coverage/test/release/report.xml', 6, 10,
                     'copy:app/build/reports/coverage/test/debug/report.xml'),
                    ('jacoco', 'lib/build/reports/kover/report.xml', 1, 2)), found)

put('flutter', 'coverage/lcov.info', 'SF:lib/main.dart\nDA:1,1\nDA:2,0\nLF:2\nLH:1\nend_of_record\nSF:lib/main.dart\nDA:2,3\nend_of_record\n')
put('flutter', 'plugins/camera/coverage/lcov.info', 'SF:lib/src/x.dart\nDA:1,1\nDA:2,1\nDA:3,0\nLF:3\nLH:2\nend_of_record\n')
put('flutter', 'modules/pay/coverage/lcov.info', 'SF:lib/src/x.dart\nDA:1,0\nDA:2,0\nLF:2\nLH:0\nend_of_record\n')
for name in ['plugins_lcov.info', 'modules_lcov.info', 'main_lcov.info']:
    put('flutter', name, 'SF:lib/main.dart\nDA:1,1\nend_of_record\n')
put('flutter', 'total_lcov.info', 'SF:lib/src/x.dart\nDA:1,1\nDA:2,1\nDA:3,0\nLF:3\nLH:2\nend_of_record\n'
    'SF:lib/src/x.dart\nDA:1,0\nDA:2,0\nLF:2\nLH:0\nend_of_record\nSF:lib/main.dart\nDA:1,1\nDA:2,0\nend_of_record\n')
put('flutter', '.dart_tool/x/coverage/lcov.info', 'SF:lib/a.dart\nDA:1,1\nend_of_record\n')
put('flutter', 'ios/.symlinks/plugins/p/coverage/lcov.info', 'SF:lib/a.dart\nDA:1,1\nend_of_record\n')
found, _, _ = run('flutter', '--root', '.', '--own', 'flutter')
check('Flutter: one row per package, the merged files of the library are not counted again, a line is counted once',
      found == rows(('flutter', 'coverage/lcov.info', 2, 2), ('flutter', 'modules/pay/coverage/lcov.info', 0, 2),
                    ('flutter', 'plugins/camera/coverage/lcov.info', 2, 3)), found)
found, _, _ = run('flutter', '--pattern', 'total_lcov.info')
awk = subprocess.run(['awk', '-F:', '/^DA:/{split($2,a,","); total++; if(a[2]>0) covered++} END{printf "%d,%d", covered, total}',
                      'total_lcov.info'], cwd=os.path.join(work, 'flutter'), capture_output=True, text=True).stdout
check('Flutter: total_lcov.info named in coverage.reportPath gives the value the awk reader gave',
      found == rows(('flutter', 'total_lcov.info', 3, 7)) and awk == '3,7', found + [awk])

put('lcov', 'crlf/coverage/lcov.info', 'TN:\r\nSF:src/a.ts\r\nDA:1,0\r\nDA:2,0\r\nDA:3,4\r\nLF:3\r\nLH:1\r\nend_of_record\r\n')
put('lcov', 'totals/coverage/lcov.info', 'TN:\nSF:src/b.ts\nLF:10\nLH:7\nend_of_record\n')
put('lcov', 'abs/coverage/lcov.info', 'SF:%s/abs/src/c.ts\nDA:1,1\nend_of_record\n' % os.path.join(work, 'lcov'))
put('lcov', 'bom/coverage/lcov.info', '﻿TN:\nSF:src/d.ts\nDA:1,1\nDA:2,0\nend_of_record\n')
found, _, _ = run('lcov', '--root', '.', '--own', 'node')
check('LCOV: CRLF line ends, LF/LH totals without DA lines, absolute source paths and a byte order mark are read',
      found == rows(('lcov', 'abs/coverage/lcov.info', 1, 1), ('lcov', 'bom/coverage/lcov.info', 1, 2),
                    ('lcov', 'crlf/coverage/lcov.info', 1, 3), ('lcov', 'totals/coverage/lcov.info', 7, 10)), found)

put('fifo', 'target/site/jacoco/jacoco.xml', jacoco([('Api', 8, 2)]))
fifo = os.path.join(work, 'fifo', 'build', 'reports', 'jacoco', 'test', 'jacocoTestReport.xml')
os.makedirs(os.path.dirname(fifo))
os.mkfifo(fifo)
late = os.path.join(work, 'late.py')
open(late, 'w').write(script.replace('signal.alarm(900)', 'signal.alarm(1)'))
found, _, head = run('fifo', '--root', '.', '--own', 'gradle', limit=20, program=late)
check('a scan that runs past its time limit prints only the E line, so the reports are read as before',
      script.count('signal.alarm(900)') == 1 and found == [] and head == ['E\tthe scan took longer than 15 minutes'], head + found)

put('same', 'target/site/jacoco/jacoco.xml', jacoco([('A', 8, 2), ('B', 0, 0)]))
put('same', 'target/site/jacoco-it/jacoco.xml', jacoco([('A', 3, 7), ('B', 0, 0)]))
found, _, _ = run('same', '--root', '.', '--own', 'maven')
check('two reports of the same classes with other numbers (unit and integration tests) both count',
      [r.split('\t')[5] for r in found] == ['', ''] and len(found) == 2, found)

put('names', 'zażółć/build/reports/jacoco/test/jacocoTestReport.xml', jacoco([('Grüße', 9, 1)]))
plain = dict((k, v) for k, v in os.environ.items() if not k.startswith(('LC_', 'LANG', 'PYTHON')))
found, classes, other = run('names', '--root', '.', '--own', 'gradle', env=dict(plain, LC_ALL='C', PYTHONIOENCODING='ascii'))
check('non-ASCII folder and class names are printed as UTF-8 whatever the locale, and nothing else is printed',
      found == rows(('jacoco', 'zażółć/build/reports/jacoco/test/jacocoTestReport.xml', 9, 10))
      and classes == ['C\tPackage: com.bbh.x, Class: com.bbh.x.Grüße, Coverage: 90.0, Missed: 1, Covered: 9'] and other == ['S\t0'],
      found + classes + other)

listing = [(n, c, m) for n, c, m in [('C%03d' % i, i % 17, (i * 7) % 13) for i in range(300)]]
put('classes', 'jacoco.xml', jacoco(listing))
_, classes, _ = run('classes', '--pattern', 'jacoco.xml')
old = subprocess.run([sys.executable, '-I', '-', 'jacoco.xml'], input=old_classes, cwd=os.path.join(work, 'classes'),
                     capture_output=True, text=True).stdout.splitlines()
expected = []
for line in old:
    p = line.split(',')
    expected.append('C\tPackage: %s, Class: %s, Coverage: %s, Missed: %s, Covered: %s' % (p[0], p[1], float(p[3]), p[5], p[4]))
check('the class listing is the one readJaCoCoPerClass printed, for 300 classes', classes == expected,
      [a for a, b in zip(classes, expected) if a != b][:3])

out = subprocess.run([sys.executable, '-I', scan, '--bogus', 'x'], capture_output=True, text=True).stdout
check('an unexpected error is one E line, never a traceback', out.startswith('E\t') and out.count('\n') == 1, out)

for i in range(400):
    put('scale', 'm%03d/build/reports/jacoco/test/jacocoTestReport.xml' % i, jacoco([('K%03d' % i, 3, 1)]))
    for j in range(40):
        put('scale', 'm%03d/src/main/java/F%02d.java' % (i, j), 'class F {}\n')
for j in range(3000):
    put('scale', 'web/node_modules/p%04d/index.js' % j, '')
start = time.time()
found, _, _ = run('scale', '--root', '.', '--own', 'gradle')
took = time.time() - start
check('400 module reports among 19 000 files are read in under 30 seconds', len(found) == 400 and took < 30, '%d rows, %.1fs' % (len(found), took))

if failures:
    print('%d COVERAGE SCAN SCENARIO(S) FAILED' % len(failures))
    sys.exit(1)
print('ALL COVERAGE SCAN SCENARIOS PASSED')
PY
