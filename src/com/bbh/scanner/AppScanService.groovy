package com.bbh.scanner

import com.bbh.build.BuildRunner
import com.bbh.build.BuildService
import com.bbh.build.DotnetBuildRunner
import com.bbh.core.OsHelper
import com.bbh.core.PipelineState
import com.bbh.core.PolicyEngine
import com.bbh.utils.AppScanIssues
import com.bbh.utils.AppScanReportParser
import com.bbh.utils.RestClient
import com.bbh.utils.BuildUtils
import com.bbh.utils.LiquibaseOutput
import com.cloudbees.groovy.cps.NonCPS

class AppScanService implements Serializable {
    private final def script
    private String token
    private long tokenIssuedAt = 0L
    private String proxyPass = ''
    private final PipelineState state
    private final OsHelper os
    private final PolicyEngine policy
    private final BuildService build
    private final AppScanEnvironmentAdapter envAdapter
    private final String shProxyEnv = '''_PP="$(cat "$APPSCAN_LOG_DIR/proxy.pass")"
_OPTS="$APPSCAN_OPTS -Dhttp.proxyPassword=$_PP -Dhttps.proxyPassword=$_PP"'''
    private final long tokenRefreshIntervalMs = 5400000L

    AppScanService(def script, PipelineState state, OsHelper os, PolicyEngine policy, BuildService build) {
        this.script = script
        this.state = state
        this.os = os
        this.policy = policy
        this.build = build
        this.envAdapter = new AppScanEnvironmentAdapter(script, state, os)
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

    void sastLogin() {
        ensureToken('SAST')
    }

    @NonCPS
    void shareSession(AppScanService other) {
        if (other == null) return
        if (other.token && other.tokenIssuedAt >= tokenIssuedAt) {
            token = other.token
            tokenIssuedAt = other.tokenIssuedAt
        }
        if (other.proxyPass) proxyPass = other.proxyPass
    }

    void ensureToken(String type) {
        if (this.token && System.currentTimeMillis() - tokenIssuedAt < tokenRefreshIntervalMs) return
        apiLogin(type)
    }

    String resolveAppId(Map config = null) {
        String id = ((state.cfg?.appId ?: state.cfg?.sast?.appId ?: state.cfg?.asoc?.appId ?: config?.appId ?: '') as String).trim()
        return id
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
        String appId = resolveAppId()
        if (appId) {
            if (state.sastScansByAppId.containsKey(appId)) {
                String primary = (state.sastScansByAppId[appId].primaryProject ?: '') as String
                if (primary && primary != state.currentProjectName) {
                    script.echo "[SAST] ${state.currentProjectName}: shares AppScan application ID '${appId}' with '${primary}' - skipping duplicate IRX generation"
                    return
                }
            }
            state.sastScansByAppId[appId] = [primaryProject: state.currentProjectName, scanName: (script.env.APPSCAN_SCAN_NAME ?: '').trim(), completed: false, counts: null]
        }
        if (!script.env.APPSCAN_SOURCE_DIR_RESOLVED?.trim()) script.error "APPSCAN_SOURCE_DIR_RESOLVED not set"
        boolean database = BuildUtils.databaseBlock(state.cfg)
        boolean doCompile = BuildUtils.booleanValue(state.cfg.asoc?.doCompile, true)
        boolean sourceCodeOnly = BuildUtils.booleanValue(state.cfg.asoc?.sourceCodeOnly, false) || database
        boolean useAppScanConfig = BuildUtils.booleanValue(state.cfg.asoc?.useAppScanConfig, false)
        String scoOption = sourceCodeOnly ? '-sco' : ''
        def tm = timeoutMin ?: (state.cfgDefaults.sast?.prepareTimeoutMin ?: 120) as int
        if (database) databaseSources()
        script.timeout(time: tm, unit: 'MINUTES') {
            script.withEnv([
                    "APPSCAN_CMD_RUNTIME=${appscanCmd()}",
                    "APPSCAN_SOURCE_DIR_RUNTIME=${script.env.APPSCAN_SOURCE_DIR_RESOLVED}",
                    "APPSCAN_SOURCE_FOLDERS=${script.env.APPSCAN_SOURCE_FOLDERS ?: ''}",
                    "APPSCAN_EXCLUDED_FOLDERS=${excludedFolders()}",
                    "APPSCAN_OPTS=${proxyOpts()}"
            ]) {
                if (os.isWindows()) {
                    String tool = build.detectTool()
                    boolean isDotnet = BuildUtils.isDotnetTool(tool)
                    if (doCompile && isDotnet) {
                        Map dotnetOverride = DotnetBuildRunner.toolCfg(state.cfg.asoc as Map, tool)
                        new DotnetBuildRunner(script, os, state.cfg).build(dotnetOverride ?: null, tool)
                    }
                    def appscanPath = windowsAppscanPath()
                    def modulesRaw = (state.cfg?.includedDirs ?: '') as String
                    boolean hasIncluded = modulesRaw.trim() != ''
                    def modules = hasIncluded ? modulesRaw.split(',').collect { "'${it.trim()}'" }.join(',') : ''
                    String xdList = isDotnet
                            ? 'sast_directory .git obj packages TestResults .appscan-tools .appscan-logs .appscan-home .appscan-bin .scannerwork .sonar node_modules DevSecOpsJenkinsLibrary'
                            : 'test test_utils Tests'
                    String copyBlock = hasIncluded ? """
foreach (\$folder in ${modules}){
    \$srcPath = "\$env:APPSCAN_SOURCE_DIR_RUNTIME\\\$folder"
    \$targetPath = "\$sastDirectory\\\$folder"
    if (Test-Path \$srcPath){
        robocopy "\$srcPath" "\$targetPath" /E /XD ${xdList} /MT:16 /NFL /NDL /NJH /NP /NS
    }
}""" : """
robocopy "\$env:APPSCAN_SOURCE_DIR_RUNTIME" "\$sastDirectory" /E /XD ${xdList} /MT:16 /NFL /NDL /NJH /NP /NS
"""
                    String libOption = isDotnet
                            ? (scoOption ? "${scoOption} " : '')
                            : "${scoOption ? scoOption + ' ' : ''}-l \"\$env:APPSCAN_SOURCE_DIR_RUNTIME\\lib;\$env:APPSCAN_SOURCE_DIR_RUNTIME\\modules;\$env:APPSCAN_SOURCE_DIR_RUNTIME\\plugins\""
                    script.powershell """
\$ErrorActionPreference = 'Stop'
\$env:HOME        = \$env:APPSCAN_HOME_DIR
\$env:USERPROFILE = \$env:APPSCAN_HOME_DIR
Remove-Item -Force "\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx" -ErrorAction SilentlyContinue

\$sastDirectory = "\$env:WORKSPACE\\sast_directory"
if (Test-Path -Path \$sastDirectory) {Remove-Item -Path \$sastDirectory -Recurse -Force }
New-Item -ItemType Directory -Path \$sastDirectory | Out-Null
${copyBlock}
\$csCount = (Get-ChildItem -Path \$sastDirectory -Recurse -Filter '*.cs' -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Host "[IRX] .NET C# source files staged: \$csCount"
Set-Location -Path \$sastDirectory
& ${appscanPath} prepare -s thorough -n "\$env:APPSCAN_SCAN_NAME" -d "\$env:WORKSPACE" ${libOption} 2>&1 |
    Out-File -FilePath "\$env:APPSCAN_LOG_DIR/prepare.log" -Encoding UTF8
if (-not (Test-Path "\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx")) { Write-Error "[ERROR] IRX not found after prepare"; exit 1 }
Write-Host "[INFO] IRX size: \$((Get-Item \"\$env:WORKSPACE/\$env:APPSCAN_SCAN_NAME.irx\").Length) bytes"
Set-Location -Path "\$env:WORKSPACE"
"""
                } else {
                    if (doCompile) {
                        def tool = build.detectTool()
                        if (tool == 'liquibase') {
                            Map liqCompileCfg = (state.cfg.asoc?.liquibase ?: state.cfg.build?.liquibase ?: [:]) as Map
                            if (build.hasLiquibaseStep(liqCompileCfg)) {
                                build.runLiquibaseCommand(liqCompileCfg, ['validate'], 'Liquibase SAST')
                            }
                        } else if (BuildUtils.isDotnetTool(tool as String)) {
                            Map dotnetOverride = DotnetBuildRunner.toolCfg(state.cfg.asoc as Map, tool as String)
                            new DotnetBuildRunner(script, os, state.cfg).build(dotnetOverride ?: null, tool as String)
                        } else {
                            Map compileCfg = (state.cfg.asoc?.get(tool) ? state.cfg.asoc : state.cfg.build) as Map
                            BuildRunner runner = new BuildRunner(script, compileCfg, tool, BuildUtils.pluginRepositories(state.cfg, state.cfgDefaults))
                            if (!runner.ifExist()) {
                                script.error "[SAST] Build tool is NOT installed on an agent"
                            }
                            runner.run()
                            Map liqCompileCfg = (state.cfg.asoc?.liquibase ?: state.cfg.build?.liquibase ?: [:]) as Map
                            if (build.hasLiquibaseStep(liqCompileCfg)) {
                                build.runLiquibaseCommand(liqCompileCfg, ['validate'], 'Liquibase SAST')
                            }
                        }
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
        String appId = resolveAppId(config)
        if (appId && state.sastScansByAppId.containsKey(appId)) {
            Map scanInfo = (state.sastScansByAppId[appId] ?: [:]) as Map
            String primary = (scanInfo.primaryProject ?: '') as String
            if (primary && primary != state.currentProjectName) {
                String existingScanId = (scanInfo.scanId ?: '') as String
                if (existingScanId) {
                    script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/scan-sast-${script.env.APPSCAN_SCAN_NAME}.id", text: existingScanId
                    recordSastConsoleLink(existingScanId, appId)
                }
                script.echo "[SAST] ${state.currentProjectName}: shares AppScan application ID '${appId}' with '${primary}' - reusing scan ID '${existingScanId}'"
                return
            }
        }
        ensureToken('SAST')
        if (!appId) script.error "[APPSCAN] appId not configured for the service in ${state.settingsPlace()}"
        String filePath = "${script.env.WORKSPACE}/${script.env.APPSCAN_SCAN_NAME ?: 'my-project'}.irx"
        if (!script.fileExists(filePath)) script.error "[APPSCAN] File not found: ${filePath}"

        script.echo "[SAST] Uploading analysis file: ${filePath}"
        String uploadResp = (callApi(curlCommand(acceptHeaders() + ["-F \"fileToUpload=@${filePath}\""], appscanApiUrl('FileUpload')), true) as String).trim()
        def fileId = readApiJson(uploadResp)?.FileId
        if (!fileId) script.error("[SAST] Unable to upload file to AppScan: ${RestClient.abbreviate(uploadResp, 300)}")
        script.echo "[SAST] File uploaded successfully. FileId: ${fileId}"

        String defaultName = "${script.env.APPSCAN_SCAN_NAME ?: 'sast-scan'}-sast"
        String scanName = ((state.cfg.sast?.scanName ?: '') as String).trim()
        String finalScanName = scanName ? BuildUtils.scanName(scanName) : defaultName
        script.writeFile(file: 'sast_payload.json',
                text: RestClient.toJson([FileId: fileId as String, ScanName: finalScanName, AppId: appId, ClientType: 'api', FullyAutomatic: true]))
        String response = (callApi(curlCommand(jsonHeaders() + ['-d @sast_payload.json'], appscanApiUrl('Scans/Sast')), true) as String).trim()
        String scanId = (readApiJson(response)?.Id ?: '') as String
        if (!scanId) script.error("[SAST] Error when starting SAST scan: ${RestClient.abbreviate(response, 300)}")
        script.writeFile file: "${script.env.APPSCAN_LOG_DIR}/scan-sast-${script.env.APPSCAN_SCAN_NAME}.id", text: scanId
        script.echo "[SAST] SAST scan queued. Scan ID: ${scanId}"
        recordSastConsoleLink(scanId, appId)
        if (appId) {
            Map record = (state.sastScansByAppId[appId] ?: [:]) as Map
            record.primaryProject = state.currentProjectName
            record.scanId = scanId
            record.scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
            record.completed = false
            record.counts = null
            state.sastScansByAppId[appId] = record
        }
    }

    private void databaseSources() {
        String name = state.currentProjectName
        script.echo "[SAST] ${name}: SQL and PL/SQL sources - the scan has no compile step"
        String listed = script.sh(returnStdout: true, label: "DB: list the files AppScan does not scan for ${name}",
                script: LiquibaseOutput.unscannedScript((state.cfg.sourceDir ?: '.') as String)) as String
        String text = LiquibaseOutput.unscanned(name, listed)
        if (text) script.echo text
    }

    void removeStagedSources() {
        if (os.isWindows()) {
            script.powershell 'Remove-Item -Path "$env:WORKSPACE\\sast_directory" -Recurse -Force -ErrorAction SilentlyContinue'
        } else {
            script.sh 'rm -rf "$WORKSPACE/appscan-source-staged"'
        }
    }

    void waitSast() {
        String appId = resolveAppId()
        if (appId && state.sastScansByAppId.containsKey(appId)) {
            Map scanInfo = (state.sastScansByAppId[appId] ?: [:]) as Map
            if (scanInfo.completed) {
                script.echo "[SAST] ${state.currentProjectName}: scan for application ID '${appId}' already completed - skipping wait"
                return
            }
        }
        String scanId = scanId('sast')
        if (!scanId || scanId == 'NONE') script.error "[SAST] No scan ID available"
        sastWait(scanId, (state.cfgDefaults.sast?.pollTimeoutMin ?: 50) as int, (state.cfgDefaults.sast?.pollIntervalSec ?: 30) as int)
        if (appId && state.sastScansByAppId.containsKey(appId)) {
            state.sastScansByAppId[appId].completed = true
        }
    }

    void sastWait(String scanId, int timeoutMin, int intervalSec) {
        script.echo "[SAST] Waiting for SAST scan: ${scanId}"
        script.timeout(time: timeoutMin, unit: 'MINUTES') {
            while (true) {
                ensureToken('SAST')
                String response = (callApi(curlCommand(acceptHeaders(), appscanApiUrl("Scans/Sast/${scanId}")), true) as String).trim()
                String status = (readApiJson(response)?.LatestExecution?.Status ?: 'Unknown') as String
                script.echo "[SAST] SAST scan status: ${status}"
                if (status == 'Ready') {
                    script.echo '[SAST] SAST scan finished with success.'
                    break
                }
                if (status == 'Failed') {
                    script.error("[SAST] SAST scan finished with failure: ${RestClient.abbreviate(response, 300)}")
                }
                script.sleep(time: intervalSec, unit: 'SECONDS')
            }
        }
    }

    void downloadSastReports() {
        String appId = resolveAppId()
        if (appId && state.sastScansByAppId.containsKey(appId)) {
            Map scanInfo = (state.sastScansByAppId[appId] ?: [:]) as Map
            String primary = (scanInfo.primaryProject ?: '') as String
            String primaryScanName = (scanInfo.scanName ?: '') as String
            if (primary && primary != state.currentProjectName) {
                String scanName = script.env.APPSCAN_SCAN_NAME
                copyReportFile("${script.env.WORKSPACE}/appscan-report-${primaryScanName}.html", "${script.env.WORKSPACE}/appscan-report-${scanName}.html")
                String srcPdf = "${script.env.WORKSPACE}/appscan-report-${primaryScanName}.pdf"
                if (script.fileExists(srcPdf)) {
                    copyReportFile(srcPdf, "${script.env.WORKSPACE}/appscan-report-${scanName}.pdf")
                    state.recordScanArtifact('sast', 'pdf', "appscan-report-${scanName}.pdf")
                }
                Map counts = (scanInfo.counts ?: [critical: 0, high: 0, medium: 0, low: 0]) as Map
                state.vulnCounts.sast = counts
                state.recordVulns('sast', counts)
                if (state.projectsSastFindings.containsKey(primary)) state.projectsSastFindings[state.currentProjectName] = state.projectsSastFindings[primary]
                script.echo "[SAST] ${state.currentProjectName}: shares AppScan application ID '${appId}' with '${primary}' - reusing report and findings"
                return
            }
        }
        String scanId = scanId('sast')
        if (!scanId || scanId == 'NONE') script.error "[SAST] No scan ID available"
        sastDownloadReport(scanId)
        collectSastFindings(scanId)
        def dest = "${script.env.WORKSPACE}/appscan-report-${script.env.APPSCAN_SCAN_NAME}.html"
        if (script.fileExists(dest)) {
            def counts = parseHtmlCounts(dest, 'sast')
            state.vulnCounts.sast = counts
            state.recordVulns('sast', counts)
            if (appId && state.sastScansByAppId.containsKey(appId)) {
                state.sastScansByAppId[appId].counts = counts
            }
        } else {
            state.vulnCounts.sast = [critical: 0, high: 0, medium: 0, low: 0]
            script.echo "[SAST] HTML report not found - vulnerability counts remain 0"
        }
    }

    void collectSastFindings(String scanId) {
        String project = state.currentProjectName
        try {
            List issues = fetchScanIssues(scanId)
            if (issues == null) {
                script.echo "[WARN] [SAST] ${project}: the AppScan API returned no list of issues for scan ${scanId} - the pull request comments fall back to the findings of the HTML report"
                return
            }
            List<Map> findings = AppScanIssues.findings(issues)
            List<Map> requests = AppScanIssues.advisoryRequests(findings, AppScanIssues.maxAdvisories())
            Map advisories = [:]
            for (int i = 0; i < requests.size(); i++) {
                Map request = requests[i] as Map
                ensureToken('SAST')
                String body = (callApi(curlCommand([bearerHeader(), '-H "Accept: application/xml"'],
                        appscanApiUrl("Issues/${request.id}/Details?format=xml&locale=en-US")), true) ?: '') as String
                String fix = AppScanIssues.fixRecommendation(body)
                if (fix) advisories[request.typeKey as String] = fix
            }
            int withFix = AppScanIssues.applyAdvisories(findings, advisories)
            state.projectsSastFindings[project] = [source: 'api', scanId: scanId, findings: findings]
            script.echo "[SAST] ${project}: ${findings.size()} open finding(s) read from the AppScan API (Issues/Scan/${scanId}); the recommended fix of ${advisories.size()} of ${requests.size()} issue type(s) read from Issues/{id}/Details covers ${withFix} finding(s)"
        } catch (Throwable t) {
            if (t.getClass().getName().endsWith('FlowInterruptedException')) throw t
            script.echo "[WARN] [SAST] ${project}: the findings could not be read from the AppScan API (${t.message}) - the pull request comments fall back to the findings of the HTML report"
        }
    }

    List fetchScanIssues(String scanId) {
        List all = []
        int size = AppScanIssues.pageSize()
        String dollar = os.isWindows() ? '$' : '\\$'
        for (int skip = 0; skip < AppScanIssues.maxIssues(); skip += size) {
            ensureToken('SAST')
            String response = ((callApi(curlCommand(acceptHeaders(),
                    appscanApiUrl("Issues/Scan/${scanId}?applyPolicies=None&${dollar}top=${size}&${dollar}skip=${skip}")), true) ?: '') as String).trim()
            List page = AppScanIssues.items(readApiJson(response))
            if (page == null) {
                if (skip == 0) {
                    script.echo "[SAST] AppScan API Issues/Scan/${scanId}: ${RestClient.abbreviate(response, 300)}"
                    return null
                }
                break
            }
            all.addAll(page)
            if (page.size() < size) break
        }
        return all
    }

    void sastDownloadReport(String scanId) {
        Map html = downloadSecurityReport('SAST', scanId, 'Html', 'appscan-report.html')
        Map pdf  = downloadSecurityReport('SAST', scanId, 'Pdf', 'appscan-report.pdf')
        if (!pdf.ok) script.echo "[SAST] PDF report unavailable: ${pdf.reason} - the HTML report is still linked"
        if (!html.ok) {
            script.error("[SAST] The HTML report could not be produced, so the findings of this scan are unknown: ${html.reason}")
        }
        renameSastReport()
        String pdfName = "appscan-report-${script.env.APPSCAN_SCAN_NAME}.pdf"
        if (script.fileExists("${script.env.WORKSPACE}/${pdfName}")) {
            state.recordScanArtifact('sast', 'pdf', pdfName)
        }
    }

    void renameSastReport() {
        def scanName = (script.env.APPSCAN_SCAN_NAME ?: '').trim()
        if (!scanName) return
        renameReportFile("${script.env.WORKSPACE}/appscan-report.html", "${script.env.WORKSPACE}/appscan-report-${scanName}.html")
        renameReportFile("${script.env.WORKSPACE}/appscan-report.pdf", "${script.env.WORKSPACE}/appscan-report-${scanName}.pdf")
    }

    private void recordSastConsoleLink(String scanId, String appId) {
        String base = ((state.cfg.asoc?.url ?: script.env.APPSCAN_SERVER_URL ?: '') as String).trim().replaceAll('/+$', '')
        if (!base || !appId) return
        String url = scanId ? "${base}/main/myapps/${appId}/scans/${scanId}" : "${base}/main/myapps/${appId}/scans"
        state.recordScanArtifact('sast', 'hcl', url)
    }

    void dastScan() {
        def cfgDast = state.cfg.dast ?: [:]
        if (BuildUtils.databaseBlock(state.cfg) && !(cfgDast.enabled as boolean)) {
            script.echo "[DAST] ${state.currentProjectName}: a SQL and PL/SQL service has no web application to scan - DAST is not required"
            state.recordScan('dast', 'NOT_REQUIRED')
            state.recordVulns('dast', [critical: 0, high: 0, medium: 0, low: 0])
            state.projectNotRequired()
            if (state.policyStatus['dast'] == 'SKIP') state.policyStatus['dast'] = 'NOT_REQUIRED'
            return
        }
        if (!(cfgDast.enabled as boolean)) {
            script.echo "[DAST] Disabled in config - not required for this project."
            state.stageResults['DAST - Dynamic Application Security Tests - HCL AppScan'] = 'NOT_REQUIRED'
            state.policyStatus['dast'] = 'NOT_REQUIRED'
            return
        }
        ensureToken('DAST')
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

    void apiLogin(String type = 'DAST') {
        script.echo "[${type}] Starting API Authentication"
        String keyId = BuildUtils.appScanKeyId(state.cfgDefaults)
        if (!keyId) {
            script.error "[${type}] asoc.keyId is not set in resources/${BuildUtils.defaultsResource()} of the library: the DevSecOps team sets there the Key ID of the AppScan on Cloud API key of the functional user"
        }
        String tmpDir = (script.env.WORKSPACE_TMP ?: "${script.env.WORKSPACE}@tmp") as String
        String bodyFile = "${tmpDir}/asoc-api-key-login.json"
        String response = ''
        script.withCredentials([script.string(credentialsId: BuildUtils.appScanKeySecretCredentialsId(state.cfgDefaults), variable: 'APPSCAN_KEY_SECRET')]) {
            try {
                script.writeFile(file: bodyFile, text: RestClient.toJson([KeyId: keyId, KeySecret: script.env.APPSCAN_KEY_SECRET as String]))
                response = (callApi(curlCommand(['-H "Content-Type: application/json"', "-d \"@${bodyFile}\""], appscanApiUrl('Account/ApiKeyLogin')), true) as String).trim()
            } finally {
                removeFile(bodyFile)
            }
        }

        this.token = (readApiJson(response)?.Token ?: '') as String
        if (!this.token) {
            script.error("[${type}] Unable to fetch Bearer Token: ${RestClient.abbreviate(response, 300)}")
        }
        tokenIssuedAt = System.currentTimeMillis()
        script.echo "[${type}] Authenticated successfully."
    }

    private def callApi(String command, boolean returnStdout) {
        if (os.isWindows()) {
            return script.bat(script: "@${command.replaceFirst(/^curl /, 'curl.exe ')}", returnStdout: returnStdout)
        }
        return script.sh(script: "set +x\n${command}", returnStdout: returnStdout)
    }

    private def readApiJson(String text) {
        if (!text) return null
        try {
            return script.readJSON(text: text)
        } catch (Exception e) {
            if (e.getClass().getName().endsWith('FlowInterruptedException')) throw e
            return null
        }
    }

    private void removeFile(String path) {
        if (os.isWindows()) script.powershell "Remove-Item -Force -ErrorAction SilentlyContinue -Path '${BuildUtils.psSingleQuote(path)}'"
        else script.sh "rm -f '${BuildUtils.escapeForSingleQuotes(path)}'"
    }

    String dastStartScan() {
        script.echo "[DAST] Sending execute DAST scan request."

        def appId = state.cfg.appId?.trim()
        def targetUrl = state.cfg.dast?.targetUrl?.trim()
        def presenceId =  state.cfg.dast?.presenceId ? state.cfg.dast.presenceId.trim() : ''

        if (!appId) script.error("[DAST] appId not configured for the service in ${state.settingsPlace()}")
        if (!targetUrl) script.error("[DAST] dast.targetUrl not configured for the service in ${state.settingsPlace()}")

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

        String response = (callApi(curlCommand(jsonHeaders() + ['-d @dast_payload.json'], appscanApiUrl('Scans/Dast')), true) as String).trim()
        def scanId = readApiJson(response)?.Id

        if (!scanId) {
            script.error("[DAST] Error when starting DAST scan: ${response}")
        }

        script.echo "[DAST] DAST scan started. Scan ID: ${scanId}"
        return scanId
    }

    void dastWait(String scanId, int timeoutMin, int intervalSec) {
        script.echo "[DAST] Waiting for DAST scan: ${scanId}"
        script.timeout(time: timeoutMin, unit: 'MINUTES') {
            while (true) {
                ensureToken('DAST')
                String response = (callApi(curlCommand(acceptHeaders(), appscanApiUrl("Scans/Dast/${scanId}")), true) as String).trim()
                String status = (readApiJson(response)?.LatestExecution?.Status ?: 'Unknown') as String
                script.echo "[DAST] DAST scan status: ${status}"
                if (status == 'Ready') {
                    script.echo '[DAST] DAST scan finished with success.'
                    break
                }
                if (status == 'Failed') {
                    script.error("[DAST] DAST scan finished with failure: ${RestClient.abbreviate(response, 300)}")
                }
                script.sleep(time: intervalSec, unit: 'SECONDS')
            }
        }
    }

    void dastDownloadReport(String scanId) {
        Map html = downloadSecurityReport('DAST', scanId, 'Html', 'appscan-dast-report.html')
        Map pdf  = downloadSecurityReport('DAST', scanId, 'Pdf', 'appscan-dast-report.pdf')
        if (!pdf.ok) script.echo "[DAST] PDF report unavailable: ${pdf.reason} - the HTML report is still linked"
        if (!html.ok) {
            script.error("[DAST] The HTML report could not be produced, so the findings of this scan are unknown: ${html.reason}")
        }
    }

    Map downloadSecurityReport(String type, String scanId, String fileType, String outFile) {
        String key = type.toLowerCase()
        script.echo "[${type}] Generating & Downloading ${fileType.toUpperCase()} report"
        int timeoutMin  = (state.cfgDefaults[key]?.reportTimeoutMin ?: 30) as int
        int intervalSec = (state.cfgDefaults[key]?.reportIntervalSec ?: 30) as int
        String payloadFile = "${key}_report_payload.json"

        script.writeFile(file: payloadFile,
                text: """{"Configuration":{"ReportFileType":"${fileType}","Summary":true,"Details":true,"Overview":true,"TableOfContent":true,"IncludeNoise":false}}""")

        ensureToken(type)
        String response = (callApi(curlCommand(jsonHeaders() + ["-d @${payloadFile}".toString()], appscanApiUrl("Reports/Security/Scan/${scanId}")), true) as String).trim()
        def reportId = readApiJson(response)?.Id
        if (!reportId) {
            return [ok: false, reason: "AppScan did not accept the ${fileType.toUpperCase()} report request: ${RestClient.abbreviate(response, 300)}".toString()]
        }
        script.echo "[${type}] Generating report ID: ${reportId}"
        return fetchRenderedReport(type, reportId as String, fileType, outFile, timeoutMin, intervalSec)
    }

    Map fetchRenderedReport(String type, String reportId, String fileType, String outFile, int timeoutMin, int intervalSec) {
        long deadline = System.currentTimeMillis() + (timeoutMin * 60000L)
        int seconds = intervalSec < 1 ? 1 : intervalSec
        int maxAttempts = ((timeoutMin * 60) / seconds) as int
        if (maxAttempts < 1) maxAttempts = 1
        int attempt = 0
        String reason = 'the report was never rendered'
        while (attempt < maxAttempts && System.currentTimeMillis() < deadline) {
            attempt++
            ensureToken(type)
            String status = reportStatus(reportId)
            if (status == 'Failed') {
                return [ok: false, reason: "AppScan reported the ${fileType.toUpperCase()} report as Failed".toString()]
            }
            if (status == 'Ready' || status == 'Unknown') {
                callApi(curlCommand([bearerHeader(), "-o \"${outFile}\""], appscanApiUrl("Reports/${reportId}/Download")), false)
                Map check = validateReportFile(outFile, fileType)
                if (check.ok) {
                    script.echo "[${type}] ${type} report saved as ${outFile} after ${attempt} attempt(s)"
                    return [ok: true, reason: '']
                }
                reason = check.reason as String
            } else {
                reason = "AppScan reports the ${fileType.toUpperCase()} report as ${status}".toString()
            }
            if (attempt >= maxAttempts) break
            script.echo "[${type}] ${fileType.toUpperCase()} report not ready yet (${reason}) - waiting ${seconds}s, attempt ${attempt} of ${maxAttempts}"
            script.sleep(time: seconds, unit: 'SECONDS')
        }
        return [ok: false, reason: "${reason}, gave up after ${attempt} attempt(s) over at most ${timeoutMin} minute(s)".toString()]
    }

    String reportStatus(String reportId) {
        try {
            String body = (callApi(curlCommand(acceptHeaders(), appscanApiUrl("Reports/${reportId}")), true) as String).trim()
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

    private void copyReportFile(String src, String dst) {
        if (os.isWindows()) script.powershell "if (Test-Path '${src}') { Copy-Item '${src}' '${dst}' -Force }"
        else script.sh "[ -f '${src}' ] && cp '${src}' '${dst}' || true"
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
        parts << "-U \"${script.env.PROXY_USER}:${proxyPassword()}\""
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

    private void fetchProxyPassword() {
        envAdapter.fetchProxyPassword()
    }

    void createDirs() {
        envAdapter.createDirs()
    }

    private void loadCertsFromResources() {
        envAdapter.loadCertsFromResources()
    }

    private String readProxyPassword() {
        return envAdapter.readProxyPassword()
    }

    private String proxyPassword() {
        if (script.env.PROXY_PASS) return script.env.PROXY_PASS as String
        if (!proxyPass) proxyPass = readAppscanLogFile('proxy.pass', false)
        return proxyPass ?: 'null'
    }

    private String excludedFolders() {
        return envAdapter.excludedFolders()
    }

    private String windowsAppscanPath() {
        return envAdapter.windowsAppscanPath()
    }

    private String appscanCmd() {
        return envAdapter.appscanCmd()
    }

    private String scanId(String type) {
        return envAdapter.scanId(type)
    }

    private String readAppscanLogFile(String fileName, boolean required) {
        return envAdapter.readAppscanLogFile(fileName, required)
    }

    private String proxyOpts() {
        return envAdapter.proxyOpts()
    }
}
