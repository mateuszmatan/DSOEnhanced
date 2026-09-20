package com.bbh.remediation.port

interface DependencyRemediation extends Serializable {

    void remediate(List<Map> scanRefs)
}
