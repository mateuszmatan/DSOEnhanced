package com.bbh.core

import com.cloudbees.groovy.cps.NonCPS

class OneViewPost implements Serializable {

    private final def script
    private final OsHelper os

    OneViewPost(def script, OsHelper os) {
        this.script = script
        this.os = os
    }

    void send(List vars, String path) {
        String unix = unixScript(path)
        String windows = windowsScript(path)
        script.withEnv(vars) {
            if (os.isWindows()) {
                script.powershell windows
            } else {
                script.sh unix
            }
        }
    }

    void cleanup(String tmpFile, String responseFile, boolean passAbort = false) {
        try {
            if (os.isWindows()) script.powershell "Remove-Item -Force '${tmpFile}', '${responseFile}' -ErrorAction SilentlyContinue"
            else                script.sh "rm -f '${tmpFile}' '${responseFile}'"
        } catch (Throwable t) {
            if (passAbort && t.getClass().getName().endsWith('FlowInterruptedException')) throw t
        }
    }

    @NonCPS
    static long buildNumber(def value) {
        String bn = (value ?: '').toString().trim()
        return bn ==~ /\d+/ ? bn.toLong() : 0L
    }

    @NonCPS
    static String unixScript(String path) {
        String template = '''set +x
code=$(curl -sS -k --connect-timeout 10 --max-time 60 -o "$ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$ONEVIEW_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$ONEVIEW_FILE")
rc=$?
if [ $rc -ne 0 ]; then
  exit $rc
fi
echo "[ONEVIEW] HTTP $code $(sed 's/"pipelineKey" *: *"[^"]*"/"pipelineKey":"(not shown)"/g' "$ONEVIEW_RESPONSE" 2>/dev/null)"
if [ "$code" -ge 300 ]; then
  exit 1
fi
'''
        return template.replace('/api/dso/reports', path)
    }

    @NonCPS
    static String windowsScript(String path) {
        String template = '''$code = curl.exe -sS -k --connect-timeout 10 --max-time 60 -o "$env:ONEVIEW_RESPONSE" -w '%{http_code}' -X POST "$env:ONEVIEW_URL/api/dso/reports" -H "Content-Type: application/json" --data-binary "@$env:ONEVIEW_FILE"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$answer = (Get-Content -Raw $env:ONEVIEW_RESPONSE -ErrorAction SilentlyContinue) -replace '"pipelineKey" *: *"[^"]*"', '"pipelineKey":"(not shown)"'
Write-Host "[ONEVIEW] HTTP $code $answer"
if ([int]$code -ge 300) { exit 1 }
'''
        return template.replace('/api/dso/reports', path)
    }
}
