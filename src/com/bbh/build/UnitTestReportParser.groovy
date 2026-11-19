package com.bbh.build

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class UnitTestReportParser implements Serializable {

    private final def script
    private final PipelineState state
    private final OsHelper os

    UnitTestReportParser(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state = state
        this.os = os
    }

    def findCoverageReports(String tool = 'gradle') {
        List<String> names = BuildUtils.isDotnetTool(tool)
                ? ['coverage.cobertura.xml', 'coverage.opencover.xml', '*cobertura*.xml', '*opencover*.xml']
                : ['jacoco.xml']
        if (os.isWindows()) {
            List<String> globs = names.collect { "**/${it}".toString() }
            List paths = []
            for (String g : globs) {
                try {
                    for (def f : script.findFiles(glob: g)) paths << (f.path as String)
                } catch (Exception ignored) {}
            }
            return paths.unique()
        }
        String namePred = names.collect { "-iname '${it}'" }.join(' -o ')
        List paths = (script.sh(script: "find '${script.env.WORKSPACE}' -type f \\( ${namePred} \\)", returnStdout: true).trim().split('\n') as List).findAll { it?.trim() }
        for (def it : paths) {
            script.echo "===== Coverage reports: ${it} ====="
        }
        return paths
    }

    @NonCPS
    static List<String> jacocoCandidates(String tool) {
        if (BuildUtils.isDotnetTool(tool)) {
            return ['**/coverage.cobertura.xml', '**/coverage.opencover.xml', 'coverage.cobertura.xml', 'coverage.opencover.xml', 'TestResults/coverage.cobertura.xml']
        }
        return tool == 'maven'
                ? ['target/site/jacoco/jacoco.xml', 'target/jacoco/jacoco.xml']
                : ['build/jacoco/jacoco.xml',
                   'build/reports/jacoco/test/jacocoTestReport.xml',
                   'build/reports/jacoco/jacocoTestReport.xml']
    }

    List readJaCoCoPerClass(String xmlPath) {
        String result = script.sh(returnStdout: true, script: """
python3 - "${xmlPath}" <<'PYEOF'
import xml.etree.ElementTree as ET
import sys
 
try:
    tree = ET.parse(sys.argv[1])
    root = tree.getroot()
 
    for pkg in root.findall('package'):
        package_name = pkg.get('name', '').replace('/', '.')
 
        for cls in pkg.findall('class'):
            class_name = cls.get('name', '').replace('/', '.')
            source_file = cls.get('sourcefilename', '')
 
            covered = 0
            missed = 0
 
            for counter in cls.findall('counter'):
                if counter.get('type') == 'LINE':
                    covered = int(counter.get('covered', 0))
                    missed = int(counter.get('missed', 0))
 
            total = covered + missed
            pct = round(covered * 100.0 / total, 1) if total else 0.0
 
            print(f"{package_name},{class_name},{source_file},{pct},{covered},{missed},{total}")
 
except Exception:
    print("ERROR,,,,0.0,0,0,0")
PYEOF
""").trim()

        def stats = []
        for (String line : result.split('\\n')) {
            def parts = line.split(',')
            if (parts.size() >= 7 && parts[0] != 'ERROR') {
                stats << [
                        packageName: parts[0],
                        className  : parts[1],
                        sourceFile : parts[2],
                        pct        : parts[3] as double,
                        covered    : parts[4] as int,
                        missed     : parts[5] as int,
                        total      : parts[6] as int
                ]
            }
        }
        return stats
    }

    Map readXmlCoverage(String xmlPath) {
        String result
        if (os.isWindows()) {
            result = script.powershell(returnStdout: true, script: """
\$ErrorActionPreference = 'SilentlyContinue'
try {
    [xml]\$doc = Get-Content -Path '${BuildUtils.psSingleQuote(xmlPath)}' -Raw
    \$kind = 'jacoco'; \$covered = 0; \$missed = 0
    if (\$doc.report) {
        \$counter = \$doc.report.counter | Where-Object { \$_.type -eq 'LINE' } | Select-Object -First 1
        if (\$counter) { \$covered = [int]\$counter.covered; \$missed = [int]\$counter.missed }
    } elseif (\$doc.coverage) {
        \$kind = 'cobertura'
        \$lv = 0; \$lc = 0
        try { \$lv = [int]\$doc.coverage.'lines-valid'; \$lc = [int]\$doc.coverage.'lines-covered' } catch {}
        if (\$lv -gt 0) {
            \$covered = \$lc; \$missed = \$lv - \$lc
        } else {
            \$seen = @{}
            foreach (\$ln in \$doc.SelectNodes('//line')) {
                \$k = (\$ln.ParentNode.ParentNode.filename + ':' + \$ln.number)
                \$h = 0; try { \$h = [int][double]\$ln.hits } catch {}
                if (-not \$seen.ContainsKey(\$k) -or \$h -gt \$seen[\$k]) { \$seen[\$k] = \$h }
            }
            foreach (\$v in \$seen.Values) { if (\$v -gt 0) { \$covered++ } else { \$missed++ } }
        }
    } elseif (\$doc.CoverageSession) {
        \$kind = 'opencover'
        \$pts = @{}
        foreach (\$sp in \$doc.SelectNodes('//SequencePoint')) {
            \$k = \$sp.fileid + ':' + \$sp.sl + ':' + \$sp.sc
            \$vc = 0; try { \$vc = [int]\$sp.vc } catch {}
            if (-not \$pts.ContainsKey(\$k) -or \$vc -gt \$pts[\$k]) { \$pts[\$k] = \$vc }
        }
        if (\$pts.Count -gt 0) {
            foreach (\$v in \$pts.Values) { if (\$v -gt 0) { \$covered++ } else { \$missed++ } }
        } elseif (\$doc.CoverageSession.Summary) {
            \$tot = [int]\$doc.CoverageSession.Summary.numSequencePoints
            \$covered = [int]\$doc.CoverageSession.Summary.visitedSequencePoints
            \$missed = [math]::Max(0, \$tot - \$covered)
        }
    }
    \$total = \$covered + \$missed
    \$pct = if (\$total -gt 0) { [math]::Round(\$covered * 100.0 / \$total, 1) } else { 0.0 }
    Write-Output "\$kind,\$pct,\$covered,\$missed"
} catch { Write-Output 'jacoco,0.0,0,0' }
""").trim()
        } else {
            result = script.sh(returnStdout: true, script: """
python3 - "${xmlPath}" <<'PYEOF'
import xml.etree.ElementTree as ET, sys
try:
    root = ET.parse(sys.argv[1]).getroot()
    if root.tag == 'report':
        for c in root.findall('counter'):
            if c.get('type') == 'LINE':
                cov, mis = int(c.get('covered', 0)), int(c.get('missed', 0))
                tot = cov + mis
                pct = round(cov * 100.0 / tot, 1) if tot else 0.0
                print(f'jacoco,{pct},{cov},{mis}'); sys.exit(0)
    elif root.tag == 'coverage':
        lv = int(root.get('lines-valid', 0) or 0)
        lc = int(root.get('lines-covered', 0) or 0)
        if lv > 0:
            pct = round(lc * 100.0 / lv, 1)
            print(f'cobertura,{pct},{lc},{max(0, lv - lc)}'); sys.exit(0)
        seen = {}
        for cls in root.iter('class'):
            fn = cls.get('filename', '') or cls.get('name', '')
            for ln in cls.iter('line'):
                num = ln.get('number', '')
                hits = int(float(ln.get('hits', 0) or 0))
                if num:
                    seen[(fn, num)] = max(seen.get((fn, num), 0), hits)
        cov = sum(1 for v in seen.values() if v > 0)
        tot = len(seen)
        pct = round(cov * 100.0 / tot, 1) if tot else 0.0
        print(f'cobertura,{pct},{cov},{tot - cov}'); sys.exit(0)
    elif root.tag == 'CoverageSession':
        pts = {}
        for sp in root.iter('SequencePoint'):
            k = (sp.get('fileid', ''), sp.get('sl', ''), sp.get('sc', ''))
            pts[k] = max(pts.get(k, 0), int(sp.get('vc', 0) or 0))
        if pts:
            cov = sum(1 for v in pts.values() if v > 0); tot = len(pts)
        else:
            sm = root.find('Summary')
            tot = int(sm.get('numSequencePoints', 0) or 0) if sm is not None else 0
            cov = int(sm.get('visitedSequencePoints', 0) or 0) if sm is not None else 0
        pct = round(cov * 100.0 / tot, 1) if tot else 0.0
        print(f'opencover,{pct},{cov},{max(0, tot - cov)}'); sys.exit(0)
    print('jacoco,0.0,0,0')
except Exception: print('jacoco,0.0,0,0')
PYEOF
""").trim()
        }
        List parts = (result ?: '').split(',') as List
        if (parts.size() >= 4) {
            return UnitTestReportParser.coverageFrom("${parts[1]},${parts[2]},${parts[3]}") + [kind: parts[0]]
        }
        return UnitTestReportParser.coverageFrom(result) + [kind: 'jacoco']
    }

    Map readLcov(String lcovPath) {
        String result
        if (os.isWindows()) {
            result = script.powershell(returnStdout: true, script: """
\$total = 0; \$covered = 0
Get-Content '${lcovPath}' | ForEach-Object {
    if (\$_ -match '^DA:\\d+,(\\d+)') { \$total++; if ([int]\$Matches[1] -gt 0) { \$covered++ } }
}
\$pct = if (\$total -gt 0) { [math]::Round(\$covered * 100.0 / \$total, 1) } else { 0.0 }
Write-Output "\$pct,\$covered,\$(\$total - \$covered)"
""").trim()
        } else {
            result = script.sh(returnStdout: true, script: """
awk -F: '/^DA:/{split(\$2,a,","); total++; if(a[2]>0) covered++} END{pct=(total>0)?(covered*100.0/total):0; printf "%.1f,%d,%d\\n",pct,covered,total-covered}' "${lcovPath}" 2>/dev/null || echo "0.0,0,0"
""").trim()
        }
        return UnitTestReportParser.coverageFrom(result)
    }

    Map parseTrxReports(List<String> trxFiles) {
        String out
        if (os.isWindows()) {
            String psList = trxFiles.collect { "'${BuildUtils.psSingleQuote(it)}'" }.join(', ')
            out = script.powershell(returnStdout: true, script: """
\$passed = 0; \$failed = 0; \$skipped = 0
foreach (\$f in @(${psList})) {
    try {
        [xml]\$doc = Get-Content -Path \$f -Raw
        \$c = \$doc.SelectSingleNode('//*[local-name()="Counters"]')
        if (\$c) {
            \$t = [int]\$c.total; \$p = [int]\$c.passed
            \$fl = ([int]\$c.failed) + ([int]\$c.error) + ([int]\$c.timeout)
            \$passed += \$p; \$failed += \$fl
            \$skipped += [math]::Max(0, \$t - \$p - \$fl)
        }
    } catch {}
}
Write-Output "\$passed,\$failed,\$skipped"
""").trim()
        } else {
            String joined = trxFiles.collect { BuildUtils.shellQuoteIfNeeded(it) }.join(' ')
            out = script.sh(returnStdout: true, script: """
python3 - ${joined} <<'PYEOF'
import xml.etree.ElementTree as ET, sys
passed = failed = skipped = 0
for p in sys.argv[1:]:
    try:
        root = ET.parse(p).getroot()
        for el in root.iter():
            if el.tag.split('}')[-1] == 'Counters':
                t = int(el.get('total', 0) or 0)
                pa = int(el.get('passed', 0) or 0)
                fl = int(el.get('failed', 0) or 0) + int(el.get('error', 0) or 0) + int(el.get('timeout', 0) or 0)
                passed += pa; failed += fl; skipped += max(0, t - pa - fl)
                break
    except Exception: pass
print(f'{passed},{failed},{skipped}')
PYEOF
""").trim()
        }
        def parts = (out ?: '').split(',')
        return [
                passed : parts.length > 0 && parts[0].isInteger() ? (parts[0] as int) : 0,
                failed : parts.length > 1 && parts[1].isInteger() ? (parts[1] as int) : 0,
                skipped: parts.length > 2 && parts[2].isInteger() ? (parts[2] as int) : 0
        ]
    }

    Map parseFlutterTestReport() {
        String out
        if (os.isWindows()) {
            out = script.powershell(returnStdout: true, script: '''
$passed = 0; $failed = 0; $skipped = 0
try {
    Get-Content test_report.json | ForEach-Object {
        try {
            $e = $_ | ConvertFrom-Json
            if ($e.type -eq 'testDone') {
                switch ($e.result) {
                    'success' { $passed++ }
                    'failure' { $failed++ }
                    'error'   { $failed++ }
                    'skipped' { $skipped++ }
                }
            }
        } catch {}
    }
} catch {}
Write-Output "$passed,$failed,$skipped"
''').trim()
        } else {
            out = script.sh(returnStdout: true, script: '''
python3 - <<'PYEOF'
import json
passed = failed = skipped = 0
try:
    for line in open('test_report.json'):
        try:
            e = json.loads(line)
            if e.get('type') == 'testDone':
                r = e.get('result', '')
                if r == 'success':             passed  += 1
                elif r in ('failure','error'): failed  += 1
                elif r == 'skipped':           skipped += 1
        except: pass
except: pass
print(f'{passed},{failed},{skipped}')
PYEOF
''').trim()
        }
        def parts = out.split(',')
        return [
                passed : parts.length > 0 ? (parts[0] as int) : 0,
                failed : parts.length > 1 ? (parts[1] as int) : 0,
                skipped: parts.length > 2 ? (parts[2] as int) : 0
        ]
    }

    String dotnetArtifactGlob(boolean windows) {
        String custom = ((state.cfg.build?.buildPath ?: '') as String).trim()
        if (custom) return windows ? custom.replace('/', '\\') : custom
        return windows
                ? 'bin\\Release\\*.dll,bin\\Release\\*.exe,*\\bin\\Release\\*.dll,*\\bin\\Release\\*.exe,*.nupkg'
                : '*/*/bin/Release/*.dll */bin/Release/*.dll bin/Release/*.dll *.nupkg'
    }

    @NonCPS
    static String flutterArtifactGlob(String platform, boolean windows) {
        def sep = windows ? '\\' : '/'
        def globs = [
                apk      : "build${sep}app${sep}outputs${sep}flutter-apk${sep}*.apk",
                appbundle: "build${sep}app${sep}outputs${sep}bundle${sep}release${sep}*.aab",
                ios      : "build${sep}ios${sep}iphoneos${sep}*.ipa",
                macos    : "build${sep}macos${sep}Build${sep}Products${sep}Release${sep}*.app",
                linux    : "build${sep}linux${sep}x64${sep}release${sep}bundle${sep}*",
                windows  : "build${sep}windows${sep}x64${sep}runner${sep}Release${sep}*.exe",
                web      : ''
        ]
        return globs.get(platform) ?: ''
    }

    @NonCPS
    static Map coverageFrom(String result) {
        List parts = (result ?: '').split(',') as List
        double pct = parts.size() > 0 ? (parts[0] as double) : 0.0d
        int covered = parts.size() > 1 ? (parts[1] as int) : 0
        int missed = parts.size() > 2 ? (parts[2] as int) : 0
        return [line: pct, covered: covered, missed: missed, total: covered + missed]
    }
}
