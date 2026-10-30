package com.bbh.build

import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class CoverageReports implements Serializable {

    @NonCPS
    static List<String> paths(def value) {
        List items = value instanceof List ? (value as List) : ((value ?: '') as String).tokenize(',\r\n')
        return items.collect { (it ?: '').toString().trim() }.findAll { it } as List<String>
    }

    @NonCPS
    static String folderOf(def dir) {
        return BuildUtils.relativePath(((dir ?: '') as String).replace('\\', '/')) ?: '.'
    }

    @NonCPS
    static Map folders(Map cfg, String tool) {
        Map c = cfg ?: [:]
        String kind = ((c.buildTool ?: '') as String).trim() ?: tool
        def dir = [((c.tests?.unitTests ?: [:]) as Map)[kind]?.dir, ((c.build ?: [:]) as Map)[kind]?.dir, c.sourceDir].find { (it ?: '').toString().trim() }
        Map result = [(CoverageReports.folderOf(dir)): [kind]]
        if (BuildUtils.nodeBuildEnabled(c)) {
            String node = CoverageReports.folderOf(c.build?.node?.dir)
            result[node] = ((result[node] ?: []) as List) + ['node']
        }
        return result
    }

    @NonCPS
    static boolean inside(String folder, String root) {
        return folder != root && (root == '.' || folder.startsWith(root + '/'))
    }

    @NonCPS
    static Map search(String project, Map projects, String tool, List<String> roots = null) {
        Map all = projects ?: [:]
        Map mine = CoverageReports.folders(all[project] as Map, tool)
        Map theirs = [:]
        all.each { name, cfg -> if (name != project) theirs[name] = CoverageReports.folders(cfg as Map, tool) }
        List folders = ((theirs.values() as List) + (roots ? [] : [mine])).collectMany { (it as Map).keySet() as List }.unique()
        List parts = (roots ?: (mine.keySet() as List)).collect { def root ->
            Map shared = [:]
            theirs.each { name, places -> if ((places as Map).containsKey(root)) shared[name] = (places as Map)[root] }
            [root: root, own: roots ? mine.values().collectMany { it as List }.unique() : mine[root], shared: shared,
             others: shared.values().collectMany { it as List }.unique(), excluded: folders.findAll { CoverageReports.inside(it as String, root as String) }]
        }
        Map shared = [:]
        parts.each { (it.shared as Map).each { name, kinds -> shared[name] = (((shared[name] ?: []) as List) + (kinds as List)).unique() } }
        Map listed = [:]
        all.each { name, cfg -> if (name != project && CoverageReports.paths((cfg as Map)?.coverage?.reportPath)) listed[name] = CoverageReports.paths((cfg as Map).coverage.reportPath) }
        List places = parts.collect { it.root }
        return [parts: parts, roots: places, shared: shared, listed: listed, excluded: parts.collectMany { it.excluded as List }.unique() - places]
    }

    @NonCPS
    static List<Map> steps(String project, Map projects, String tool, List<String> patterns) {
        Map search = CoverageReports.search(project, projects, tool)
        List<Map> steps = []
        if (patterns) {
            steps << [line: "[COVERAGE] Reading the reports named by coverage.reportPath: ${patterns.join(', ')}".toString(),
                      args: patterns.collectMany { ['--pattern', it] }, search: search]
        }
        steps << [line: CoverageReports.searchLine(search, patterns ? 'coverage.reportPath names no report; searching' : 'Searching'),
                  args: CoverageReports.searchArgs(search), search: search]
        if (!(search.roots as List).contains('.')) {
            Map workspace = CoverageReports.search(project, projects, tool, ['.'])
            steps << [line: CoverageReports.searchLine(workspace, "No report in ${CoverageReports.place(search.roots as List)}; searching".toString()),
                      args: CoverageReports.searchArgs(workspace), search: workspace]
        }
        return steps
    }

    @NonCPS
    static List<String> searchArgs(Map search) {
        List<String> args = []
        (search.parts as List).eachWithIndex { def part, int n ->
            if (n) args << '--and'
            [root: '--root', own: '--own', others: '--others', excluded: '--exclude'].each { String key, String flag ->
                ((part[key] instanceof List ? part[key] : [part[key]]) as List).each { args.addAll([flag, it as String]) }
            }
        }
        return args
    }

    @NonCPS
    static String command(List<Map> steps) {
        List<String> args = []
        steps.eachWithIndex { Map step, int n -> args.addAll((n ? ['--then'] : []) + (step.args as List)) }
        List<String> words = []
        for (int i = 0; i < args.size(); i++) {
            words << args[i]
            if (args[i] != '--then' && args[i] != '--and') words << "'" + BuildUtils.escapeForSingleQuotes(args[++i]) + "'"
        }
        return "if ! command -v python3 >/dev/null 2>&1; then echo NO-PYTHON3; exit 0; fi\npython3 -I - ${words.join(' ')} <<'PYEOF'\n${scanScript()}PYEOF\n"
    }

    @NonCPS
    static Map parse(String out, List<Map> steps) {
        Map result = [step: steps.size() - 1, reports: [], classes: [], error: '', noPython: false]
        (out ?: '').readLines().each { String line ->
            List<String> f = line.split('\t', -1) as List<String>
            if (line.trim() == 'NO-PYTHON3') result.noPython = true
            else if (f[0] == 'S' && f.size() > 1) result.step = f[1] as int
            else if (f[0] == 'R' && f.size() > 5) {
                Map search = steps[result.step as int].search as Map
                String also = f.size() > 6 && f[6] ? CoverageReports.alsoFor((search.shared ?: [:]) as Map, f[6], (search.listed ?: [:]) as Map, f[2]) : ''
                (result.reports as List) << CoverageReports.row(f[1], f[2], f[3] as int, f[4] as int, f[5], also, f.size() > 7 ? f[7] : '')
            }
            else if (f[0] == 'C') (result.classes as List) << line.substring(2)
            else if (f[0] == 'E') result.error = f.size() > 1 ? f[1] : 'unknown error'
        }
        return result
    }

    @NonCPS
    static String alsoFor(Map shared, String owner, Map listed = [:], String path = '') {
        List names = shared.findAll { name, kinds -> (kinds as List).contains(owner) }.keySet() as List
        List counting = shared.keySet().findAll { def name ->
            listed.containsKey(name) ? (listed[name] as List).any { CoverageReports.names(it as String, path) } : (names ?: shared.keySet()).contains(name)
        } as List
        return counting ? "also counted for ${counting.join(', ')}".toString() : ''
    }

    @NonCPS
    static boolean names(String pattern, String path) {
        List<String> parts = BuildUtils.relativePath(pattern).split('/', -1) as List<String>
        String rx = ''
        for (int i = 0; i < parts.size(); i++) {
            boolean last = i == parts.size() - 1
            if (parts[i] == '**') {
                rx += last ? '.*' : '(?:[^/]+/)*'
                continue
            }
            for (int n = 0; n < parts[i].length(); n++) {
                String c = parts[i].substring(n, n + 1)
                rx += c == '*' ? '[^/]*' : c == '?' ? '[^/]' : c ==~ /\w/ ? c : '\\' + c
            }
            if (!last) rx += '/'
        }
        return path ==~ rx
    }

    @NonCPS
    static List<String> distinct(List paths) {
        return (paths ?: []).collect { (it as String).replace('\\', '/') }.unique() as List<String>
    }

    @NonCPS
    static boolean isLcov(String path) {
        String low = (path ?: '').toLowerCase()
        return low.endsWith('.info') || low.endsWith('.lcov')
    }

    @NonCPS
    static Map row(String kind, String path, int covered, int total, String note, String also = '', String sha = '') {
        String code = note ?: (total ? '' : 'empty')
        String reason = code == 'empty' ? 'no lines'
                : code == 'unreadable' ? 'could not be read'
                : code.startsWith('copy:') ? "a copy of ${code.substring(5)}".toString()
                : code.startsWith('aggregate:') ? "it combines ${code.substring(10)} other reports".toString()
                : ''
        String rest = code.startsWith('rest:') ? "only its ${code.substring(5)} ${kind == 'jacoco' ? 'classes' : 'files'} that no other report holds".toString() : ''
        return [kind: kind, tool: CoverageReports.toolName(kind), path: path, covered: covered, missed: total - covered, total: total,
                line: total ? CoverageReports.round1(covered * 1000.0 / total) : null, counted: !reason, reason: reason,
                detail: [rest, reason ? '' : also].findAll { it }.join('; '), sha: sha]
    }

    @NonCPS
    static String toolName(String kind) {
        return ([jacoco: 'JaCoCo', lcov: 'LCOV', flutter: 'Flutter'][kind] ?: kind) as String
    }

    @NonCPS
    static double round1(def tenths) {
        return ((tenths + 0.5) as long) / 10.0
    }

    @NonCPS
    static Map summary(List reports) {
        List counted = (reports ?: []).findAll { (it as Map).counted }
        def tenths = 0.0
        int covered = 0
        int total = 0
        counted.each { def r ->
            tenths += (r.covered as int) * 1000.0 / (r.total as int)
            covered += r.covered as int
            total += r.total as int
        }
        double line = counted ? CoverageReports.round1(tenths / counted.size()) : 0.0d
        return [counted: counted.size(), line: line, covered: covered, missed: total - covered, total: total]
    }

    @NonCPS
    static Map runSummary(Map projectsCoverage) {
        List counted = []
        Map paths = [:]
        Map shas = [:]
        (projectsCoverage ?: [:]).values().each { def p ->
            List mine = ((p as Map)?.reports ?: []).findAll { def r -> (r as Map).counted && !paths.containsKey(r.path) && !(r.sha && shas.containsKey(r.sha)) }
            mine.each { def r ->
                paths[r.path] = true
                if (r.sha) shas[r.sha] = true
            }
            counted.addAll(mine)
        }
        return CoverageReports.summary(counted)
    }

    @NonCPS
    static String kindsOf(List reports) {
        return (reports ?: []).findAll { (it as Map).counted }.collect { it.kind == 'jacoco' ? 'jacoco' : 'lcov' }.unique().join('+')
    }

    @NonCPS
    static String searchLine(Map search, String lead) {
        List names = (search.shared as Map).keySet() as List
        String shared = names ? "; ${names.join(', ')} ${names.size() == 1 ? 'uses' : 'use'} the same folder: each service takes the reports of its own build tool" : ''
        return "[COVERAGE] ${lead} ${CoverageReports.where(search)} for JaCoCo and LCOV reports${shared}".toString()
    }

    @NonCPS
    static String place(List roots) {
        return roots.collect { it == '.' ? 'the workspace' : it }.join(' and ')
    }

    @NonCPS
    static String where(Map search) {
        String place = CoverageReports.place(search.roots as List)
        return search.excluded ? "${place} (without ${(search.excluded as List).join(', ')})".toString() : place
    }

    @NonCPS
    static String noReport(Map search, String place) {
        return "No JaCoCo or LCOV coverage report found in ${CoverageReports.where(search)}, set coverage.reportPath for the service in ${place} to point at it".toString()
    }

    @NonCPS
    static String noLines(List reports) {
        List names = (reports ?: []).collect { (it as Map).path as String }
        String more = names.size() > 3 ? " and ${names.size() - 3} more" : ''
        return "No coverage report with measured lines (${(names.size() > 3 ? names.subList(0, 3) : names).join(', ')}${more})".toString()
    }

    @NonCPS
    static String logText(List log, List reports) {
        List lines = (log ?: []) + (reports ?: []).collect { def r ->
            String measure = r.total ? "${r.line}% (${r.covered} of ${r.total} lines)" : "${r.reason}, not counted"
            String note = !r.total ? '' : r.reason ? " - not counted, ${r.reason}" : r.detail ? " - ${r.detail}" : ''
            "[COVERAGE] ${r.tool} ${r.path}: ${measure}${note}".toString()
        }
        return lines.join('\n')
    }

    @NonCPS
    static String classText(List reports, List classes) {
        if (!(reports ?: []).any { (it as Map).counted && it.kind == 'jacoco' }) return ''
        return (['Class coverage: '] + (classes ?: []) + ['=========================']).join('\n')
    }

    @NonCPS
    static String scanScript() {
        return '''import hashlib, os, re, signal, sys
import xml.etree.ElementTree as ET

TAB = chr(9)
SKIP = {'.git', '.svn', '.hg', 'node_modules', 'bower_components', 'jspm_packages', '.gradle', '.m2', '.dart_tool',
        '.pub-cache', '.pub', '.fvm', '.symlinks', '.plugin_symlinks', 'Pods', '.angular', '.nx', '.next', '.nuxt',
        '.yarn', '.pnpm-store', '.npm', '.cache', '.turbo', '.parcel-cache', '.idea', '.kotlin', 'venv', '.venv',
        '__pycache__', '.tox', 'site-packages', '.scannerwork', '.sonar', 'appscan-source-staged', '.appscan-logs',
        'DevSecOpsJenkinsLibrary'}
COPIES = {'total_lcov.info', 'main_lcov.info', 'plugins_lcov.info', 'modules_lcov.info', 'lcov.base.info'}
XML = ('jacoco', '/reports/coverage/', '/reports/kover/')


def norm(path):
    p = os.path.normpath(path).replace(os.sep, '/')
    return '' if p == '.' else p


def under(path, folder):
    return folder == '' or path == folder or path.startswith(folder + '/')


def walk(top, excluded):
    for here, dirs, files in os.walk(top or '.'):
        base = norm(here)
        dirs[:] = sorted(d for d in dirs if d not in SKIP and not d.endswith(('@tmp', '@script', '@libs'))
                         and not any(len(x) > len(top) and under(norm(os.path.join(base, d)), x) for x in excluded))
        for name in sorted(files):
            yield norm(os.path.join(base, name))


def below(path, top):
    return path[len(top) + 1:] if top else path


def candidate(path, top):
    low = '/' + below(path, top).lower()
    name = low.rsplit('/', 1)[-1]
    if low.endswith('.xml') and any(x in low for x in XML):
        return True
    return name.endswith(('.info', '.lcov')) and name not in COPIES


def matcher(pattern):
    rx = ''
    parts = norm(pattern).split('/')
    for i, part in enumerate(parts):
        last = i == len(parts) - 1
        if part == '**':
            rx += '.*' if last else '(?:[^/]+/)*'
            continue
        for c in part:
            rx += '[^/]*' if c == '*' else '[^/]' if c == '?' else re.escape(c)
        rx += '' if last else '/'
    return re.compile(rx + '$')


def wild(text):
    return '*' in text or '?' in text


def by_pattern(patterns):
    found = []
    for pattern in patterns:
        if not wild(pattern):
            if os.path.isfile(pattern):
                found.append(norm(pattern))
            continue
        static = []
        for part in norm(pattern).split('/'):
            if wild(part):
                break
            static.append(part)
        top = '/'.join(static)
        if os.path.isdir(top or '.'):
            rx = matcher(pattern)
            found.extend(p for p in walk(top, []) if rx.match(p))
    return sorted(set(found), key=found.index)


def jacoco(data):
    head = data[:2048]
    if b'-//JACOCO//DTD Report' not in head and b'<report' not in head:
        return None
    root = ET.fromstring(data)
    if root.tag != 'report':
        return None
    covered = missed = 0
    for c in root.findall('counter'):
        if c.get('type') == 'LINE':
            covered, missed = int(c.get('covered', 0)), int(c.get('missed', 0))
            break
    lines, listing = {}, {}
    for pkg in root.iter('package'):
        package = pkg.get('name', '').replace('/', '.')
        for cls in pkg.findall('class'):
            name = cls.get('name', '')
            c = m = 0
            for counter in cls.findall('counter'):
                if counter.get('type') == 'LINE':
                    c, m = int(counter.get('covered', 0)), int(counter.get('missed', 0))
            pct = round(c * 100.0 / (c + m), 1) if c + m else 0.0
            lines[name] = (c, c + m)
            listing[name] = ('Package: %s, Class: %s, Coverage: %s, Missed: %d, Covered: %d'
                             % (package, name.replace('/', '.'), pct, m, c))
    return {'kind': 'jacoco', 'covered': covered, 'total': covered + missed, 'lines': lines, 'listing': listing}


def package_of(path):
    parts = path.split('/')[:-1]
    if 'coverage' in parts:
        parts = parts[:len(parts) - 1 - parts[::-1].index('coverage')]
    return '/'.join(parts)


def source(base, name):
    if os.path.isabs(name):
        rel = os.path.relpath(name)
        return norm(name if rel.startswith('..') else rel)
    return norm(os.path.join(base, name) if base else name)


def lcov(data, path):
    text = data.decode('utf-8-sig', 'replace').splitlines()
    first = next((line.strip() for line in text if line.strip()), '')
    if not first.startswith(('TN:', 'SF:', 'VER:')):
        return None
    merge = path.rsplit('/', 1)[-1].lower() not in COPIES
    base, hits, sums, current = package_of(path), {}, {}, None
    for raw in text:
        line = raw.strip()
        if line.startswith('SF:'):
            current = source(base, line[3:])
            sums.setdefault(current, [0, 0])
        elif line.startswith('DA:') and current is not None:
            parts = line[3:].split(',')
            try:
                count = float(parts[1])
            except (IndexError, ValueError):
                continue
            key = (current, parts[0] if merge else len(hits))
            hits[key] = max(hits.get(key, 0.0), count)
        elif line[:3] in ('LH:', 'LF:') and line[3:].isdigit() and current is not None:
            sums[current][line[:3] == 'LF:'] += int(line[3:])
        elif line == 'end_of_record':
            current = None
    lines = {name: tuple(v) for name, v in sums.items()}
    if hits:
        lines = {name: (0, 0) for name in sums}
        for (name, _), count in hits.items():
            c, t = lines[name]
            lines[name] = (c + (count > 0), t + 1)
    dart = sum(1 for name in lines if name.endswith('.dart'))
    return {'kind': 'flutter' if lines and dart * 2 > len(lines) else 'lcov', 'covered': sum(c for c, _ in lines.values()),
            'total': sum(t for _, t in lines.values()), 'lines': lines, 'listing': {}}


def read(path, xml):
    try:
        with open(path, 'rb') as f:
            data = f.read()
        report = jacoco(data) if xml else lcov(data, path)
        if report:
            report['sha'] = hashlib.sha1(data).hexdigest()
        return report
    except Exception:
        return {'kind': 'jacoco' if xml else 'lcov', 'covered': 0, 'total': 0, 'lines': {}, 'listing': {}, 'sha': '',
                'note': 'unreadable'}


def builder(path, top):
    for part in reversed(below(path, top).split('/')[:-1]):
        if part in ('target', 'build'):
            return 'maven' if part == 'target' else 'gradle'
    return 'none'


def part(o, seen):
    own, others, pattern = set(o['own']), set(o['others']), bool(o['pattern'])
    if pattern:
        found = [(p, '') for p in by_pattern(o['pattern'])]
    else:
        found, excluded = [], [norm(x) for x in o['exclude']]
        for top in [norm(r) for r in o['root'] or ['.']]:
            found.extend((p, top) for p in walk(top, excluded) if candidate(p, top))
    reports = []
    for path, top in found:
        xml = path.lower().endswith('.xml')
        kind = builder(path, top) if xml else ''
        if path in seen or not pattern and kind in others and kind not in own:
            continue
        report = read(path, xml)
        if report is None:
            continue
        kind = kind or ('flutter' if report['kind'] == 'flutter' else 'node')
        if not pattern and kind in others and kind not in own:
            continue
        seen.add(path)
        report.update(path=path, set=frozenset(report['lines']),
                      shared=kind if others and not pattern and (kind in others or kind not in own) else '')
        report.setdefault('note', '' if report['total'] else 'empty')
        reports.append(report)
    return reports


def scan(parts):
    seen = set()
    reports = [r for o in parts for r in part(o, seen)]
    return reports if any(o['pattern'] for o in parts) else judge(reports)


def judge(reports):
    family = lambda r: r['kind'] == 'jacoco'
    kept = []
    for r in sorted(reports, key=lambda r: (r['path'].count('/'), len(r['path']), r['path'])):
        if r['note']:
            continue
        same = next((k for k in kept if family(k) == family(r) and (k['sha'] == r['sha'] or k['set'] and k['set'] == r['set']
                     and (k['covered'], k['total']) == (r['covered'], r['total']))), None)
        if same:
            r['note'] = 'copy:' + same['path']
        else:
            kept.append(r)
    for r in kept:
        parts = []
        for s in sorted({k['set'] for k in kept if family(k) == family(r) and k['set'] and k['set'] < r['set']},
                        key=lambda s: (len(s), sorted(s))):
            if not any(s & p for p in parts):
                parts.append(s)
        if len(parts) > 1:
            r['note'] = 'aggregate:%d' % len(parts)
    held = {True: set(), False: set()}
    for k in kept:
        if not k['note']:
            held[family(k)] |= k['set']
    rest = {}
    for r in sorted((k for k in kept if k['note']), key=lambda k: len(k['set'])):
        names = [n for n in r['lines'] if n not in held[family(r)]]
        held[family(r)] |= set(names)
        total = sum(r['lines'][n][1] for n in names)
        if total:
            rest[r['path']] = dict(r, note='rest:%d' % len(names), covered=sum(r['lines'][n][0] for n in names), total=total,
                                   listing={n: r['listing'][n] for n in names if n in r['listing']})
    return [x for r in reports for x in [r] + ([rest[r['path']]] if r['path'] in rest else [])]


def fresh():
    return {'root': [], 'own': [], 'others': [], 'exclude': [], 'pattern': []}


def main(argv):
    signal.signal(signal.SIGALRM, late)
    signal.alarm(900)
    steps, i = [[fresh()]], 0
    while i < len(argv):
        if argv[i] == '--then':
            steps.append([fresh()])
        elif argv[i] == '--and':
            steps[-1].append(fresh())
        else:
            steps[-1][-1][argv[i][2:]].append(argv[i + 1])
            i += 1
        i += 1
    for n, parts in enumerate(steps):
        reports = scan(parts)
        if reports or n == len(steps) - 1:
            break
    out = ['S' + TAB + str(n)]
    out += [TAB.join(['R', r['kind'], r['path'], str(r['covered']), str(r['total']), r['note'], r['shared'], r['sha']]) for r in reports]
    out += ['C' + TAB + line for r in reports if not r['note'] or r['note'].startswith('rest:') for line in r['listing'].values()]
    signal.alarm(0)
    return out


def late(*_):
    os.write(1, ('E' + TAB + 'the scan took longer than 15 minutes' + chr(10)).encode())
    os._exit(0)


try:
    text = chr(10).join(main(sys.argv[1:]))
except Exception as e:
    text = 'E' + TAB + (str(e) or e.__class__.__name__).replace(TAB, ' ').splitlines()[0][:300]
sys.stdout.buffer.write((text + chr(10)).encode('utf-8', 'surrogateescape'))
'''
    }
}
