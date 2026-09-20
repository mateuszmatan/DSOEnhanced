package com.bbh.fixture

import com.bbh.remediation.port.GoldenFixSource

class StaticGoldenFixSource implements GoldenFixSource {

    List<Map> fixes = []

    StaticGoldenFixSource(List<Map> fixes) {
        this.fixes.addAll(fixes)
    }

    List<Map> fetchGoldenFixes(Map scanRef, Map cfg) {
        return fixes
    }
}
