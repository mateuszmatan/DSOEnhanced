package com.bbh.remediation.port

interface ManifestUpdater extends Serializable {

    String ecosystem()

    List<String> filePatterns()

    boolean supports(String relativePath)

    Map updateDeclarations(String relativePath, String content, List<Map> fixes)

    Map updateProperties(String relativePath, String content, List<Map> properties)
}
