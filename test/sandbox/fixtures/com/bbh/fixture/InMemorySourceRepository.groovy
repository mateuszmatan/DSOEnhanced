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
        List<String> found = []
        for (String path : files.keySet()) {
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
        return written.containsKey(relativePath) ? written[relativePath] : files[relativePath]
    }

    void writeText(String dir, String relativePath, String content) {
        written[relativePath] = content
    }

    String commit(String dir, List<String> changedFiles, String message, Map author) {
        commits << [files: changedFiles, message: message, author: author]
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
}
