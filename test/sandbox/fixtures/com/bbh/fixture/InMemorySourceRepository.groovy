package com.bbh.fixture

import com.bbh.remediation.port.SourceRepository

class InMemorySourceRepository implements SourceRepository {

    Map<String, String> files = [:]
    Map<String, String> original = [:]
    Map<String, String> written = [:]
    List<Map> commits = []
    List<String> pushedBranches = []
    List<List<String>> searches = []
    int reverts = 0

    InMemorySourceRepository(Map<String, String> files) {
        this.files.putAll(files)
        this.original.putAll(files)
    }

    String prepareWorkingCopy(String branch) {
        return '/ws@tmp/goldenfix-' + branch
    }

    List<String> findFiles(String dir, List<String> patterns, List<String> excludeDirs) {
        searches << ([] + patterns)
        String prefix = (dir && dir.contains('version-catalogs')) ? (dir + '/') : ''
        List<String> found = []
        for (String fullPath : files.keySet()) {
            String path = fullPath
            if (prefix) {
                if (!fullPath.startsWith(prefix)) continue
                path = fullPath.substring(prefix.length())
            } else if (fullPath.startsWith('/ws@tmp/version-catalogs/')) {
                continue
            }
            List<String> segments = path.tokenize('/')
            if (segments.subList(0, segments.size() - 1).any { (excludeDirs ?: []).contains(it) }) continue
            for (String pattern : patterns) {
                String regex = pattern.replaceFirst('^\\./', '').replace('.', '\\.').replace('?', '.').replace('*', '.*')
                if ((pattern.contains('/') ? path : segments.last()) ==~ regex && !found.contains(path)) found << path
            }
        }
        return found.sort()
    }

    String readText(String dir, String relativePath) {
        String scoped = (dir && dir.contains('version-catalogs')) ? "${dir}/${relativePath}".toString() : relativePath
        if (written.containsKey(scoped)) return written[scoped]
        if (files.containsKey(scoped)) return files[scoped]
        return written.containsKey(relativePath) ? written[relativePath] : files[relativePath]
    }

    void writeText(String dir, String relativePath, String content) {
        if (dir && dir.contains('version-catalogs') && files.containsKey("${dir}/${relativePath}".toString())) {
            written["${dir}/${relativePath}".toString()] = content
        }
        written[relativePath] = content
    }

    String commit(String dir, List<String> changedFiles, String message, Map author) {
        commits << [dir: dir, files: changedFiles, message: message, author: author]
        return 'c0ffee1'
    }

    void push(String dir, String branch, Map pushCfg) {
        pushedBranches << branch
    }

    void revert(String dir) {
        reverts++
        written.clear()
        files.clear()
        files.putAll(original)
    }

    void cleanup(String dir) {
    }

    String resolveCurrentBranch() {
        return 'develop'
    }

    String prepareExternalRepository(String url, String branch, String credentialsId, String authType) {
        return '/ws@tmp/version-catalogs/' + (branch ?: 'main')
    }
}
