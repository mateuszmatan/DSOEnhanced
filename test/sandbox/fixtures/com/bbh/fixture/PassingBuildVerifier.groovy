package com.bbh.fixture

import com.bbh.remediation.port.BuildVerifier

class PassingBuildVerifier implements BuildVerifier {

    List<List<String>> verified = []

    Map verify(String dir, List<String> changedFiles, Map cfg) {
        verified << ([] + (changedFiles ?: []))
        return [ok: true, skipped: false, log: 'stub build', checks: [], files: []]
    }
}
