package com.bbh.fixture

import com.bbh.remediation.port.PullRequestPublisher

class InMemoryPullRequestPublisher implements PullRequestPublisher {

    String repository = 'https://bitbucket.bbh.com/projects/PAY/repos/payments'
    Map<String, Map> openPullRequests = [:]
    Map<String, List<String>> texts = [:]
    List<Map> created = []
    List<Map> comments = []
    List<Map> lineComments = []
    List<String> changed = null
    String failure = ''
    boolean answerExisting = false
    int nextId = 41

    Map open(String sourceBranch, Map details = [:]) {
        String id = "${nextId}".toString()
        nextId++
        Map pullRequest = [id: id, url: "${repository}/pull-requests/${id}".toString()] + details
        openPullRequests[sourceBranch] = pullRequest
        return pullRequest
    }

    void already(String id, String text) {
        textsOf(id) << text
    }

    List<String> textsOf(String id) {
        if (!texts.containsKey(id)) texts[id] = []
        return texts[id]
    }

    Map createPullRequest(Map scmCfg, Map pullRequest) {
        created << pullRequest
        Map opened = open(pullRequest.sourceBranch as String)
        return answerExisting ? opened + [existing: true] : opened + [:]
    }

    void addComment(Map scmCfg, Map pullRequest, String text) {
        if (failure) throw new Exception(failure)
        comments << [id: pullRequest.id as String, text: text]
        textsOf(pullRequest.id as String) << text
    }

    void addLineComment(Map scmCfg, Map pullRequest, String path, int line, String text) {
        if (failure) throw new Exception(failure)
        lineComments << [id: pullRequest.id as String, path: path, line: line, text: text]
        textsOf(pullRequest.id as String) << text
    }

    Map findOpenPullRequest(Map scmCfg, String sourceBranch, String targetBranch) {
        Map pullRequest = openPullRequests[sourceBranch]
        return pullRequest == null ? null : pullRequest + [:]
    }

    List<String> existingComments(Map scmCfg, Map pullRequest) {
        return [] + textsOf(pullRequest.id as String)
    }

    List<String> changedFiles(Map scmCfg, Map pullRequest) {
        return changed
    }

    Map repositoryInfo(Map scmCfg) {
        return [cloneUrl: 'https://bitbucket.bbh.com/scm/pay/payments.git', cloud: false]
    }
}
