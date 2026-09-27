package com.bbh.utils

import com.cloudbees.groovy.cps.NonCPS

class BuildUtils implements Serializable {

    static def execSh(def steps, String cmd, String label, boolean returnStdout) {
        String script = """
            #!/usr/bin/env bash
            set -euo pipefail
            ${cmd}
        """

        if (returnStdout) {
            return steps.sh(label: label, script: script, returnStdout: true).trim()
        }
        steps.sh(label: label, script: script)
        return null
    }

    static List<String> normalizeTokens(def v) {
        if (v == null) return []
        if (v instanceof List) return v.collect { it.toString() }.findAll { it?.trim() }
        if (v instanceof String ) {
            String s = v.trim()
            if (!s) return []
            return s.split(/\s+/).toList()
        }
        return [v.toString()]
    }

    static String shellQuoteIfNeeded(String token) {
        if (token == null) return ""
        if (token ==~ /^A-Za-z0-9._\/:=+-$/) return token
        return "'" + escapeForSingleQuotes(token) + "'"
    }

    @NonCPS
    static String fileNameOf(String path) {
        String normalized = (path ?: '').replace('\\', '/')
        int index = normalized.lastIndexOf('/')
        return index >= 0 ? normalized.substring(index + 1) : normalized
    }

    static String parentOf(String path) {
        String normalized = (path ?: '').replace('\\', '/')
        int index = normalized.lastIndexOf('/')
        return index >= 0 ? normalized.substring(0, index) : ''
    }

    static String escapeForSingleQuotes(String value) {
        return value.replace("'", "'\"'\"'")
    }

    @NonCPS
    static boolean booleanValue(def value, boolean defaultValue) {
        if (value == null) {
            return defaultValue
        }

        if (value instanceof Boolean) {
            return value
        }

        return value.toString().toBoolean()
    }

    static String requiredString(def script, def value, String propertyName) {
        String result = value?.toString()?.trim()

        if (!result) {
            script.error "[DOD] '${propertyName}' is required."
        }
        return result
    }

    @NonCPS
    static String msFmt(long ms) {
        if (ms <= 0) return '-'
        int s = (ms / 1000) as int
        if (s < 60) return "${s}s"
        int m = s.intdiv(60)
        int sec = s % 60
        return sec > 0 ? "${m}m ${sec}s" : "${m}m"
    }
}
