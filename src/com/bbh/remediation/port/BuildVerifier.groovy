package com.bbh.remediation.port

interface BuildVerifier extends Serializable {

    Map verify(String dir, List<String> changedFiles, Map cfg)
}
