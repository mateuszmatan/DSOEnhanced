package com.bbh.fixture

import com.bbh.remediation.port.PullRequestPublisher

class RecordingPullRequestPublisher implements PullRequestPublisher {

    List<Map> created = []
    List<Map> comments = []
    String commentFailure = ''

    Map createPullRequest(Map scmCfg, Map pullRequest) {
        created << pullRequest
        return [url: 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318', id: 318]
    }

    void addComment(Map scmCfg, Map pullRequest, String text) {
        if (commentFailure) throw new Exception(commentFailure)
        comments << [pullRequest: pullRequest, text: text]
    }

    Map repositoryInfo(Map scmCfg) {
        return [cloneUrl: 'https://bitbucket.bbh.com/scm/ta/cert-scanner.git', cloud: false]
    }
}
