package com.bbh.remediation.port

interface GoldenFixSource extends Serializable {

    List<Map> fetchGoldenFixes(Map scanRef, Map cfg)
}
