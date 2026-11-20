package com.bbh.scm

import com.bbh.remediation.port.SourceRepository
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class GitSourceRepository implements SourceRepository {

    private final def script
    private final def state

    GitSourceRepository(def script, def state = null) {
        this.script = script
        this.state = state
    }

    String prepareWorkingCopy(String branch) {
        String root = repoRootFor()
        String ws = workspaceRoot()
        String base = "${script.env.WORKSPACE_TMP ?: ws + '@tmp'}/goldenfix"
        String dir  = "${base}/${branch}"
        if (!script.isUnix()) {
            script.powershell(label: "GoldenFix: prepare worktree ${branch}", script: """
\$ErrorActionPreference = 'Stop'
Set-Location -Path '${psq(root)}'
git worktree prune 2>\$null
if (Test-Path '${psq(dir)}') { Remove-Item -Path '${psq(dir)}' -Recurse -Force }
New-Item -ItemType Directory -Force -Path '${psq(base)}' | Out-Null
git show-ref --verify --quiet 'refs/heads/${psq(branch)}'
if (\$LASTEXITCODE -eq 0) {
  git worktree add --force '${psq(dir)}' '${psq(branch)}'
} else {
  git worktree add -b '${psq(branch)}' '${psq(dir)}' HEAD
}
if (\$LASTEXITCODE) { exit \$LASTEXITCODE }
""")
            return dir
        }
        script.sh(label: "GoldenFix: prepare worktree ${branch}", script: """#!/bin/bash
set -euo pipefail
cd '${q(root)}'
git worktree prune || true
rm -rf '${q(dir)}'
mkdir -p '${q(base)}'
if git show-ref --verify --quiet 'refs/heads/${q(branch)}'; then
  git worktree add --force '${q(dir)}' '${q(branch)}'
else
  git worktree add -b '${q(branch)}' '${q(dir)}' HEAD
fi
""")
        return dir
    }

    List<String> findFiles(String dir, List<String> patterns, List<String> excludeDirs) {
        if (!patterns) return []
        if (!script.isUnix()) {
            String psPatterns = patterns.collect { "'${psq(it as String)}'" }.join(', ')
            String psExcluded = (excludeDirs ?: []).collect { "'${psq(it as String)}'" }.join(', ')
            String out = script.powershell(label: 'GoldenFix: find dependency manifests', returnStdout: true, script: """
\$ErrorActionPreference = 'Stop'
Set-Location -Path '${psq(dir)}'
\$rootLen = (Get-Location).Path.Length
\$pats = @(${psPatterns})
\$excl = @(${psExcluded})
\$found = Get-ChildItem -Recurse -File | ForEach-Object {
    \$rel = \$_.FullName.Substring(\$rootLen).Replace('\\', '/').TrimStart('/')
    \$segs = \$rel.Split('/')
    \$skip = \$false
    if (\$segs.Length -gt 1) {
        for (\$i = 0; \$i -lt \$segs.Length - 1; \$i++) {
            if (\$excl -contains \$segs[\$i]) { \$skip = \$true; break }
        }
    }
    if (-not \$skip) {
        foreach (\$p in \$pats) {
            \$rx = '^' + [Regex]::Escape(\$p.Replace('\\', '/')).Replace('\\*', '.*').Replace('\\?', '.') + '\$'
            \$target = if (\$p -match '/') { \$rel } else { \$_.Name }
            if (\$target -match \$rx) { \$rel; break }
        }
    }
}
\$found | Sort-Object -Unique
""") as String
            return nonEmptyLines(out)
        }
        String out = script.sh(label: 'GoldenFix: find dependency manifests', returnStdout: true, script: """#!/bin/bash
set -euo pipefail
cd '${q(dir)}'
${findCommand(patterns, excludeDirs)} | sed 's|^\\./||' | LC_ALL=C sort
""")
        return nonEmptyLines(out)
    }

    String readText(String dir, String relativePath) {
        return script.readFile(file: "${dir}/${relativePath}", encoding: 'UTF-8')
    }

    void writeText(String dir, String relativePath, String content) {
        script.writeFile(file: "${dir}/${relativePath}", text: content, encoding: 'UTF-8')
    }

    String commit(String dir, List<String> files, String message, Map author) {
        if (!files) return ''
        String messageFile = "${dir}.commit-msg"
        String name  = (author?.name  ?: 'DevSecOps GoldenFix') as String
        String email = (author?.email ?: 'devsecops-goldenfix@noreply.local') as String
        script.writeFile(file: messageFile, text: message, encoding: 'UTF-8')
        if (!script.isUnix()) {
            String psFiles = files.collect { "'${psq(it as String)}'" }.join(' ')
            return (script.powershell(label: 'GoldenFix: commit changes', returnStdout: true, script: """
\$ErrorActionPreference = 'Stop'
Set-Location -Path '${psq(dir)}'
git add -- ${psFiles}
git diff --cached --quiet
if (\$LASTEXITCODE -ne 0) {
  git -c "user.name=${psq(name)}" -c "user.email=${psq(email)}" commit -q -F '${psq(messageFile)}'
  if (\$LASTEXITCODE) { exit \$LASTEXITCODE }
  git rev-parse HEAD
}
Remove-Item -Force '${psq(messageFile)}' -ErrorAction SilentlyContinue
""") as String).trim()
        }
        return script.sh(label: 'GoldenFix: commit changes', returnStdout: true, script: """#!/bin/bash
set -euo pipefail
cd '${q(dir)}'
git add -- ${quoteAll(files)}
if ! git diff --cached --quiet; then
  git -c user.name='${q(name)}' -c user.email='${q(email)}' commit -q -F '${q(messageFile)}'
  git rev-parse HEAD
fi
rm -f '${q(messageFile)}'
""").trim()
    }

    void push(String dir, String branch, Map pushCfg) {
        String url = (pushCfg?.url ?: '') as String
        if (!url) {
            if (!script.isUnix()) {
                url = (script.powershell(returnStdout: true, script: "git -C '${psq(dir)}' remote get-url origin") as String).trim()
            } else {
                url = script.sh(returnStdout: true, script: "git -C '${q(dir)}' remote get-url origin").trim()
            }
        }
        String credentialsId = (pushCfg?.credentialsId ?: '') as String
        script.echo "[GOLDENFIX] Pushing branch ${branch} to ${sanitizeUrl(url)}"
        if (!script.isUnix()) {
            pushWindows(dir, branch, url, credentialsId, pushCfg)
            return
        }
        String target = "push '${q(url)}' 'HEAD:refs/heads/${q(branch)}'"

        int status = 0
        if (isSshUrl(url) && credentialsId) {
            script.sshagent([credentialsId]) { status = runGit(dir, '', target, '') }
        } else if (isSshUrl(url) || !credentialsId) {
            status = runGit(dir, '', target, '')
        } else if (((pushCfg.authType ?: 'basic') as String).toLowerCase() in ['bearer', 'token']) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'GF_GIT_TOKEN')]) {
                status = pushCfg.cloud
                        ? runGit(dir, credentialHelper('x-token-auth', '${GF_GIT_TOKEN}'), target, '')
                        : runGit(dir, '', target, bearerHeaderEnv('GF_GIT_TOKEN'))
            }
        } else {
            script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'GF_GIT_USER', passwordVariable: 'GF_GIT_PASS')]) {
                status = runGit(dir, credentialHelper('${GF_GIT_USER}', '${GF_GIT_PASS}'), target, '')
            }
        }
        if (status == 0) return
        String output = script.sh(label: 'GoldenFix: read the git push output', returnStdout: true,
                script: "cat '${q(dir)}.push.log' 2>/dev/null || true; rm -f '${q(dir)}.push.log'") as String
        script.error(pushFailure(branch, sanitizeUrl(url), status, output))
    }

    private void pushWindows(String dir, String branch, String url, String credentialsId, Map pushCfg) {
        String psCmd = "git -C '${psq(dir)}' push '${psq(url)}' 'HEAD:refs/heads/${psq(branch)}'"
        int status = 0
        if (isSshUrl(url) && credentialsId) {
            script.sshagent([credentialsId]) { status = runGitWindows(dir, psCmd, '') }
        } else if (isSshUrl(url) || !credentialsId) {
            status = runGitWindows(dir, psCmd, '')
        } else if (((pushCfg.authType ?: 'basic') as String).toLowerCase() in ['bearer', 'token']) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'GF_GIT_TOKEN')]) {
                String hdrScript = pushCfg.cloud
                        ? "\$pair = \"x-token-auth:\$env:GF_GIT_TOKEN\"\n\$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(\$pair))\n\$env:GIT_CONFIG_COUNT='1'; \$env:GIT_CONFIG_KEY_0='http.extraHeader'; \$env:GIT_CONFIG_VALUE_0=\"Authorization: Basic \$b64\""
                        : "\$env:GIT_CONFIG_COUNT='1'; \$env:GIT_CONFIG_KEY_0='http.extraHeader'; \$env:GIT_CONFIG_VALUE_0=\"Authorization: Bearer \$env:GF_GIT_TOKEN\""
                status = runGitWindows(dir, psCmd, hdrScript)
            }
        } else {
            script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'GF_GIT_USER', passwordVariable: 'GF_GIT_PASS')]) {
                String hdrScript = "\$pair = \"\$(\$env:GF_GIT_USER):\$(\$env:GF_GIT_PASS)\"\n\$b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(\$pair))\n\$env:GIT_CONFIG_COUNT='1'; \$env:GIT_CONFIG_KEY_0='http.extraHeader'; \$env:GIT_CONFIG_VALUE_0=\"Authorization: Basic \$b64\""
                status = runGitWindows(dir, psCmd, hdrScript)
            }
        }
        if (status == 0) return
        String output = script.powershell(label: 'GoldenFix: read the git push output', returnStdout: true,
                script: "if (Test-Path '${psq(dir)}.push.log') { Get-Content -Raw '${psq(dir)}.push.log'; Remove-Item -Force '${psq(dir)}.push.log' -ErrorAction SilentlyContinue }") as String
        script.error(pushFailure(branch, sanitizeUrl(url), status, output))
    }

    private int runGitWindows(String dir, String gitCommand, String preamble) {
        String out = script.powershell(label: 'GoldenFix: git push', returnStdout: true, script: """
\$ErrorActionPreference = 'Continue'
\$env:GIT_TERMINAL_PROMPT = '0'
${preamble}
\$logFile = '${psq(dir)}.push.log'
& { ${gitCommand} } *>&1 | Tee-Object -FilePath \$logFile | Out-Null
\$code = \$LASTEXITCODE
if (\$code -eq 0) { Remove-Item -Force \$logFile -ErrorAction SilentlyContinue }
Write-Output "DEVSECOPS_EXIT=\$code"
""") as String
        def m = (out ?: '') =~ /DEVSECOPS_EXIT=(-?\d+)/
        return m.find() ? m.group(1).toInteger() : 1
    }

    @NonCPS
    static String pushFailure(String branch, String url, int status, String output) {
        List<String> lines = (output ?: '').readLines().collect { it.trim() }
        if (lines.any { it ==~ /.*\[rejected\].*\((fetch first|non-fast-forward|already exists)\).*/ }) {
            return "[GOLDENFIX] The branch ${branch} already exists in ${url} - another GoldenFix job of this repository ran in the same minute; run one GoldenFix job for all services of a repository (projectNames)".toString()
        }
        List<String> reasons = lines.findAll { it.startsWith('remote:') || it.startsWith('! [') } as List<String>
        return "[GOLDENFIX] git push of ${branch} to ${url} failed (exit code ${status})${reasons ? ': ' + reasons.join(' ') : ''}".toString()
    }

    void revert(String dir) {
        if (!script.isUnix()) {
            script.powershell(label: 'GoldenFix: revert worktree', script: """
\$ErrorActionPreference = 'Stop'
git -C '${psq(dir)}' checkout -- .
git -C '${psq(dir)}' clean -fd
""")
            return
        }
        script.sh(label: 'GoldenFix: revert worktree', script: """#!/bin/bash
set +x
set -euo pipefail
git -C '${q(dir)}' checkout -- .
git -C '${q(dir)}' clean -fd
""")
    }

    void cleanup(String dir) {
        if (!dir) return
        String root = repoRootFor()
        if (!script.isUnix()) {
            script.powershell(label: 'GoldenFix: remove worktree', script: """
\$ErrorActionPreference = 'Continue'
Set-Location -Path '${psq(root)}'
git worktree remove --force '${psq(dir)}' 2>\$null
if (Test-Path '${psq(dir)}') { Remove-Item -Path '${psq(dir)}' -Recurse -Force -ErrorAction SilentlyContinue }
git worktree prune 2>\$null
""")
            return
        }
        script.sh(label: 'GoldenFix: remove worktree', script: """#!/bin/bash
cd '${q(root)}'
git worktree remove --force '${q(dir)}' 2>/dev/null || rm -rf '${q(dir)}'
git worktree prune 2>/dev/null || true
""")
    }

    String resolveCurrentBranch() {
        String branch = normalizeBranch((script.env.CHANGE_TARGET ?: script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String)
        if (!branch) {
            String root = repoRootFor()
            if (!script.isUnix()) {
                branch = (script.powershell(returnStdout: true, script: "git -C '${psq(root)}' rev-parse --abbrev-ref HEAD 2>\$null") as String).trim()
            } else {
                branch = script.sh(returnStdout: true, script: "git -C '${q(root)}' rev-parse --abbrev-ref HEAD 2>/dev/null || true").trim()
            }
        }
        return branch == 'HEAD' ? '' : branch
    }

    String prepareExternalRepository(String url, String branch, String credentialsId, String authType) {
        String root = workspaceRoot()
        String base = "${script.env.WORKSPACE_TMP ?: root + '@tmp'}/version-catalogs"
        String dir  = "${base}/repository-${System.currentTimeMillis()}"
        String targetBranch = branch ?: 'main'
        if (!script.isUnix()) {
            script.powershell(label: 'GoldenFix: prepare external version catalog repository', script: """
\$ErrorActionPreference = 'Stop'
if (Test-Path '${psq(dir)}') { Remove-Item -Path '${psq(dir)}' -Recurse -Force }
New-Item -ItemType Directory -Force -Path '${psq(base)}' | Out-Null
git clone --depth 1 -b '${psq(targetBranch)}' '${psq(url)}' '${psq(dir)}' 2>\$null
if (\$LASTEXITCODE -ne 0) { git clone --depth 1 '${psq(url)}' '${psq(dir)}' }
if (\$LASTEXITCODE) { exit \$LASTEXITCODE }
""")
            return dir
        }
        script.sh(label: 'GoldenFix: prepare the shared version catalog folder', script: "mkdir -p '${q(base)}' && rm -rf '${q(dir)}'")
        String target = "clone -q --depth 1 -b '${q(targetBranch)}' '${q(url)}' '${q(dir)}'"
        script.echo "[GOLDENFIX] Cloning the shared version catalog repository ${sanitizeUrl(url)} (branch ${targetBranch})"
        int status = 0
        if (isSshUrl(url) && credentialsId) {
            script.sshagent([credentialsId]) { status = runGit(base, '', target, '') }
        } else if (isSshUrl(url) || !credentialsId) {
            status = runGit(base, '', target, '')
        } else if (((authType ?: 'basic') as String).toLowerCase() in ['bearer', 'token']) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'GF_GIT_TOKEN')]) {
                status = url.contains('bitbucket.org')
                        ? runGit(base, credentialHelper('x-token-auth', '${GF_GIT_TOKEN}'), target, '')
                        : runGit(base, '', target, bearerHeaderEnv('GF_GIT_TOKEN'))
            }
        } else {
            script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'GF_GIT_USER', passwordVariable: 'GF_GIT_PASS')]) {
                status = runGit(base, credentialHelper('${GF_GIT_USER}', '${GF_GIT_PASS}'), target, '')
            }
        }
        if (status != 0) {
            String output = script.sh(returnStdout: true, script: "cat '${q(base)}.push.log' 2>/dev/null || true; rm -f '${q(base)}.push.log'") as String
            script.error("[GOLDENFIX] git clone of ${sanitizeUrl(url)} (branch ${targetBranch}) failed (exit code ${status}): ${(output ?: '').readLines().findAll { it.trim() }.takeRight(3).join(' ')}")
        }
        return dir
    }

    int runGit(String dir, String authOptions, String command, String preamble) {
        return script.sh(label: 'GoldenFix: git push', returnStatus: true, script: """#!/bin/bash
set +x
set -euo pipefail
export GIT_TERMINAL_PROMPT=0
${preamble}
git ${authOptions} -C '${q(dir)}' ${command} 2>&1 | tee '${q(dir)}.push.log'
rm -f '${q(dir)}.push.log'
""") as int
    }

    private String workspaceRoot() {
        return script.env.WORKSPACE as String
    }

    private String repoRootFor(Map cfg = state?.cfg) {
        String ws = workspaceRoot()
        String sd = (cfg?.sourceDir ?: '').toString().trim()
        if (ws && sd && sd != '.' && !sd.startsWith('/')) {
            if (script.fileExists("${sd}/.git/HEAD") || script.fileExists("${sd}/.git")) {
                return "${ws}/${sd}"
            }
        }
        return ws
    }

    @NonCPS
    private static String q(String value) {
        return BuildUtils.escapeForSingleQuotes(value ?: '')
    }

    @NonCPS
    private static String psq(String value) {
        return BuildUtils.psSingleQuote(value ?: '')
    }

    @NonCPS
    private static String quoteAll(List<String> values) {
        return values.collect { "'" + BuildUtils.escapeForSingleQuotes(it as String) + "'" }.join(' ')
    }

    @NonCPS
    static String findCommand(List<String> patterns, List<String> excludeDirs) {
        String names = patterns.collect { "${(it as String).contains('/') ? '-path' : '-name'} '${BuildUtils.escapeForSingleQuotes(it as String)}'" }.join(' -o ')
        if (!excludeDirs) return "find . -type f \\( ${names} \\) -print"
        String prune = excludeDirs.collect { "-name '${BuildUtils.escapeForSingleQuotes(it as String)}'" }.join(' -o ')
        return "find . \\( ${prune} \\) -prune -o -type f \\( ${names} \\) -print"
    }

    @NonCPS
    static String credentialHelper(String userExpression, String passwordExpression) {
        return "-c credential.helper= -c 'credential.helper=!f() { test \"\$1\" = get || exit 0; echo \"username=${userExpression}\"; echo \"password=${passwordExpression}\"; }; f'"
    }

    @NonCPS
    static String bearerHeaderEnv(String tokenVariable) {
        return 'export GIT_CONFIG_COUNT=1\nexport GIT_CONFIG_KEY_0=http.extraHeader\nexport GIT_CONFIG_VALUE_0="Authorization: Bearer ${' + tokenVariable + '}"'
    }

    @NonCPS
    static String normalizeBranch(String branch) {
        return BuildUtils.normalizeBranch(branch)
    }

    @NonCPS
    private static boolean isSshUrl(String url) {
        return url ==~ /^(ssh:\/\/.*|[\w.\-]+@[\w.\-]+:.*)$/
    }

    @NonCPS
    private static String sanitizeUrl(String url) {
        return (url ?: '').replaceFirst('^(https?://)[^@/]+@', '$1')
    }

    @NonCPS
    private static List<String> nonEmptyLines(String text) {
        return (text ?: '').readLines().collect { it.trim() }.findAll { it } as List<String>
    }
}
