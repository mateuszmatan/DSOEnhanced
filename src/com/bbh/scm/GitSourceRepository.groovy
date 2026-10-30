package com.bbh.scm

import com.bbh.remediation.port.SourceRepository
import com.bbh.utils.BuildUtils
import com.cloudbees.groovy.cps.NonCPS

class GitSourceRepository implements SourceRepository {

    private final def script

    GitSourceRepository(def script) {
        this.script = script
    }

    String prepareWorkingCopy(String branch) {
        String root = workspaceRoot()
        String base = "${script.env.WORKSPACE_TMP ?: root + '@tmp'}/goldenfix"
        String dir  = "${base}/${branch}"
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
            url = script.sh(returnStdout: true, script: "git -C '${q(dir)}' remote get-url origin").trim()
        }
        String credentialsId = (pushCfg?.credentialsId ?: '') as String
        String target = "push '${q(url)}' 'HEAD:refs/heads/${q(branch)}'"
        script.echo "[GOLDENFIX] Pushing branch ${branch} to ${sanitizeUrl(url)}"

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

    @NonCPS
    static String pushFailure(String branch, String url, int status, String output) {
        List<String> lines = (output ?: '').readLines().collect { it.trim() }
        if (lines.any { it ==~ /.*\[rejected\].*\((fetch first|non-fast-forward|already exists)\).*/ }) {
            return "[GOLDENFIX] The branch ${branch} already exists in ${url} - another GoldenFix job of this repository ran in the same minute; run one GoldenFix job for all services of a repository (pipelineKeys)".toString()
        }
        List<String> reasons = lines.findAll { it.startsWith('remote:') || it.startsWith('! [') } as List<String>
        return "[GOLDENFIX] git push of ${branch} to ${url} failed (exit code ${status})${reasons ? ': ' + reasons.join(' ') : ''}".toString()
    }

    void revert(String dir) {
        script.sh(label: 'GoldenFix: revert worktree', script: """#!/bin/bash
set +x
set -euo pipefail
git -C '${q(dir)}' checkout -- .
git -C '${q(dir)}' clean -fd
""")
    }

    void cleanup(String dir) {
        script.sh(label: 'GoldenFix: remove worktree', script: """#!/bin/bash
cd '${q(workspaceRoot())}'
git worktree remove --force '${q(dir)}' 2>/dev/null || rm -rf '${q(dir)}'
git worktree prune 2>/dev/null || true
""")
    }

    String resolveCurrentBranch() {
        String branch = normalizeBranch((script.env.CHANGE_TARGET ?: script.env.BRANCH_NAME ?: script.env.GIT_BRANCH ?: '') as String)
        if (!branch) {
            branch = script.sh(returnStdout: true, script: "git -C '${q(workspaceRoot())}' rev-parse --abbrev-ref HEAD 2>/dev/null || true").trim()
        }
        return branch == 'HEAD' ? '' : branch
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

    @NonCPS
    private static String q(String value) {
        return BuildUtils.escapeForSingleQuotes(value ?: '')
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
        return (branch ?: '').trim().replaceFirst('^(refs/remotes/origin/|refs/heads/|origin/)', '')
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
