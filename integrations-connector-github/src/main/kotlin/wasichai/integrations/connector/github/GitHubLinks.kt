package wasichai.integrations.connector.github

// rfc 8288 Link header as github writes it: <url>; rel="next", <url>; rel="last"
internal object GitHubLinks {
    private val LINK = Regex("""<([^>]*)>\s*;\s*rel="([^"]*)"""")

    fun next(header: String?): String? =
        header?.let { value ->
            LINK
                .findAll(value)
                .firstOrNull { match -> match.groupValues[2].split(' ').contains("next") }
                ?.groupValues
                ?.get(1)
        }
}
