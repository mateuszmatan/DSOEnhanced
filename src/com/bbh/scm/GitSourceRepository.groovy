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

    List<String> findFiles(String dir, List<String> namePatterns, List<String> excludeDirs) {
        if (!namePatterns) return []
        String out = script.sh(label: 'GoldenFix: find dependency manifests', returnStdout: true, script: """#!/bin/bash
set -euo pipefail
cd '${q(dir)}'
${findCommand(namePatterns, excludeDirs)} | sed 's|^\\./||' | LC_ALL=C sort
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

        if (isSshUrl(url) || !credentialsId) {
            if (isSshUrl(url) && credentialsId) {
                script.sshagent([credentialsId]) { runGit(dir, '', target, '') }
            } else {
                runGit(dir, '', target, '')
            }
            return
        }
        if (((pushCfg.authType ?: 'basic') as String).toLowerCase() in ['bearer', 'token']) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'GF_GIT_TOKEN')]) {
                if (pushCfg.cloud) {
                    runGit(dir, credentialHelper('x-token-auth', '${GF_GIT_TOKEN}'), target, '')
                } else {
                    runGit(dir, '', target, bearerHeaderEnv('GF_GIT_TOKEN'))
                }
            }
        } else {
            script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'GF_GIT_USER', passwordVariable: 'GF_GIT_PASS')]) {
                runGit(dir, credentialHelper('${GF_GIT_USER}', '${GF_GIT_PASS}'), target, '')
            }
        }
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

    void runGit(String dir, String authOptions, String command, String preamble) {
        script.sh(label: 'GoldenFix: git push', script: """#!/bin/bash
set +x
set -euo pipefail
export GIT_TERMINAL_PROMPT=0
${preamble}
git ${authOptions} -C '${q(dir)}' ${command}
""")
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
    static String findCommand(List<String> namePatterns, List<String> excludeDirs) {
        String names = namePatterns.collect { "-name '${BuildUtils.escapeForSingleQuotes(it as String)}'" }.join(' -o ')
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
