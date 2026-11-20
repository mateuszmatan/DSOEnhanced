package com.bbh.remediation.port

interface PullRequestPublisher extends Serializable {

    Map createPullRequest(Map scmCfg, Map pullRequest)

    void addComment(Map scmCfg, Map pullRequest, String text)

    void addLineComment(Map scmCfg, Map pullRequest, String path, int line, String text)

    Map findOpenPullRequest(Map scmCfg, String sourceBranch, String targetBranch)

    Map repositoryInfo(Map scmCfg)

    List<String> existingComments(Map scmCfg, Map pullRequest)
}
