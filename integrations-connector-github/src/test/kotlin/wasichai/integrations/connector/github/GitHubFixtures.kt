package wasichai.integrations.connector.github

import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer

// payload shapes from the github rest api reference (version 2022-11-28) and its webhook docs, trimmed to what the
// connector reads plus a few fields it must ignore
internal object GitHubFixtures {
    const val REPO = "octocat/Hello-World"
    const val ISSUES = "/repos/$REPO/issues"

    val user =
        """
        {"login":"octocat","id":1,"node_id":"MDQ6VXNlcjE=","type":"User","site_admin":false,"name":"monalisa octocat",
         "html_url":"https://github.com/octocat"}
        """.trimIndent()

    fun repository(
        fullName: String = REPO,
        hasIssues: Boolean = true
    ) = """
        {"id":1296269,"node_id":"MDEwOlJlcG9zaXRvcnkxMjk2MjY5","name":"${fullName.substringAfter('/')}","full_name":"$fullName",
         "owner":{"login":"${fullName.substringBefore('/')}","id":1},"private":false,"description":"This your first repo!",
         "has_issues":$hasIssues,"archived":false,"open_issues_count":0,"permissions":{"admin":false,"push":true,"pull":true}}
        """.trimIndent()

    fun issue(
        number: Int = 1347,
        title: String = "Found a bug",
        updated: String = "2026-11-04T12:00:00Z",
        state: String = "open",
        pullRequest: Boolean = false
    ) = """
        {"id":${1000 + number},"node_id":"MDU6SXNzdWUx","url":"https://api.github.com/repos/$REPO/issues/$number",
         "html_url":"https://github.com/$REPO/issues/$number","number":$number,"state":"$state",
         "state_reason":${if (state == "closed") "\"completed\"" else "null"},"title":"$title","body":"I'm having a problem with this.",
         "user":{"login":"octocat","id":1},
         "labels":[{"id":208045946,"name":"bug","description":"Something isn't working","color":"f29513","default":true}],
         "assignee":{"login":"octocat","id":1},"assignees":[{"login":"octocat","id":1}],
         "milestone":{"id":1002604,"number":1,"state":"open","title":"v1.0"},
         "locked":true,"active_lock_reason":"too heated","comments":0,
         ${if (pullRequest) "\"pull_request\":{\"url\":\"https://api.github.com/repos/$REPO/pulls/$number\"}," else ""}
         "closed_at":${if (state == "closed") "\"$updated\"" else "null"},"created_at":"2026-11-01T09:00:00Z","updated_at":"$updated",
         "closed_by":null,"author_association":"COLLABORATOR"}
        """.trimIndent()

    fun array(items: List<String>) = items.joinToString(",", "[", "]")

    fun StubProviderServer.githubBasics() {
        onJson(HttpMethod.GET, "/user", user)
        onJson(HttpMethod.GET, "/user/repos", array(listOf(repository())))
        onJson(HttpMethod.GET, "/repos/$REPO", repository())
    }
}
