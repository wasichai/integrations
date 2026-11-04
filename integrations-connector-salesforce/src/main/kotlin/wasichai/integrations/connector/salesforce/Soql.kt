package wasichai.integrations.connector.salesforce

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// soql is assembled from validated identifiers and escaped literals only, never raw input
internal object Soql {
    // api names: a letter, then letters, digits and underscores (Custom__c, ns__Field__c), never ending in one.
    // nothing else can reach the query text, so no quote, space or dot gets through
    private val IDENTIFIER = Regex("""^[A-Za-z](?:[A-Za-z0-9_]*[A-Za-z0-9])?$""")
    private val DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun identifier(name: String): String {
        require(name.length <= 80 && IDENTIFIER.matches(name)) { "not a valid Salesforce api name: '$name'" }
        return name
    }

    // escape sequences from the soql reference: \n \r \t \b \f \" \' \\
    fun string(value: String): String {
        val out = StringBuilder("'")
        value.forEach { c ->
            when (c) {
                '\\' -> out.append("\\\\")
                '\'' -> out.append("\\'")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> out.append(c)
            }
        }
        return out.append("'").toString()
    }

    // datetime literals are unquoted; second precision, truncated (callers compare with >=)
    fun datetime(instant: Instant): String = DATETIME.format(instant.truncatedTo(ChronoUnit.SECONDS))

    fun select(
        fields: List<String>,
        type: String,
        where: String? = null,
        orderBy: List<String> = emptyList(),
        limit: Int? = null
    ): String {
        require(fields.isNotEmpty()) { "no fields to select" }
        val sql = StringBuilder("SELECT ")
        sql.append(fields.joinToString(", ") { identifier(it) })
        sql.append(" FROM ").append(identifier(type))
        where?.let { sql.append(" WHERE ").append(it) }
        if (orderBy.isNotEmpty()) sql.append(" ORDER BY ").append(orderBy.joinToString(", ") { identifier(it) })
        limit?.let {
            require(it > 0) { "limit must be positive" }
            sql.append(" LIMIT ").append(it)
        }
        return sql.toString()
    }
}
