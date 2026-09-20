package com.bbh.remediation.port

interface SourceRepository extends Serializable {

    String prepareWorkingCopy(String branch)

    List<String> findFiles(String dir, List<String> namePatterns, List<String> excludeDirs)

    String readText(String dir, String relativePath)

    void writeText(String dir, String relativePath, String content)

    String commit(String dir, List<String> files, String message, Map author)

    void push(String dir, String branch, Map pushCfg)

    void cleanup(String dir)

    String resolveCurrentBranch()
}
