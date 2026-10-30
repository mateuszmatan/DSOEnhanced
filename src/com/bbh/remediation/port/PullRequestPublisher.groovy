package com.bbh.remediation.port

interface PullRequestPublisher extends Serializable {

    Map createPullRequest(Map scmCfg, Map pullRequest)

    void addComment(Map scmCfg, Map pullRequest, String text)

    Map repositoryInfo(Map scmCfg)
}
