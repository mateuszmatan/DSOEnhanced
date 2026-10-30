package com.bbh.fixture

import com.bbh.remediation.port.GoldenFixSource

class StaticGoldenFixSource implements GoldenFixSource {

    List<Map> fixes = []
    Throwable failure = null

    StaticGoldenFixSource(List<Map> fixes) {
        this.fixes.addAll(fixes)
    }

    List<Map> fetchGoldenFixes(Map scanRef, Map cfg) {
        if (failure) throw failure
        return fixes
    }
}
