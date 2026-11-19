package com.bbh.scanner

import com.bbh.core.OsHelper
import com.bbh.core.PipelineState

class AppScanEnvironmentAdapter implements Serializable {

    private final def script
    private final PipelineState state
    private final OsHelper os

    AppScanEnvironmentAdapter(def script, PipelineState state, OsHelper os) {
        this.script = script
        this.state = state
        this.os = os
    }

    void fetchProxyPassword() {
        String oisHost = (state.platform?.oisHost ?: 'oisapi.bbh.com') as String
        if (!(oisHost ==~ /[A-Za-z0-9.-]+/)) script.error "[APPSCAN] platform.oisHost '${oisHost}' of the DevSecOps portal is not a host name"
        if (os.isWindows()) {
            script.powershell('''
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
                    
            '''.replace('oisapi.bbh.com', oisHost))

        } else {
            script.sh('''#!/usr/bin/env bash
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
'''.replace('oisapi.bbh.com', oisHost))
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

    void loadPowershellModules(String resourcePath, String moduleInstallDir = null) {
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

    void loadCertsFromResources() {
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

        if (os.isWindows()) {
            loadPowershellModules('ps-modules/safeguard-ps.zip.b64')
        }
    }

    void writePropsFile(String homeAppscan) {
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

    String readProxyPassword() {
        return readAppscanLogFile('proxy.pass', true)
    }

    String excludedFolders() {
        String excluded = (script.env.APPSCAN_EXCLUDED_FOLDERS ?: '') as String
        List tokens = excluded.tokenize(',').collect { it.trim() }
        List extra = []
        boolean free = !script.env.APPSCAN_SOURCE_FOLDERS
        if (free && state.stageResults.containsKey('Node.js build') && !tokens.contains('node_modules')) extra << 'node_modules'
        if (free && state.projectsDatabase && !tokens.contains('.dso-db')) extra << '.dso-db'
        return ([excluded] + extra).findAll { it }.join(',')
    }

    String windowsAppscanPath() {
        String configured = ((state.cfg?.appscanPath ?: '') as String).trim()
        if (configured) {
            if (configured.contains(':') || configured.startsWith('\\\\')) return configured
            String base = ((script.env.WORKSPACE ?: script.pwd()) as String).replace('\\', '/')
            return "${base}${configured.startsWith('/') ? configured : '/' + configured}"
        }
        return (script.env.APPSCAN_CMD_RUNTIME ?: appscanCmd() ?: 'appscan.bat') as String
    }

    String appscanCmd() {
        return readAppscanLogFile('appscan.cmd', true)
    }

    String scanId(String type) {
        return readAppscanLogFile("scan-${type}-${script.env.APPSCAN_SCAN_NAME}.id", false)
    }

    String readAppscanLogFile(String fileName, boolean required) {
        String path = "${script.env.APPSCAN_LOG_DIR}/${fileName}"
        if (!script.fileExists(path)) {
            if (required) script.error "File not found: ${path}"
            return ''
        }
        return script.readFile(path).trim()
    }

    String proxyOpts() {
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
