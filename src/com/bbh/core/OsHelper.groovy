package com.bbh.core

class OsHelper implements Serializable {
    private final def script
    private String osType = 'linux'

    OsHelper(def script) { this.script = script }

    void detect() {
        if (!script.isUnix()) { osType = 'windows'; return }
        def uname = script.sh(returnStdout: true, script: 'uname -s 2>/dev/null || echo Linux').trim().toLowerCase()
        osType = uname.contains('darwin') ? 'mac' : 'linux'
    }

    boolean isWindows() { osType == 'windows' }
    boolean isLinux()   { osType == 'linux' }
    String  getType()   { osType }

    def run(String cmd) {
        if (isWindows()) script.powershell(cmd) else script.sh(cmd)
    }

    void chmodX(String path) {
        if (!isWindows()) script.sh "chmod +x '${path}' 2>/dev/null || true"
    }

    void mkdirP(String path) {
        if (isWindows()) script.powershell "New-Item -ItemType Directory -Force -Path '${path}' | Out-Null"
        else             script.sh "mkdir -p '${path}'"
    }
}