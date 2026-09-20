package com.bbh.fixture

import com.bbh.remediation.port.SourceRepository

class InMemorySourceRepository implements SourceRepository {

    Map<String, String> files = [:]
    Map<String, String> written = [:]
    List<Map> commits = []
    List<String> pushedBranches = []

    InMemorySourceRepository(Map<String, String> files) {
        this.files.putAll(files)
    }

    String prepareWorkingCopy(String branch) {
        return '/ws@tmp/goldenfix-' + branch
    }

    List<String> findFiles(String dir, List<String> namePatterns, List<String> excludeDirs) {
        List<String> found = []
        for (String path : files.keySet()) {
            String name = path.tokenize('/').last()
            for (String pattern : namePatterns) {
                String regex = pattern.replace('.', '\\.').replace('*', '.*')
                if (name ==~ regex && !found.contains(path)) found << path
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

    void cleanup(String dir) {
    }

    String resolveCurrentBranch() {
        return 'develop'
    }
}
