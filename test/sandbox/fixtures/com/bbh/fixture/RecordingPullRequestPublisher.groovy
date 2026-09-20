package com.bbh.fixture

import com.bbh.remediation.port.PullRequestPublisher

class RecordingPullRequestPublisher implements PullRequestPublisher {

    List<Map> created = []

    Map createPullRequest(Map scmCfg, Map pullRequest) {
        created << pullRequest
        return [url: 'https://bitbucket.bbh.com/projects/TA/repos/cert-scanner/pull-requests/318', id: 318]
    }

    Map repositoryInfo(Map scmCfg) {
        return [cloneUrl: 'https://bitbucket.bbh.com/scm/ta/cert-scanner.git', cloud: false]
    }
}
