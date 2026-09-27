package com.bbh.scanner

import com.bbh.build.BuildRunner
import com.bbh.build.BuildService
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.AppScanReportParser
import com.bbh.utils.RestClient
import com.bbh.utils.BuildUtils

class AppScanService implements Serializable {
    private final def script
    private String token
    private final PipelineState state
    private final OsHelper os
    private final PolicyEngine policy
    private final BuildService build
    private final String shProxyEnv = '''_PP="$(cat "$APPSCAN_LOG_DIR/proxy.pass")"
_OPTS="$APPSCAN_OPTS -Dhttp.proxyPassword=$_PP -Dhttps.proxyPassword=$_PP"'''
    private final long tokenRefreshIntervalMs = 5400000L

    AppScanService(def script, PipelineState state, OsHelper os, PolicyEngine policy, BuildService build) {
        this.script = script
        this.state = state
        this.os = os
        this.policy = policy
        this.build = build
    }

    void setup() {
        String workspace = (script.env.WORKSPACE ?: script.pwd()) as String
        script.env.APPSCAN_HOST = script.env.APPSCAN_HOST ?: 'bbh.cloud.appscan.com'
        script.env.APPSCAN_SERVER_URL = script.env.APPSCAN_SERVER_URL ?: 'https://bbh.cloud.appscan.com'
        script.env.APPSCAN_TOOLS_DIR = script.env.APPSCAN_TOOLS_DIR ?: "${workspace}/.appscan-tools"
        script.env.APPSCAN_LOG_DIR = script.env.APPSCAN_LOG_DIR ?: "${workspace}/.appscan-logs"
        script.env.APPSCAN_HOME_DIR = script.env.APPSCAN_HOME_DIR ?: "${workspace}/.appscan-home"
        script.env.APPSCAN_BIN_DIR = script.env.APPSCAN_BIN_DIR ?: "${workspace}/.appscan-bin"

        loadCertsFromResources()
        fetchProxyPassword()
        createDirs()
    }

    void cliLogin() {
        script.withEnv(["APPSCAN_CMD_RUNTIME=${appscanCmd()}", "APPSCAN_OPTS=${proxyOpts()}"]) {
            script.withCredentials([script.string(credentialsId: state.cfg.asoc.token ?: "${script.env.APPSCAN_KEY_ID}", variable: "APPSCAN_KEY_SECRET")]) {
                if (os.isWindows()) {
                    def appscanPath = script.pwd().replace('\\', '/') + state.cfg?.appscanPath
                    script.powershell """
\$ErrorActionPreference = 'Stop'
\$env:HOME        = \$env:APPSCAN_HOME_DIR
\$env:USERPROFILE = \$env:APPSCAN_HOME_DIR
\$out = & ${appscanPath} api_login -u "${script.env.APPSCAN_KEY_ID}" -P "${script.env.APPSCAN_KEY_SECRET}" -persist -acceptssl -service_url "${script.env.APPSCAN_SERVER_URL}" 2>&1
\$out | Out-File -FilePath "\$env:APPSCAN_LOG_DIR/api_login.log" -Encoding UTF8
if ((\$out -join ' ') -match 'unable to authenticate|authentication failed|unauthorized|token has expired') {
    Write-Error "[ERROR] AppScan CLI login failed."; exit 1
}
Write-Host "[INFO] AppScan CLI login succeeded."
"""
                } else {
                    script.sh """#!/usr/bin/env bash
set -euo pipefail; set +x
${shProxyEnv}
env -u APPSCAN_DOMAIN \\
    PATH="\$APPSCAN_BIN_DIR:\$PATH" HOME="\$APPSCAN_HOME_DIR" USERPROFILE="\$APPSCAN_HOME_DIR" \\
    APPSCAN_OPTS="\${_OPTS}" \\
"\$APPSCAN_CMD_RUNTIME" api_login \\
    -u "${script.env.APPSCAN_KEY_ID}" -P "${script.env.APPSCAN_KEY_SECRET}" -persist -acceptssl \\
    -service_url "${script.env.APPSCAN_SERVER_URL}" \\
    > "\$APPSCAN_LOG_DIR/api_login.log" 2>&1 || true
grep -v "^Picked up " "\$APPSCAN_LOG_DIR/api_login.log" || true
if grep -qiE 'unable to authenticate|authentication failed|unauthorized|token has expired' "\$APPSCAN_LOG_DIR/api_login.log"; then
    echo "[ERROR] AppScan CLI login failed."; exit 1
fi
echo "[INFO] AppScan CLI login succeeded."
"""
                }
            }
        }
    }

    void resolveSourceDir(String param = null) {
        def resolved = (param ?: state.cfg.sourceDir?.trim() ?: '.').trim()
        if (!resolved || resolved == '.') resolved = script.env.WORKSPACE
        else if (!resolved.startsWith('/') && !resolved.contains(':')) resolved = "${script.env.WORKSPACE}/${resolved}"
        script.env.APPSCAN_SOURCE_DIR_RESOLVED = resolved

        script.env.APPSCAN_SOURCE_FOLDERS = state.cfg.includedDirs?.trim() ?: ''
        script.env.APPSCAN_EXCLUDED_FOLDERS = state.cfg.excludedDirs?.trim() ?: ''
    }

    void generateIrx(int timeoutMin = 120) {
        if (!script.env.APPSCAN_SOURCE_DIR_RESOLVED?.trim()) script.error "APPSCAN_SOURCE_DIR_RESOLVED not set"
        boolean doCompile = BuildUtils.booleanValue(state.cfg.asoc?.doCompile, true)
        boolean sourceCodeOnly = BuildUtils.booleanValue(state.cfg.asoc?.sourceCodeOnly, false)
        boolean useAppScanConfig = BuildUtils.booleanValue(state.cfg.asoc?.useAppScanConfig, false)
        String scoOption = sourceCodeOnly ? '-sco' : ''
        def tm = timeoutMin ?: (state.cfgDefaults.sast?.prepareTimeoutMin ?: 120) as int
        script.timeout(time: tm, unit: 'MINUTES') {
            script.withEnv([
                    "APPSCAN_CMD_RUNTIME=${appscanCmd()}",
                    "APPSCAN_SOURCE_DIR_RUNTIME=${script.env.APPSCAN_SOURCE_DIR_RESOLVED}",
                    "APPSCAN_SOURCE_FOLDERS=${script.env.APPSCAN_SOURCE_FOLDERS ?: ''}",
                    "APPSCAN_EXCLUDED_FOLDERS=${script.env.APPSCAN_EXCLUDED_FOLDERS ?: ''}",
                    "APPSCAN_OPTS=${proxyOpts()}"
            ]) {
                if (os.isWindows()) {
                    def appscanPath = script.pwd().replace('\\', '/') + state.cfg?.appscanPath
                    def modulesRaw = state.cfg?.includedDirs ?: ''
                    def modules = modulesRaw.split(',').collect { "'${it.trim()}'" }.join(',')
                    script.powershell """
\$ErrorActionPreference = 'Stop'
\$env:HOME        = \$env:APPSCAN_HOME_DIR
\$env:USERPROFILE = \$env:APPSCAN_HOME_DIR
Remove-Item -Force "\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx" -ErrorAction SilentlyContinue

\$sastDirectory = "\$env:WORKSPACE\\sast_directory"
if (Test-Path -Path \$sastDirectory) {Remove-Item -Path \$sastDirectory -Recurse -Force }
New-Item -ItemType Directory -Path \$sastDirectory | Out-Null

foreach (\$folder in ${modules}){
    \$srcPath = "\$env:APPSCAN_SOURCE_DIR_RUNTIME\\\$folder"
    \$targetPath = "\$sastDirectory\\\$folder"
    if (Test-Path \$srcPath){
        robocopy "\$srcPath" "\$targetPath" /E /XD test test_utils Tests /MT:16 /NFL /NDL /NJH /NP /NS
    }
}
Set-Location -Path \$sastDirectory
& ${appscanPath} prepare -s thorough -n "\$env:APPSCAN_SCAN_NAME" -d "\$env:WORKSPACE" -l "\$env:APPSCAN_SOURCE_DIR_RUNTIME\\lib;\$env:APPSCAN_SOURCE_DIR_RUNTIME\\modules;\$env:APPSCAN_SOURCE_DIR_RUNTIME\\plugins" 2>&1 |
    Out-File -FilePath "\$env:APPSCAN_LOG_DIR/prepare.log" -Encoding UTF8
if (-not (Test-Path "\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx")) { Write-Error "[ERROR] IRX not found after prepare"; exit 1 }
Write-Host "[INFO] IRX size: \$((Get-Item \"\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx\").Length) bytes"
Set-Location -Path "\$env:WORKSPACE"
"""
                } else {
                    if (doCompile) {
                        def tool = build.detectTool()
                        Map compileCfg = (state.cfg.asoc?.get(tool) ? state.cfg.asoc : state.cfg.build) as Map
                        BuildRunner runner = new BuildRunner(script, compileCfg, tool)
                        if (!runner.ifExist()) {
                            script.error "[SAST] Build tool is NOT installed on an agent"
                        }
                        runner.run()
                    }
                    script.sh """#!/usr/bin/env bash
set -euo pipefail; set +x

rm -f "\$WORKSPACE/\$APPSCAN_SCAN_NAME.irx" || true

APPSCAN_SOURCE_FOLDERS="\${APPSCAN_SOURCE_FOLDERS:-}"
APPSCAN_EXCLUDED_FOLDERS="\${APPSCAN_EXCLUDED_FOLDERS:-}"

APPSCAN_CONFIG_FILE="\${APPSCAN_CONFIG_FILE:-appscan-config.xml}"
case "\$APPSCAN_CONFIG_FILE" in
    /*)
        APPSCAN_CONFIG_PATH="\$APPSCAN_CONFIG_FILE"
        ;;
    *)
        APPSCAN_CONFIG_PATH="\$WORKSPACE/\$APPSCAN_CONFIG_FILE"
        ;;
esac

echo "===== [INFO]: environment ====="
echo "PWD=\$(pwd)"
echo "WORKSPACE=\$WORKSPACE"
echo "APPSCAN_SOURCE_DIR_RUNTIME=\$APPSCAN_SOURCE_DIR_RUNTIME"
echo "APPSCAN_SOURCE_FOLDERS=\$APPSCAN_SOURCE_FOLDERS"
echo "APPSCAN_EXCLUDED_FOLDERS=\$APPSCAN_EXCLUDED_FOLDERS"
echo "APPSCAN_SCAN_NAME=\$APPSCAN_SCAN_NAME"
echo "APPSCAN_BIN_DIR=\$APPSCAN_BIN_DIR"
echo "APPSCAN_HOME_DIR=\$APPSCAN_HOME_DIR"
echo "APPSCAN_LOG_DIR=\$APPSCAN_LOG_DIR"
echo "APPSCAN_CONFIG_FILE=\$APPSCAN_CONFIG_FILE"
echo "JAVA_HOME=\${JAVA_HOME:-}"
echo "PATH=\$PATH"
 
echo "===== [INFO]: Workspace content ====="
ls -la "\$WORKSPACE" || true
echo "===== [INFO]: AppScan Source Folder content ====="
ls -la "\$APPSCAN_SOURCE_DIR_RUNTIME" || true

echo "[IRX] Source directory: \$APPSCAN_SOURCE_DIR_RUNTIME"

if [ "${useAppScanConfig}" = "true" ]; then

    APPSCAN_CONFIG_ARGS=(
        -c "\$APPSCAN_CONFIG_PATH"
    )
    SCAN_DIR="\$WORKSPACE"
    
else    
    # Prevent AppScan from automatically loading appscan-config.xml from the current directory.
    APPSCAN_CONFIG_ARGS=(-nc)

    echo "===== Create staged AppScan source directory ====="
    
    if [ "\$APPSCAN_SOURCE_FOLDERS" = "\$APPSCAN_SOURCE_DIR_RUNTIME" ] && [ -z \$APPSCAN_EXCLUDED_FOLDERS ]; then
        echo "[INFO] SOURCE_FOLDERS is the same as APPSCAN_SOURCE_DIR_RUNTIME and no excluded folders are specified. Skipping staging."
        SCAN_DIR="\$APPSCAN_SOURCE_DIR_RUNTIME"
    else
        SCAN_DIR="\$WORKSPACE/appscan-source-staged"
         
        rm -rf "\$SCAN_DIR"
        mkdir -p "\$SCAN_DIR"
         
        echo "[INFO] Copying source folders: \$APPSCAN_SOURCE_FOLDERS"
         
        IFS=',' read -ra INCLUDED_FOLDERS <<< "\$APPSCAN_SOURCE_FOLDERS"
        IFS=',' read -ra EXCLUDED_FOLDERS <<< "\$APPSCAN_EXCLUDED_FOLDERS"
          
        for i in "\${!INCLUDED_FOLDERS[@]}"; do
            INCLUDED_FOLDERS[\$i]="\$(echo "\${INCLUDED_FOLDERS[\$i]}" | xargs)"
        done
        
        for i in "\${!EXCLUDED_FOLDERS[@]}"; do
            EXCLUDED_FOLDERS[\$i]="\$(echo "\${EXCLUDED_FOLDERS[\$i]}" | xargs)"
        done 
        
        HAS_INCLUDED=false
        HAS_EXCLUDED=false
        
        [ -n "\$APPSCAN_SOURCE_FOLDERS" ] && HAS_INCLUDED=true
        [ -n "\$APPSCAN_EXCLUDED_FOLDERS" ] && HAS_EXCLUDED=true
            
        if \$HAS_INCLUDED; then
            echo "[INFO] Copying only included directories"
         
            for INCLUDED in "\${INCLUDED_FOLDERS[@]}"; do
                [ -z "\$INCLUDED" ] && continue
         
                find "\$APPSCAN_SOURCE_DIR_RUNTIME" -path "\$SCAN_DIR" -prune -o -mindepth 1 -type d -name "\$INCLUDED" -print |
                while IFS= read -r DIR; do
                    REL_PATH="\${DIR#"\$APPSCAN_SOURCE_DIR_RUNTIME"/}"
         
                    echo "[INFO] Copying included directory: \$REL_PATH"
         
                    mkdir -p "\$SCAN_DIR/\$(dirname "\$REL_PATH")"
                    cp -a "\$DIR" "\$SCAN_DIR/\$REL_PATH"
                done
            done
        
        elif \$HAS_EXCLUDED; then
            echo "[INFO] Copying everything except excluded directories"
     
            find "\$APPSCAN_SOURCE_DIR_RUNTIME" -path "\$SCAN_DIR" -prune -o -mindepth 1 -print0 |
            while IFS= read -r -d '' SOURCE_PATH; do
         
                REL_PATH="\${SOURCE_PATH#"\$APPSCAN_SOURCE_DIR_RUNTIME"/}"
                SKIP=false
                for EXCLUDED in "\${EXCLUDED_FOLDERS[@]}"; do
                    [ -z "\$EXCLUDED" ] && continue
         
                    if [[ "/\$REL_PATH/" == *"\$EXCLUDED/"* ]]; then
                        SKIP=true
                        break
                    fi
                done
         
                \$SKIP && continue
     
                TARGET_PATH="\$SCAN_DIR/\$REL_PATH"
                if [ -d "\$SOURCE_PATH" ]; then
                    mkdir -p "\$TARGET_PATH"
                elif [ -f "\$SOURCE_PATH" ]; then
                    mkdir -p "\$(dirname "\$TARGET_PATH")"
                    cp -a "\$SOURCE_PATH" "\$TARGET_PATH"
                elif [ -L "\$SOURCE_PATH" ]; then
                    mkdir -p "\$(dirname "\$TARGET_PATH")"
                    cp -a "\$SOURCE_PATH" "\$TARGET_PATH"
                fi
            done
            
        else
            echo "[INFO] No included or excluded directories specified"
            echo "[INFO] Copying complete source directory contents"
         
            find "\$APPSCAN_SOURCE_DIR_RUNTIME" -path "\$SCAN_DIR" -prune -o -mindepth 1 -print0 |
            while IFS= read -r -d '' SOURCE_PATH; do
                REL_PATH="\${SOURCE_PATH#"\$APPSCAN_SOURCE_DIR_RUNTIME"/}"
                TARGET_PATH="\$SCAN_DIR/\$REL_PATH"
         
                if [ -d "\$SOURCE_PATH" ]; then
                    mkdir -p "\$TARGET_PATH"
                else
                    mkdir -p "\$(dirname "\$TARGET_PATH")"
                    cp -a "\$SOURCE_PATH" "\$TARGET_PATH"
                fi
            done
        fi             
    fi
    cd \$SCAN_DIR
    
    if [ ! -d ".git" ]; then
        git init >/dev/null 2>&1
    fi
fi        

_irx_java=\$(find "\$SCAN_DIR" -name "*.java"   2>/dev/null | wc -l | tr -d ' ')
_irx_grv=\$(find  "\$SCAN_DIR" -name "*.groovy" 2>/dev/null | wc -l | tr -d ' ')
_irx_kt=\$(find   "\$SCAN_DIR" -name "*.kt"     2>/dev/null | wc -l | tr -d ' ')
echo "[IRX] Source files: .java=\$_irx_java  .groovy=\$_irx_grv  .kt=\$_irx_kt"

echo "AppScan Prepare CLI is running..."

if [ ! -d ".git" ]; then
    git init >/dev/null 2>&1
fi
 
set +e

env -u APPSCAN_DOMAIN \\
    PATH="\$APPSCAN_BIN_DIR:\$PATH" HOME="\$APPSCAN_HOME_DIR" USERPROFILE="\$APPSCAN_HOME_DIR" \\
    APPSCAN_OPTS="\$APPSCAN_OPTS" \\
"\$APPSCAN_CMD_RUNTIME" prepare -s thorough "\${APPSCAN_CONFIG_ARGS[@]}" -n "\$APPSCAN_SCAN_NAME" -d . ${scoOption} \\
    > "\$APPSCAN_LOG_DIR/prepare.log" 2>&1
    
PREPARE_RC=\$?
 
set -e
 
echo "===== [INFO]: AppScan prepare result ====="
echo "AppScan prepare exit code: \$PREPARE_RC"
 
echo "===== [INFO]: prepare.log - first 50 lines ====="
head -n 50 "\$APPSCAN_LOG_DIR/prepare.log"

echo "===== [INFO]: prepare.log - last 50 lines ====="
tail -n 50 "\$APPSCAN_LOG_DIR/prepare.log"
 
IRX_FILE=\$(find "\$WORKSPACE" "\$SCAN_DIR" "\$APPSCAN_HOME_DIR" "\$APPSCAN_LOG_DIR" /tmp/appscan-workspace \\
  -name "*.irx" -type f 2>/dev/null | head -1 || true)
 
if [ "\$PREPARE_RC" -ne 0 ]; then
  echo "[ERROR] AppScan prepare failed with exit code \$PREPARE_RC"
  exit "\$PREPARE_RC"
fi
 
if [ -z "\$IRX_FILE" ]; then
  echo "[ERROR] IRX not found after prepare"
  exit 1
fi
 
echo "[INFO] Found IRX: \$IRX_FILE"
 
if [ "\$IRX_FILE" != "\$WORKSPACE/\$APPSCAN_SCAN_NAME.irx" ]; then
  echo "[INFO] Copying IRX to expected location: \$WORKSPACE/\$APPSCAN_SCAN_NAME.irx"
  cp "\$IRX_FILE" "\$WORKSPACE/\$APPSCAN_SCAN_NAME.irx"
fi
 
echo "[INFO] IRX size: \$(wc -c < "\$WORKSPACE/\$APPSCAN_SCAN_NAME.irx" | tr -d ' ') bytes"
"""
                }
            }
        }
    }

    void queueSast(Map config) {
        script.echo "Start queue sast"
        def scanId = queueAnalysis("${script.env.APPSCAN_SCAN_NAME ?: 'sast-scan'}-sast",
                "${script.env.WORKSPACE}/${script.env.APPSCAN_SCAN_NAME ?: 'my-project'}.irx", config)
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/scan-sast-${script.env.APPSCAN_SCAN_NAME}.id", text: scanId
    }

    void waitSast() {
        pollScan('sast', (state.cfgDefaults.sast?.pollTimeoutMin ?: 50) as int, (state.cfgDefaults.sast?.pollIntervalSec ?: 30) as int)
    }

    void downloadSastReports() {
        def dest = "${script.env.WORKSPACE}/appscan-report-${script.env.APPSCAN_SCAN_NAME}.html"
        getReports('sast', dest)
        if (script.fileExists(dest)) {
            def counts = parseHtmlCounts(dest, 'sast')
            state.vulnCounts.sast = counts
            state.recordVulns('sast', counts)
        } else {
            script.echo "[SAST] HTML report not found - vulnerability counts remain 0"
        }
    }

    void renameSastReport() {
        def scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        if (!scanName) return
        renameReportFile("${script.env.WORKSPACE}/appscan-report.html", "${script.env.WORKSPACE}/appscan-report-${scanName}.html")
    }

    void dastScan() {
        def cfgDast = state.cfg.dast ?: [:]
        if (!(cfgDast.enabled as boolean)) {
            script.echo "[DAST] Disabled in config - not required for this project."
            state.stageResults['DAST - Dynamic Application Security Tests - HCL AppScan'] = 'NOT_REQUIRED'
            state.policyStatus['dast'] = 'NOT_REQUIRED'
            return
        }
        apiLogin()
        def scanId = dastStartScan()
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/scan-dast-${script.env.APPSCAN_SCAN_NAME}.id", text: scanId
        script.echo "[DAST] Scan ID: ${scanId}"
        recordDastConsoleLink(scanId)

        def pollTimeoutMin = (state.cfgDefaults.dast?.pollTimeoutMin ?: 60) as int
        def pollIntervalSec = (state.cfgDefaults.dast?.pollIntervalSec ?: 60) as int
        dastWait(scanId, pollTimeoutMin, pollIntervalSec)

        dastDownloadReport(scanId)
        renameDastReport()
        countDastVulns()
    }

    void apiLogin() {
        script.echo "[DAST] Starting API Authentication"

        String response = ''
        script.withCredentials([
                script.usernamePassword(
                        credentialsId: 'hcl-app-scan-acount',
                        passwordVariable: 'Secret',
                        usernameVariable: 'ID'
                )
        ]) {
            String credentials = "-d \"{\\\"KeyId\\\":\\\"${script.env.ID}\\\",\\\"KeySecret\\\":\\\"${script.env.Secret}\\\"}\""
            response = script.sh(
                    script: curlCommand(['-H "Content-Type: application/json"', credentials], appscanApiUrl('Account/ApiKeyLogin')),
                    returnStdout: true
            ).trim()
        }

        this.token = script.readJSON(text: response).Token
        if (!this.token) {
            script.error("[DAST] Unable to fetch Bearer Token: ${response}")
        }
        script.echo "[DAST] Authenticated successfully."
    }

    String dastStartScan() {
        script.echo "[DAST] Sending execute DAST scan request."

        def appId = state.cfg.appId?.trim()
        def targetUrl = state.cfg.dast?.targetUrl?.trim()
        def presenceId =  state.cfg.dast?.presenceId ? state.cfg.dast.presenceId.trim() : ''

        if (!appId) script.error("[DAST] appId not configured in config.yaml")
        if (!targetUrl) script.error("[DAST] dast.targetUrl not configured in config.yaml")

        def payload = """{
            "ScanConfiguration": {
                "Target": {
                  "StartingUrl": "${targetUrl}",
                }
            },
            "PresenceId": "${presenceId}",
            "ScanName": "${script.env.APPSCAN_SCAN_NAME ?: 'app-scan'}-dast_${script.env.BUILD_NUMBER}",
            "AppId": "${appId}",
            "ClientType": "api",
            "FullyAutomatic": true
        }"""

        script.writeFile(file: 'dast_payload.json', text: payload)

        def response = script.sh(
                script: curlCommand(jsonHeaders() + ['-d @dast_payload.json'], appscanApiUrl('Scans/Dast')),
                returnStdout: true
        ).trim()
        def scanId = script.readJSON(text: response).Id

        if (!scanId) {
            script.error("[DAST] Error when starting DAST scan: ${response}")
        }

        script.echo "[DAST] DAST scan started. Scan ID: ${scanId}"
        return scanId
    }

    void dastWait(String scanId, int timeoutMin, int intervalSec) {
        script.echo "[DAST] Waiting for DAST scan: ${scanId}"
        long lastLoginTime = System.currentTimeMillis()

        script.timeout(time: timeoutMin, unit: 'MINUTES') {
            while (true) {
                if (System.currentTimeMillis() - lastLoginTime >= tokenRefreshIntervalMs) {
                    script.echo "[DAST] Refreshing AppScan token..."
                    apiLogin()
                    lastLoginTime = System.currentTimeMillis()
                }

                def response = script.sh(
                        script: curlCommand(acceptHeaders(), appscanApiUrl("Scans/Dast/${scanId}")),
                        returnStdout: true
                ).trim()
                def status = script.readJSON(text: response)?.LatestExecution?.Status ?: 'Unknow'

                script.echo "[DAST] DAST scan status: ${status}"

                if (status == "Ready") {
                    script.echo "[DAST] DAST scan finished with success."
                    break
                } else if (status == "Failed") {
                    script.error("[DAST] DAST scan finished with failure: ${response}")
                }

                script.sleep(time: intervalSec, unit: 'SECONDS')
            }
        }
    }

    void dastDownloadReport(String scanId) {
        Map html = downloadDastReport(scanId, 'Html', 'appscan-dast-report.html')
        Map pdf  = downloadDastReport(scanId, 'Pdf', 'appscan-dast-report.pdf')
        if (!pdf.ok) script.echo "[DAST] PDF report unavailable: ${pdf.reason} - the HTML report is still linked"
        if (!html.ok) {
            script.error("[DAST] The HTML report could not be produced, so the findings of this scan are unknown: ${html.reason}")
        }
    }

    Map downloadDastReport(String scanId, String fileType, String outFile) {
        script.echo "[DAST] Generating & Downloading ${fileType.toUpperCase()} report"
        int timeoutMin  = (state.cfgDefaults.dast?.reportTimeoutMin ?: 30) as int
        int intervalSec = (state.cfgDefaults.dast?.reportIntervalSec ?: 30) as int

        script.writeFile(file: 'report_payload.json',
                text: """{"Configuration":{"ReportFileType":"${fileType}","Summary":true,"Details":true,"Overview":true,"TableOfContent":true}}""")

        def response = script.sh(
                script: curlCommand(jsonHeaders() + ['-d @report_payload.json'], appscanApiUrl("Reports/Security/Scan/${scanId}")),
                returnStdout: true
        ).trim()
        def reportId = script.readJSON(text: response).Id
        if (!reportId) {
            return [ok: false, reason: "AppScan did not accept the ${fileType.toUpperCase()} report request: ${RestClient.abbreviate(response, 300)}".toString()]
        }
        script.echo "[DAST] Generating report ID: ${reportId}"
        return fetchRenderedReport(reportId, fileType, outFile, timeoutMin, intervalSec)
    }

    Map fetchRenderedReport(String reportId, String fileType, String outFile, int timeoutMin, int intervalSec) {
        long deadline = System.currentTimeMillis() + (timeoutMin * 60000L)
        int seconds = intervalSec < 1 ? 1 : intervalSec
        int maxAttempts = ((timeoutMin * 60) / seconds) as int
        if (maxAttempts < 1) maxAttempts = 1
        int attempt = 0
        String reason = 'the report was never rendered'
        while (attempt < maxAttempts && System.currentTimeMillis() < deadline) {
            attempt++
            String status = reportStatus(reportId)
            if (status == 'Failed') {
                return [ok: false, reason: "AppScan reported the ${fileType.toUpperCase()} report as Failed".toString()]
            }
            if (status == 'Ready' || status == 'Unknown') {
                script.sh(script: curlCommand([bearerHeader(), "-o \"${outFile}\""], appscanApiUrl("Reports/${reportId}/Download")))
                Map check = validateReportFile(outFile, fileType)
                if (check.ok) {
                    script.echo "[DAST] DAST report saved as ${outFile} after ${attempt} attempt(s)"
                    return [ok: true, reason: '']
                }
                reason = check.reason as String
            } else {
                reason = "AppScan reports the ${fileType.toUpperCase()} report as ${status}".toString()
            }
            if (attempt >= maxAttempts) break
            script.echo "[DAST] ${fileType.toUpperCase()} report not ready yet (${reason}) - waiting ${seconds}s, attempt ${attempt} of ${maxAttempts}"
            script.sleep(time: seconds, unit: 'SECONDS')
        }
        return [ok: false, reason: "${reason}, gave up after ${attempt} attempt(s) over at most ${timeoutMin} minute(s)".toString()]
    }

    String reportStatus(String reportId) {
        try {
            String body = script.sh(script: curlCommand(acceptHeaders(), appscanApiUrl("Reports/${reportId}")), returnStdout: true).trim()
            if (!body) return 'Unknown'
            def status = script.readJSON(text: body)?.Status
            return status ? (status as String) : 'Unknown'
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return 'Unknown'
        }
    }

    Map validateReportFile(String outFile, String fileType) {
        String path = "${script.env.WORKSPACE}/${outFile}"
        if (!script.fileExists(path)) return [ok: false, reason: 'the download produced no file']
        return AppScanReportParser.validate(readHead(path), fileType)
    }

    String readHead(String path) {
        try {
            String content = script.readFile(path) ?: ''
            return content.length() > 2048 ? content.substring(0, 2048) : content
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return ''
        }
    }

    void renameDastReport() {
        def scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        if (!scanName) return
        renameReportFile("${script.env.WORKSPACE}/appscan-dast-report.html", "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.html")
        renameReportFile("${script.env.WORKSPACE}/appscan-dast-report.pdf", "${script.env.WORKSPACE}/appscan-dast-report-${scanName}.pdf")
    }

    private void renameReportFile(String src, String dst) {
        if (os.isWindows()) script.powershell "if (Test-Path '${src}') { Move-Item '${src}' '${dst}' -Force }"
        else script.sh "[ -f '${src}' ] && mv '${src}' '${dst}' || true"
    }

    void countDastVulns() {
        def dest = "${script.env.WORKSPACE}/appscan-dast-report-${script.env.APPSCAN_SCAN_NAME}.html"
        def pdfName = "appscan-dast-report-${script.env.APPSCAN_SCAN_NAME}.pdf"
        if (script.fileExists("${script.env.WORKSPACE}/${pdfName}")) {
            state.recordScanArtifact('dast', 'pdf', pdfName)
            script.echo "[DAST] PDF report: ${pdfName}"
        } else {
            script.echo "[DAST] PDF report not found - only the HTML report will be linked"
        }
        if (!script.fileExists(dest)) {
            script.error("[DAST] ${dest.tokenize('/').last()} is missing, so the findings of this scan are unknown - the stage cannot be reported as passed")
        }
        Map check = validateReportFile(dest.tokenize('/').last(), 'Html')
        if (!check.ok) {
            script.error("[DAST] ${dest.tokenize('/').last()} does not hold a report: ${check.reason}")
        }
        def counts = parseHtmlCounts(dest, 'dast')
        state.vulnCounts.dast = counts
        state.recordVulns('dast', counts)
        script.echo "[DAST] C:${counts.critical} H:${counts.high} M:${counts.medium} L:${counts.low}"

        policy.registerFindings('dast', counts, dest.tokenize('/').last())
    }

    private void recordDastConsoleLink(String scanId) {
        String base = ((state.cfg.asoc?.url ?: script.env.APPSCAN_SERVER_URL ?: '') as String).trim().replaceAll('/+$', '')
        String appId = ((state.cfg.appId ?: '') as String).trim()
        if (!base || !appId) return
        String url = scanId ? "${base}/main/myapps/${appId}/scans/${scanId}" : "${base}/main/myapps/${appId}/scans"
        state.recordScanArtifact('dast', 'hcl', url)
    }

    Map parseHtmlCounts(String htmlFile, String type) {
        Map counts = AppScanReportParser.severities(script.readFile(htmlFile))
        int crit = counts.critical as int
        int high = counts.high as int
        int med  = counts.medium as int
        int low  = counts.low as int

        script.echo "[${type.toUpperCase()}] Report counts: Critical=${crit} High=${high} Medium=${med} Low=${low}"
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/${type}-${script.env.APPSCAN_SCAN_NAME}-critical.count", text: "${crit}"
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/${type}-${script.env.APPSCAN_SCAN_NAME}-high.count",     text: "${high}"
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/${type}-${script.env.APPSCAN_SCAN_NAME}-medium.count",   text: "${med}"
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/${type}-${script.env.APPSCAN_SCAN_NAME}-low.count",      text: "${low}"

        return [critical: crit, high: high, medium: med, low: low]
    }

    String curlCommand(List<String> options, String url) {
        List<String> parts = ['curl', '-s', '-S']
        if (BuildUtils.booleanValue(state.cfg.asoc?.insecureTls, false)) parts << '-k'
        parts << "-x \"http://${script.env.PROXY_HOST}:${script.env.PROXY_PORT}\""
        parts << "-U \"${script.env.PROXY_USER}:${script.env.PROXY_PASS}\""
        parts.addAll(options)
        parts << "\"${url}\""
        return parts.join(' ')
    }

    String appscanApiUrl(String path) {
        return "${script.env.APPSCAN_SERVER_URL}/api/v4/${path}"
    }

    String bearerHeader() {
        return "-H \"Authorization: Bearer ${this.token}\""
    }

    List<String> acceptHeaders() {
        return [bearerHeader(), '-H "Accept: application/json"']
    }

    List<String> jsonHeaders() {
        return acceptHeaders() + ['-H "Content-Type: application/json"']
    }

    private void pollScan(String type, int timeoutMin, int intervalSec) {
        def scanId = scanId(type)
        if (!scanId || scanId == 'NONE') script.error "[${type.toUpperCase()}] No scan ID available"
        script.timeout(time: timeoutMin, unit: 'MINUTES') {
            script.withEnv([
                    "APPSCAN_CMD_RUNTIME=${appscanCmd()}",
                    "APPSCAN_SCAN_ID=${scanId}",
                    "APPSCAN_POLL_INTERVAL=${intervalSec.toString()}",
                    "APPSCAN_OPTS=${proxyOpts()}"
            ]) {
                if (os.isWindows()) {
                    def appscanPath = script.pwd().replace('\\', '/') + state.cfg?.appscanPath
                    script.powershell """
\$ErrorActionPreference = 'Continue'
\$env:HOME        = \$env:APPSCAN_HOME_DIR
\$env:USERPROFILE = \$env:APPSCAN_HOME_DIR
\$pp = (Get-Content -Raw -Path "\$env:APPSCAN_LOG_DIR\\proxy.pass").Trim()
\$opts = "\$env:APPSCAN_OPTS -Dhttp.proxyPassword=\$pp -Dhttps.proxyPassword=\$pp"
\$env:APPSCAN_OPTS = \$opts

\$_start = Get-Date
while (\$true) {
    \$_elapsed = [int]((Get-Date) - \$_start).TotalSeconds
    \$out = & ${appscanPath} status -i "\$env:APPSCAN_SCAN_ID" 2>&1
    \$out | Out-File -FilePath "\$env:APPSCAN_LOG_DIR/status-${type}-${script.env.APPSCAN_SCAN_NAME}.log" -Encoding UTF8
    \$text = \$out -join ' '
    if (\$text -match 'Completed|Complete|Ready')        { Write-Host "[INFO] ${type.toUpperCase()} scan completed after \$_elapsed s."; exit 0 }
    if (\$text -match 'Failed|Error|Canceled|Cancelled') { Write-Error "[ERROR] ${type.toUpperCase()} scan failed after \$_elapsed s."; exit 1 }
    Write-Host "[RUNNING] ${type.toUpperCase()} scan in progress... \$_elapsed s elapsed"
    Start-Sleep -Seconds ([int]\$env:APPSCAN_POLL_INTERVAL)
}
"""
                } else {
                    script.sh """#!/usr/bin/env bash
set -euo pipefail; set +x
${shProxyEnv}
_START=\$(date +%s)
while true; do
    _ELAPSED=\$(( \$(date +%s) - _START ))
    env -u APPSCAN_DOMAIN \\
        PATH="\$APPSCAN_BIN_DIR:\$PATH" HOME="\$APPSCAN_HOME_DIR" USERPROFILE="\$APPSCAN_HOME_DIR" \\
        APPSCAN_OPTS="\${_OPTS}" \\
    "\$APPSCAN_CMD_RUNTIME" status -i "\$APPSCAN_SCAN_ID" \\
        > "\$APPSCAN_LOG_DIR/status-${type}-${script.env.APPSCAN_SCAN_NAME}.log" 2>&1 || true
    if grep -qiE "Completed|Complete|Ready"        "\$APPSCAN_LOG_DIR/status-${type}-${script.env.APPSCAN_SCAN_NAME}.log"; then echo "[INFO] ${type.toUpperCase()} scan completed after \${_ELAPSED}s."; exit 0; fi
    if grep -qiE "Failed|Error|Canceled|Cancelled" "\$APPSCAN_LOG_DIR/status-${type}-${script.env.APPSCAN_SCAN_NAME}.log"; then echo "[ERROR] ${type.toUpperCase()} scan failed after \${_ELAPSED}s."; exit 1; fi
    echo "[RUNNING] ${type.toUpperCase()} scan in progress... \${_ELAPSED}s elapsed"
    sleep "\$APPSCAN_POLL_INTERVAL"
done
"""
                }
            }
        }
    }

    private void getReports(String type, String dest) {
        script.withEnv(["APPSCAN_CMD_RUNTIME=${appscanCmd()}", "APPSCAN_SCAN_ID=${scanId(type)}", "APPSCAN_OPTS=${proxyOpts()}"]) {
            if (os.isWindows()) {
                def appscanPath = script.pwd().replace('\\', '/') + state.cfg?.appscanPath
                script.powershell """
\$env:HOME        = \$env:APPSCAN_HOME_DIR
\$env:USERPROFILE = \$env:APPSCAN_HOME_DIR

\$pp = (Get-Content -Raw -Path "\$env:APPSCAN_LOG_DIR\\proxy.pass").Trim()
\$env:APPSCAN_OPTS += " -Dhttp.proxyPassword=\$pp -Dhttps.proxyPassword=\$pp"
for (\$i = 1; \$i -le 30; \$i++) {
    \$cmd_out = & ${appscanPath} get_result -i "\$env:APPSCAN_SCAN_ID" -t html -d "${dest}" 2>&1
    Write-Host "-------"
    Write-Host \$cmd_out
    if ((Test-Path "${dest}") -and (Get-Item "${dest}").Length -gt 0) { Write-Host "[INFO] ${type.toUpperCase()} report downloaded"; break }
    Write-Host "[WARN] Report not ready, retry \$i/6..."; Start-Sleep -Seconds 10
}
"""
            } else {
                script.sh """#!/usr/bin/env bash
set -euo pipefail; set +x
${shProxyEnv}
for i in {1..6}; do
    env -u APPSCAN_DOMAIN \\
        PATH="\$APPSCAN_BIN_DIR:\$PATH" HOME="\$APPSCAN_HOME_DIR" USERPROFILE="\$APPSCAN_HOME_DIR" \\
        APPSCAN_OPTS="\${_OPTS}" \\
    "\$APPSCAN_CMD_RUNTIME" get_result -i "\$APPSCAN_SCAN_ID" -t html -d "${dest}" \\
        > "\$APPSCAN_LOG_DIR/get_result-${type}-${script.env.APPSCAN_SCAN_NAME}.log" 2>&1 || true
    if [ -s "${dest}" ]; then echo "[INFO] ${type.toUpperCase()} report downloaded"; break; fi
    echo "[WARN] Report not ready, retry \$i/6..."; sleep 10
done
"""
            }
        }
    }

    private String queueAnalysis(String name, String filePath, Map config) {
        def appId = state.cfg.appId?.trim()
        if (!appId) {
            appId = config.appId?.trim()
        }
        if (!appId) script.error "[APPSCAN] appId not configured in config.yaml"
        if (!script.fileExists(filePath)) script.error "[APPSCAN] File not found: ${filePath}"
        String output = ''
        script.withEnv(["APPSCAN_CMD_RUNTIME=${appscanCmd()}", "APPSCAN_OPTS=${proxyOpts()}"]) {
            if (os.isWindows()) {
                def appscanPath = script.pwd().replace('\\', '/') + state.cfg?.appscanPath
                output = script.powershell(returnStdout: true, script: """
\$ErrorActionPreference = 'Continue'
\$env:HOME        = \$env:APPSCAN_HOME_DIR
\$env:USERPROFILE = \$env:APPSCAN_HOME_DIR
\$pp = (Get-Content -Raw -Path "\$env:APPSCAN_LOG_DIR\\proxy.pass").Trim()
\$opts = "\$env:APPSCAN_OPTS -Dhttp.proxyPassword=\$pp -Dhttps.proxyPassword=\$pp"
\$env:APPSCAN_OPTS = \$opts
\$out = & ${appscanPath} queue_analysis -a "${appId}" -f "${filePath}" -n "${name}" 2>&1
\$out | Out-File -FilePath "\$env:APPSCAN_LOG_DIR\\queue-${name}.log" -Encoding UTF8
\$out
""").trim()
            } else {
                output = script.sh(returnStdout: true, script: """#!/usr/bin/env bash
set -euo pipefail; set +x
${shProxyEnv}
env -u APPSCAN_DOMAIN \\
    PATH="\$APPSCAN_BIN_DIR:\$PATH" HOME="\$APPSCAN_HOME_DIR" USERPROFILE="\$APPSCAN_HOME_DIR" \\
    APPSCAN_OPTS="\${_OPTS}" \\
"\$APPSCAN_CMD_RUNTIME" queue_analysis -a "${appId}" -f "${filePath}" -n "${name}" \\
    2>&1 | tee "\$APPSCAN_LOG_DIR/queue-${name}.log" | grep -v "^Picked up " || true
""").trim()
            }
        }
        def m = (output =~ /(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/)
        if (!m) {
            script.sh "grep -v '% transferred' ${script.env.APPSCAN_LOG_DIR}/queue-${name}.log"
            script.error "[APPSCAN] Could not extract scan ID from queue_analysis output for '${name}'. Check ${script.env.APPSCAN_LOG_DIR}/queue-${name}.log"
        }
        script.echo "[INFO] '${name}' queued - Scan ID: ${m[0]}"
        return m[0]
    }

    private void fetchProxyPassword() {
        if (os.isWindows()) {
            script.powershell '''
                $ErrorActionPreference = "Stop"
             
                $certsDir   = Join-Path $env:WORKSPACE "DevSecOpsJenkinsLibrary"
                $modulesDir = $env:PS_MODULES_PATH
             
                if (!(Test-Path $modulesDir)) {
                    throw "PowerShell modules folder not found: $modulesDir"
                }

                Get-ChildItem -Path $modulesDir -Recurse -File | Select-Object FullName, Extension | Format-Table -AutoSize
 
                $moduleManifest = Get-ChildItem -Path $modulesDir -Recurse -File -Filter "*.psd1" |
                    Where-Object { $_.Name -eq "safeguard-ps.psd1" } |
                    Select-Object -First 1
                 
                if (!$moduleManifest) {
                    $moduleManifest = Get-ChildItem -Path $modulesDir -Recurse -File -Filter "*.psd1" |
                        Select-Object -First 1
                }
                 
                if (!$moduleManifest) {
                    throw "No PowerShell module manifest .psd1 found in: $modulesDir"
                }
                 
                Write-Host "Importing PowerShell module manifest: $($moduleManifest.FullName)"
                Import-Module $moduleManifest.FullName -Force -DisableNameChecking
             
                $thumbprintFile          = Join-Path $certsDir "PROXY_ASOCJenk.thumb"
                $certificateFileBase64   = Join-Path $certsDir "PROXY_ASOCJenk.p12.b64"
                $certificateFile         = Join-Path $certsDir "PROXY_ASOCJenk.p12"
                $certificatePasswordFile = Join-Path $certsDir "PROXY_ASOCJenk.exppsw"
             
                $base64 = (Get-Content $certificateFileBase64 -Raw).Trim()
 
                [IO.File]::WriteAllBytes(
                    $certificateFile,
                    [Convert]::FromBase64String($base64)
                )
                 
                Write-Host "Decoded P12 file created:"
                Write-Host $p12File
             
                foreach ($file in @(
                    $thumbprintFile,
                    $certificateFile,
                    $certificatePasswordFile
                )) {
                    if (!(Test-Path $file)) {
                        throw "Required file not found: $file"
                    }
                }
             
                $thumbprint          = (Get-Content -Path $thumbprintFile -Raw).Trim()
                $certificatePassword = (Get-Content -Path $certificatePasswordFile -Raw).Trim()
             
                if ([string]::IsNullOrWhiteSpace($thumbprint)) {
                    throw "Thumbprint file is empty"
                }
             
                if ([string]::IsNullOrWhiteSpace($certificatePassword)) {
                    throw "Certificate password file is empty"
                }
                
                $securePassword = ConvertTo-SecureString `
                    -String $certificatePassword `
                    -AsPlainText `
                    -Force
                 
                Write-Host "Importing certificate $certificateFile into CurrentUser\\My store..."
                 
                $importedCert = Import-PfxCertificate `
                    -FilePath $certificateFile `
                    -CertStoreLocation Cert:\\CurrentUser\\My `
                    -Password $securePassword `
                    -Exportable
                 
                if (!$importedCert) {
                    throw "Certificate import failed"
                }
                 
                Write-Host "Certificate imported successfully"
             
                $apiKey = "9hV0qzEV9kou36xWP+TtBA6OWUKFuaq6J1jNgQ05Bjw="
             
                $proxyPass = Get-SafeguardA2aPassword `
                    -Insecure `
                    -Thumbprint $thumbprint `
                    -ApiKey $apiKey `
                    -Appliance "oisapi.bbh.com"
                    
                if (-not $proxyPass) { Write-Error "[ERROR] Failed to retrieve proxy password."; exit 1 }
                
                Write-Host "DEBUG: APPSCAN_LOG_DIR: $env:APPSCAN_LOG_DIR"
                New-Item -ItemType Directory -Force -Path \$env:APPSCAN_LOG_DIR
                \$utf8WithoutBom = New-Object System.Text.UTF8Encoding(\$false)
                [IO.File]::WriteAllText("$env:APPSCAN_LOG_DIR\\proxy.pass", \$proxyPass, \$utf8WithoutBom)    
                    
            '''

        } else {
            script.sh '''#!/usr/bin/env bash
set -euo pipefail; set -x
mkdir -p "$APPSCAN_LOG_DIR"
chmod +x DevSecOpsJenkinsLibrary/get-a2a-password.sh 2>/dev/null || true
PROXY_PASS="$(cat DevSecOpsJenkinsLibrary/PROXY_ASOCJenk.keypsw | DevSecOpsJenkinsLibrary/get-a2a-password.sh \
    -a oisapi.bbh.com \
    -c DevSecOpsJenkinsLibrary/PROXY_ASOCJenk.cert.pem \
    -k DevSecOpsJenkinsLibrary/PROXY_ASOCJenk.key.pem \
    -A "9hV0qzEV9kou36xWP+TtBA6OWUKFuaq6J1jNgQ05Bjw=" \
    -p)"
if [[ "$PROXY_PASS" == *"error"* ]]; then echo "[ERROR] Failed to retrieve proxy password."; exit 1; fi
printf '%s' "$PROXY_PASS" > "$APPSCAN_LOG_DIR/proxy.pass"
chmod 600 "$APPSCAN_LOG_DIR/proxy.pass"
'''
        }
    }


    void createDirs() {
        if (os.isWindows()) {
            script.powershell """
\$ErrorActionPreference = 'Stop'
foreach (\$d in @(\$env:APPSCAN_TOOLS_DIR,\$env:APPSCAN_LOG_DIR,\$env:APPSCAN_HOME_DIR,\$env:APPSCAN_BIN_DIR)) {
    New-Item -ItemType Directory -Force -Path \$d | Out-Null
}
\$zipPath = "\$env:APPSCAN_TOOLS_DIR/sa.zip"
Remove-Item -Force \$zipPath -ErrorAction SilentlyContinue
Write-Host "Generating SA Client..."
Invoke-WebRequest -Uri \$env:SA_WIN_URL -OutFile \$zipPath
if (-not (Test-Path \$zipPath) -or (Get-Item \$zipPath).Length -eq 0) { Write-Error "[ERROR] SAClientUtil archive missing or empty"; exit 1 }
Expand-Archive -Force -Path \$zipPath -DestinationPath \$env:APPSCAN_TOOLS_DIR
\$appscanCmd = Get-ChildItem -Recurse -Path \$env:APPSCAN_TOOLS_DIR -Filter "appscan.bat" | Select-Object -First 1 -ExpandProperty FullName
if (-not \$appscanCmd) { Write-Error "[ERROR] appscan.bat not found after extraction"; exit 1 }
\$logDir = \$env:APPSCAN_LOG_DIR.Replace('/', '\\')
\$logPath = Join-Path \$logDir "appscan.cmd"
[IO.File]::WriteAllText(\$logPath, \$appscanCmd, [Text.Encoding]::UTF8)
"""
        } else {
            script.sh '''#!/usr/bin/env bash
set -euo pipefail; set -x
mkdir -p "$APPSCAN_TOOLS_DIR" "$APPSCAN_LOG_DIR" "$APPSCAN_HOME_DIR" "$APPSCAN_BIN_DIR"
rm -f "$APPSCAN_TOOLS_DIR/sa.zip" || true
curl -fsSL "$SA_LINUX_URL" -o "$APPSCAN_TOOLS_DIR/sa.zip"
if [ ! -s "$APPSCAN_TOOLS_DIR/sa.zip" ]; then echo "[ERROR] SAClientUtil archive missing or empty"; exit 1; fi
unzip -oq "$APPSCAN_TOOLS_DIR/sa.zip" -d "$APPSCAN_TOOLS_DIR"
APPSCAN_CMD="$(find "$APPSCAN_TOOLS_DIR" -type f -name appscan.sh | head -n 1)"
if [ -z "$APPSCAN_CMD" ]; then echo "[ERROR] appscan.sh not found after extraction"; exit 1; fi
chmod +x "$APPSCAN_CMD"
printf '%s' "$APPSCAN_CMD" > "$APPSCAN_LOG_DIR/appscan.cmd"
'''
            script.writeFile file: "${script.env.APPSCAN_BIN_DIR}/gradle",
                    text: '#!/usr/bin/env bash\nset -euo pipefail\nif [ -x "$WORKSPACE/gradlew" ]; then exec "$WORKSPACE/gradlew" "$@"; fi\necho "gradlew not found" >&2; exit 127\n'
            script.sh 'chmod +x "$APPSCAN_BIN_DIR/gradle"'
        }
        writePropsFile("${script.env.APPSCAN_HOME_DIR}/.appscan")
    }

    private void loadPowershellModules(String resourcePath, String moduleInstallDir = null) {
        script.echo "Load Powershell Modules to fetch proxy password"
        String b64Name = resourcePath.tokenize('/').last()
        String zipName = b64Name.replaceFirst(/\.b64$/, "")
        String zipPath = "${script.env.WORKSPACE}\\${zipName}"

        String psModulesDir = moduleInstallDir ?: "${script.env.WORKSPACE}\\ps-modules"

        String zipBase64 = script.libraryResource(resourcePath)

        script.powershell """
            \$ErrorActionPreference = 'Stop'
     
            \$zipPath = "${zipPath}"
            \$extractDir = Join-Path \$env:WORKSPACE "extracted_modules"
            \$modulesDir = "${psModulesDir}"

            # Avoid base64 decoding by groovy due to sandbox mode restrictons
            \$base64 = @"
${zipBase64}
"@
            [IO.File]::WriteAllBytes(\$zipPath, [Convert]::FromBase64String(\$base64))
     
            if (Test-Path \$extractDir) {
                Remove-Item \$extractDir -Recurse -Force
            }
     
            New-Item -ItemType Directory -Force -Path \$extractDir | Out-Null
            New-Item -ItemType Directory -Force -Path \$modulesDir | Out-Null
     
            Expand-Archive -Path \$zipPath -DestinationPath \$extractDir -Force
     
            Get-ChildItem -Path \$extractDir -Directory | ForEach-Object {
                \$moduleName = \$_.Name
                \$targetModuleDir = Join-Path \$modulesDir \$moduleName
     
                if (Test-Path \$targetModuleDir) {
                    Remove-Item \$targetModuleDir -Recurse -Force
                }
     
                Copy-Item -Path \$_.FullName -Destination \$targetModuleDir -Recurse -Force
            }
     
            Write-Host "PowerShell modules installed to: \$modulesDir"
            Write-Host "Current PSModulePath:"
            Write-Host \$env:PSModulePath
        """
        script.env.PS_MODULES_PATH = psModulesDir
    }

    private void loadCertsFromResources() {
        String targetDir = 'DevSecOpsJenkinsLibrary'

        Map files = [
                'a2a-certs/a2a.sh'             : 'a2a.sh',
                'a2a-certs/get-a2a-password.sh': 'get-a2a-password.sh',
                'a2a-certs/loginfile.sh'       : 'loginfile.sh',
        ]

        if (os.isWindows()) {
            files = files + [
                    'a2a-certs/PROXY_ASOCJenk.exppsw' : 'PROXY_ASOCJenk.exppsw',
                    'a2a-certs/PROXY_ASOCJenk.p12.b64': 'PROXY_ASOCJenk.p12.b64',
                    'a2a-certs/PROXY_ASOCJenk.thumb'  : 'PROXY_ASOCJenk.thumb'
            ]
        } else {
            files = files + [
                    'a2a-certs/PROXY_ASOCJenk.cert.pem': 'PROXY_ASOCJenk.cert.pem',
                    'a2a-certs/PROXY_ASOCJenk.key.pem' : 'PROXY_ASOCJenk.key.pem',
                    'a2a-certs/PROXY_ASOCJenk.keypsw'  : 'PROXY_ASOCJenk.keypsw'
            ]
        }

        if (os.isLinux()) {
            script.sh "mkdir -p ${targetDir}"
        } else {
            script.powershell """
                if (-not (Test-Path '${targetDir}')) {
                    New-Item -ItemType Directory -Path '${targetDir}' | Out-Null
                }
            """
        }

        files.each { resourcePath, targetName ->
            def content = script.libraryResource(resourcePath)
            def targetPath = os.isLinux() ? "${targetDir}/${targetName}" : "${targetDir}\\${targetName}"

            script.writeFile file: targetPath, text: content

            if (os.isLinux()) {
                if (targetName.endsWith('.sh')) {
                    script.sh "chmod +x ${targetPath}"
                } else if (targetName.endsWith('.pem')) {
                    script.sh "chmod 600 ${targetPath}"
                }
            }
        }

        if (os.isWindows())
            loadPowershellModules('ps-modules/safeguard-ps.zip.b64')
    }

    private void writePropsFile(String homeAppscan) {
        def pass = readProxyPassword().replace('\\', '\\\\')
        def content = [
                "server.url=${script.env.APPSCAN_SERVER_URL}",
                "proxy.host=${script.env.PROXY_HOST}",
                "proxy.port=${script.env.PROXY_PORT}",
                "proxy.user=${script.env.PROXY_USER}",
                "proxy.password=${pass}"
        ].join('\n') + '\n'
        os.mkdirP(homeAppscan)
        script.writeFile file: "${homeAppscan}/appscan.properties", text: content
        if (!os.isWindows()) script.sh "chmod 600 '${homeAppscan}/appscan.properties'"
    }

    private String readProxyPassword() {
        return readAppscanLogFile('proxy.pass', true)
    }

    private String appscanCmd() {
        return readAppscanLogFile('appscan.cmd', true)
    }

    private String scanId(String type) {
        return readAppscanLogFile("scan-${type}-${script.env.APPSCAN_SCAN_NAME}.id", false)
    }

    private String readAppscanLogFile(String fileName, boolean required) {
        String path = "${script.env.APPSCAN_LOG_DIR}/${fileName}"
        if (!script.fileExists(path)) {
            if (required) script.error "File not found: ${path}"
            return ''
        }
        return script.readFile(path).trim()
    }

    private String proxyOpts() {
        return [
                "-DBLUEMIX_SERVER=${script.env.APPSCAN_HOST}",
                "-Dhttp.proxyHost=${script.env.PROXY_HOST}", "-Dhttp.proxyPort=${script.env.PROXY_PORT}",
                "-Dhttp.proxyUser=${script.env.PROXY_USER}",
                "-Dhttps.proxyHost=${script.env.PROXY_HOST}", "-Dhttps.proxyPort=${script.env.PROXY_PORT}",
                "-Dhttps.proxyUser=${script.env.PROXY_USER}",
                "-Djava.net.useSystemProxies=false",
                "-Dhttp.auth.preference=Basic",
                "-Djdk.http.auth.tunneling.disabledSchemes=",
                "-Djdk.http.auth.proxying.disabledSchemes="
        ].join(' ')
    }
}
