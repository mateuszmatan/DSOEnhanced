package com.bbh.scm

import com.bbh.remediation.port.PullRequestPublisher
import com.bbh.utils.RestClient
import com.cloudbees.groovy.cps.NonCPS

class BitbucketPullRequestPublisher implements PullRequestPublisher {

    private final def        script
    private final RestClient rest

    BitbucketPullRequestPublisher(def script) {
        this.script = script
        this.rest   = new RestClient(script)
    }

    Map repositoryInfo(Map scmCfg) {
        Map repo = parse(scmCfg)
        if (repo.error) script.error("[GOLDENFIX] ${repo.error}")
        return [webUrl: repo.webUrl, cloneUrl: repo.cloneUrl, cloud: repo.type == 'cloud']
    }

    Map createPullRequest(Map scmCfg, Map pullRequest) {
        Map repo = parse(scmCfg)
        if (repo.error) script.error("[GOLDENFIX] ${repo.error}")
        String credentialsId = (scmCfg.credentialsId ?: '') as String
        if (!credentialsId) script.error("[GOLDENFIX] scm.bitbucket.credentialsId is required to raise a pull request")
        List reviewers = (scmCfg.reviewers instanceof List) ? (scmCfg.reviewers as List) : []

        Map result = null
        if (isBearer(scmCfg)) {
            script.withCredentials([script.string(credentialsId: credentialsId, variable: 'BB_GF_TOKEN')]) {
                result = create(repo, pullRequest, reviewers, [type: 'bearer', tokenVar: 'BB_GF_TOKEN'])
            }
        } else {
            script.withCredentials([script.usernamePassword(credentialsId: credentialsId, usernameVariable: 'BB_GF_USER', passwordVariable: 'BB_GF_PASS')]) {
                result = create(repo, pullRequest, reviewers, [type: 'basic', userVar: 'BB_GF_USER', passVar: 'BB_GF_PASS'])
            }
        }
        script.echo "[GOLDENFIX] Pull request ${result.existing ? 'already existed' : 'created'}: ${result.url}"
        return result
    }

    Map create(Map repo, Map pullRequest, List reviewers, Map auth) {
        return repo.type == 'cloud'
                ? createCloud(repo, pullRequest, reviewers, auth)
                : createServer(repo, pullRequest, reviewers, auth)
    }

    private Map createServer(Map repo, Map pullRequest, List reviewers, Map auth) {
        String api = "${repo.apiBase}/pull-requests"
        Map response = rest.request('POST', api, serverPayload(repo, pullRequest, reviewers), auth,
                "Bitbucket: create pull request ${pullRequest.title}")
        if ((response.status as int) == 409) {
            String at = RestClient.urlEncode("refs/heads/${pullRequest.sourceBranch}".toString())
            def existing = rest.getJson("${api}?state=OPEN&direction=OUTGOING&at=${at}", auth, 'Bitbucket: find existing pull request')
            def first = firstValue(existing)
            if (first) return [id: first.id?.toString(), url: serverPullRequestUrl(first, repo), existing: true]
        }
        rest.ensureSuccess(response, 'Bitbucket: create pull request')
        def json = rest.parseJson(response.body as String)
        return [id: json?.id?.toString(), url: serverPullRequestUrl(json, repo), existing: false]
    }

    private Map createCloud(Map repo, Map pullRequest, List reviewers, Map auth) {
        String api = "${repo.apiBase}/repositories/${RestClient.urlEncode(repo.workspace as String)}/${RestClient.urlEncode(repo.repoSlug as String)}/pullrequests"
        Map response = rest.request('POST', api, cloudPayload(pullRequest, reviewers), auth,
                "Bitbucket Cloud: create pull request ${pullRequest.title}")
        rest.ensureSuccess(response, 'Bitbucket Cloud: create pull request')
        def json = rest.parseJson(response.body as String)
        String url = (json?.links?.html?.href ?: "${repo.webUrl}/pull-requests/${json?.id}") as String
        return [id: json?.id?.toString(), url: url, existing: false]
    }

    @NonCPS
    static Map serverPayload(Map repo, Map pullRequest, List reviewers) {
        Map repository = [slug: repo.repoSlug, project: [key: repo.projectKey]]
        return [
                title      : pullRequest.title as String,
                description: truncate(pullRequest.description as String),
                state      : 'OPEN',
                open       : true,
                closed     : false,
                locked     : false,
                fromRef    : [id: "refs/heads/${pullRequest.sourceBranch}".toString(), repository: repository],
                toRef      : [id: "refs/heads/${pullRequest.targetBranch}".toString(), repository: repository],
                reviewers  : reviewers.collect { [user: [name: it as String]] }
        ]
    }

    @NonCPS
    static Map cloudPayload(Map pullRequest, List reviewers) {
        return [
                title              : pullRequest.title as String,
                description        : truncate(pullRequest.description as String),
                source             : [branch: [name: pullRequest.sourceBranch as String]],
                destination        : [branch: [name: pullRequest.targetBranch as String]],
                close_source_branch: true,
                reviewers          : reviewers.collect { [uuid: it as String] }
        ]
    }

    @NonCPS
    private static String serverPullRequestUrl(def json, Map repo) {
        def self = json?.links?.self
        if (self instanceof List && !self.isEmpty() && self[0]?.href) return self[0].href as String
        return "${repo.webUrl}/pull-requests/${json?.id}".toString()
    }

    @NonCPS
    private static def firstValue(def page) {
        def values = page?.values
        return (values instanceof List && !values.isEmpty()) ? values[0] : null
    }

    @NonCPS
    private static boolean isBearer(Map scmCfg) {
        return ((scmCfg.authType ?: 'basic') as String).toLowerCase() in ['bearer', 'token']
    }

    @NonCPS
    private static String truncate(String text) {
        if (!text) return ''
        return text.length() > 30000 ? text.substring(0, 30000) + '\n\n_(truncated)_' : text
    }

    @NonCPS
    private static String cloudRe() {
        String re = /^https?:\/\/(?:[^@\/]+@)?(?:api\.)?bitbucket\.org\/(?:2\.0\/repositories\/)?([^\/]+)\/([^\/]+?)(?:\.git)?(?:\/.*)?$/
        return re
    }

    @NonCPS
    private static String restRe() {
        String re = /^(https?:\/\/.+?)\/rest\/api\/(?:1\.0|latest)\/(projects|users)\/([^\/]+)\/repos\/([^\/]+).*$/
        return re
    }

    @NonCPS
    private static String browseRe() {
        String re = /^(https?:\/\/.+?)\/(projects|users)\/([^\/]+)\/repos\/([^\/?#]+).*$/
        return re
    }

    @NonCPS
    private static String cloneRe() {
        String re = /^(https?:\/\/)(?:[^@\/]+@)?(.+?)\/scm\/([^\/]+)\/([^\/]+?)(?:\.git)?$/
        return re
    }

    @NonCPS
    private static Map parse(Map scmCfg) {
        String url = ((scmCfg?.url ?: '') as String).trim().replaceAll('/+$', '')
        String type = ((scmCfg?.type ?: (url.toLowerCase().contains('bitbucket.org') ? 'cloud' : 'server')) as String).toLowerCase()
        return type == 'cloud' ? parseCloud(url, scmCfg) : parseServer(url, scmCfg)
    }

    @NonCPS
    private static Map parseCloud(String url, Map scmCfg) {
        List groups = firstMatch(url, cloudRe())
        String workspace = (scmCfg?.workspace ?: (groups ? groups[1] : null)) as String
        String slug      = (scmCfg?.repoSlug ?: (groups ? groups[2] : null)) as String
        if (!workspace || !slug) {
            return [error: "Cannot determine the Bitbucket Cloud repository from scm.bitbucket.url='${url}'. Expected https://bitbucket.org/<workspace>/<repository>".toString()]
        }
        return [
                type     : 'cloud',
                workspace: workspace,
                repoSlug : slug,
                apiBase  : ((scmCfg?.apiUrl ?: 'https://api.bitbucket.org/2.0') as String).replaceAll('/+$', ''),
                webUrl   : "https://bitbucket.org/${workspace}/${slug}".toString(),
                cloneUrl : (scmCfg?.cloneUrl ?: "https://bitbucket.org/${workspace}/${slug}.git").toString()
        ]
    }

    @NonCPS
    private static Map parseServer(String url, Map scmCfg) {
        String base = null
        String project = null
        String slug = null

        List rest = firstMatch(url, restRe())
        List browse = firstMatch(url, browseRe())
        List clone = firstMatch(url, cloneRe())
        if (rest) {
            base = rest[1]; project = projectKey(rest[2] as String, rest[3] as String); slug = rest[4]
        } else if (browse) {
            base = browse[1]; project = projectKey(browse[2] as String, browse[3] as String); slug = browse[4]
        } else if (clone) {
            base = (clone[1] as String) + (clone[2] as String)
            project = (clone[3] as String).startsWith('~') ? clone[3] as String : (clone[3] as String).toUpperCase()
            slug = clone[4]
        }

        base    = ((scmCfg?.apiUrl ?: base) as String)?.replaceAll('/+$', '')
        project = (scmCfg?.projectKey ?: project) as String
        slug    = (scmCfg?.repoSlug ?: slug) as String
        if (!base || !project || !slug) {
            return [error: "Cannot determine the Bitbucket repository from scm.bitbucket.url='${url}'. Expected https://<host>/projects/<KEY>/repos/<slug> or set projectKey and repoSlug".toString()]
        }

        String webPath = project.startsWith('~') ? "users/${project.substring(1)}" : "projects/${project}"
        return [
                type      : 'server',
                baseUrl   : base,
                projectKey: project,
                repoSlug  : slug,
                apiBase   : "${base}/rest/api/1.0/projects/${project}/repos/${slug}".toString(),
                webUrl    : "${base}/${webPath}/repos/${slug}".toString(),
                cloneUrl  : (scmCfg?.cloneUrl ?: "${base}/scm/${project.toLowerCase()}/${slug}.git").toString()
        ]
    }

    @NonCPS
    private static List firstMatch(String text, String regex) {
        def matcher = (text ?: '') =~ regex
        return matcher ? (matcher[0] as List) : null
    }

    @NonCPS
    private static String projectKey(String kind, String key) {
        return kind == 'users' ? "~${key}".toString() : key
    }
}
