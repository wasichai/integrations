package wasichai.integrations.connector.jira

import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer

// payload shapes from the jira cloud rest api v3 reference and atlassian's oauth 2.0 (3lo) guide,
// trimmed to what the connector reads
internal object JiraFixtures {
    const val CLOUD = "11223344-a1b2-3b33-c444-def123456789"
    const val API = "/ex/jira/$CLOUD/rest/api/3"
    const val ISSUE = "10001"

    val accessibleResources =
        """
        [{"id":"$CLOUD","url":"https://acme.atlassian.net","name":"acme",
          "scopes":["read:jira-work","write:jira-work","read:jira-user","manage:jira-webhook"],"avatarUrl":"https://x/a.png"},
         {"id":"99999999-0000-4000-8000-000000000000","url":"https://acme-wiki.atlassian.net","name":"acme-wiki",
          "scopes":["read:confluence-content.all"],"avatarUrl":"https://x/b.png"}]
        """.trimIndent()

    val projectSearch =
        """
        {"self":"x","maxResults":50,"startAt":0,"total":1,"isLast":true,
         "values":[{"id":"10000","key":"PROJ","name":"Project One","projectTypeKey":"software","simplified":false}]}
        """.trimIndent()

    val project =
        """
        {"id":"10000","key":"PROJ","name":"Project One",
         "issueTypes":[{"id":"10001","name":"Task","subtask":false},{"id":"10002","name":"Bug","subtask":false}]}
        """.trimIndent()

    val fields =
        """
        [{"id":"summary","key":"summary","name":"Summary","custom":false,"orderable":true,"navigable":true,"searchable":true,
          "schema":{"type":"string","system":"summary"}},
         {"id":"description","key":"description","name":"Description","custom":false,"schema":{"type":"string","system":"description"}},
         {"id":"issuetype","key":"issuetype","name":"Issue Type","custom":false,"schema":{"type":"issuetype","system":"issuetype"}},
         {"id":"project","key":"project","name":"Project","custom":false,"schema":{"type":"project","system":"project"}},
         {"id":"status","key":"status","name":"Status","custom":false,"schema":{"type":"status","system":"status"}},
         {"id":"priority","key":"priority","name":"Priority","custom":false,"schema":{"type":"priority","system":"priority"}},
         {"id":"labels","key":"labels","name":"Labels","custom":false,"schema":{"type":"array","items":"string","system":"labels"}},
         {"id":"assignee","key":"assignee","name":"Assignee","custom":false,"schema":{"type":"user","system":"assignee"}},
         {"id":"duedate","key":"duedate","name":"Due date","custom":false,"schema":{"type":"date","system":"duedate"}},
         {"id":"updated","key":"updated","name":"Updated","custom":false,"schema":{"type":"datetime","system":"updated"}},
         {"id":"components","key":"components","name":"Components","custom":false,"schema":{"type":"array","items":"component","system":"components"}},
         {"id":"customfield_10016","key":"customfield_10016","name":"Story point estimate","custom":true,
          "schema":{"type":"number","custom":"com.atlassian.jira.plugin.system.customfieldtypes:float","customId":10016}},
         {"id":"customfield_10020","key":"customfield_10020","name":"Team","custom":true,
          "schema":{"type":"option","custom":"com.atlassian.jira.plugin.system.customfieldtypes:select","customId":10020}},
         {"id":"customfield_10030","key":"customfield_10030","name":"Notes","custom":true,
          "schema":{"type":"string","custom":"com.atlassian.jira.plugin.system.customfieldtypes:textarea","customId":10030}},
         {"id":"customfield_10040","key":"customfield_10040","name":"Sprint","custom":true,
          "schema":{"type":"array","items":"json","custom":"com.pyxis.greenhopper.jira:gh-sprint","customId":10040}},
         {"id":"issuekey","key":"issuekey","name":"Key","custom":false}]
        """.trimIndent()

    fun issue(
        id: String = ISSUE,
        key: String = "PROJ-1",
        summary: String = "First",
        updated: String = "2026-11-04T12:00:00.000+0000",
        project: String = "PROJ"
    ) = """
        {"id":"$id","key":"$key","self":"https://api.atlassian.com$API/issue/$id",
         "fields":{"summary":"$summary","updated":"$updated","duedate":"2026-12-01","customfield_10016":5,
           "project":{"id":"10000","key":"$project","name":"Project One"},
           "issuetype":{"id":"10001","name":"Task"},"status":{"id":"3","name":"In Progress"},"priority":{"id":"2","name":"High"},
           "labels":["backend","urgent"],"assignee":{"accountId":"5b10ac8d82e05b22cc7d4ef5","displayName":"Ana"},
           "components":[{"id":"1","name":"API"}],"customfield_10020":{"id":"10100","value":"Core"},"customfield_10030":null,
           "description":{"type":"doc","version":1,"content":[
             {"type":"paragraph","content":[{"type":"text","text":"Line "},{"type":"text","text":"one","marks":[{"type":"strong"}]}]},
             {"type":"paragraph","content":[{"type":"text","text":"Line two"},{"type":"hardBreak"},{"type":"text","text":"same block"}]}]}}}
        """.trimIndent()

    fun search(
        issues: List<String>,
        next: String? = null
    ) = """{"issues":[${issues.joinToString(",")}],${next?.let { "\"nextPageToken\":\"$it\"," } ?: ""}"isLast":${next == null}}"""

    fun StubProviderServer.jiraBasics() {
        onJson(HttpMethod.GET, "/oauth/token/accessible-resources", accessibleResources)
        onJson(HttpMethod.GET, "$API/project/search", projectSearch)
        onJson(HttpMethod.GET, "$API/project/PROJ", project)
        onJson(HttpMethod.GET, "$API/field", fields)
        onJson(HttpMethod.GET, "$API/myself", """{"accountId":"5b10ac8d82e05b22cc7d4ef5","displayName":"Ana","timeZone":"America/Santiago"}""")
    }
}
