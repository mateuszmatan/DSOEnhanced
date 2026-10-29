package com.bbh.remediation.model

import com.cloudbees.groovy.cps.NonCPS

class VersionCatalogs implements Serializable {

    @NonCPS
    static String alias(String name) {
        return (name ?: '').replaceAll('[-_.]', '.')
    }

    @NonCPS
    static String baseName(String path) {
        String name = (path ?: '').replace('\\', '/').replaceFirst('^.*/', '')
        return name.replaceFirst('(\\.versions)?\\.toml$', '')
    }

    @NonCPS
    static String globRegex(String glob) {
        return (glob ?: '').replaceAll(/([\\\.\[\]\{\}\(\)\*\+\-\?\^\$\|])/, '\\\\$1')
                .replace('\\*\\*/', '(?:.*/)?').replace('\\*\\*', '.*').replace('\\*', '[^/]*').replace('\\?', '[^/]')
    }

    @NonCPS
    static String resolve(String folder, String relative) {
        if ((relative ?: '').startsWith('/')) return null
        String joined = ((folder ? folder + '/' : '') + (relative ?: '')).replace('\\', '/')
        List<String> out = []
        for (String part : joined.tokenize('/')) {
            if (part == '.') continue
            if (part == '..') {
                if (out.isEmpty()) return null
                out.remove(out.size() - 1)
            } else {
                out << part
            }
        }
        return out.join('/')
    }

    @NonCPS
    static List<Map> settingsBlocks(String text) {
        List<Map> out = []
        String source = text ?: ''
        def outer = source =~ /versionCatalogs\s*\{/
        while (outer.find()) {
            int open = outer.end()
            String body = source.substring(open, closing(source, open))
            def header = body =~ /(?:create|register)\s*\(\s*['"]([\w.-]+)['"]\s*\)\s*\{|(?<![\w.])([A-Za-z_]\w*)\s*\{/
            int next = 0
            while (header.find()) {
                if (header.start() < next) continue
                next = closing(body, header.end())
                String inner = body.substring(header.end(), next)
                out << [name     : header.group(1) ?: header.group(2), start: open + header.end(), end: open + next,
                        files    : groups(inner, /from\s*\(\s*files?\s*\(\s*['"]([^'"]+)['"]/),
                        artifacts: groups(inner, /from\s*\(\s*['"]([^'":]+:[^'":]+:[^'"]+)['"]\s*\)/)]
            }
        }
        return out
    }

    @NonCPS
    static List<String> groups(String text, String regex) {
        List<String> out = []
        def matcher = (text ?: '') =~ regex
        while (matcher.find()) out << matcher.group(1)
        return out
    }

    @NonCPS
    private static int closing(String text, int from) {
        int depth = 1
        for (int i = from; i < text.length(); i++) {
            String c = text.substring(i, i + 1)
            if (c == '{') depth++
            if (c == '}') depth--
            if (depth == 0) return i
        }
        return text.length()
    }
}
