package com.bbh.remediation.port

interface PullRequestPublisher extends Serializable {

    Map createPullRequest(Map scmCfg, Map pullRequest)

    Map repositoryInfo(Map scmCfg)
}
