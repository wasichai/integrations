package wasichai.integrations.workflows

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class WorkflowRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    // jsonb equality is semantic: key order and whitespace do not count as a change
    private val columns =
        "d.id, d.organization_id, d.name, d.status, d.graph::text AS graph, d.published_version, d.paused, d.version, d.created_at, d.updated_at, " +
            "EXISTS (SELECT 1 FROM $m.integration_workflow_versions v WHERE v.definition_id = d.id AND v.number = d.published_version " +
            "AND v.graph = d.graph) AS draft_published"

    suspend fun list(organizationId: UUID): List<WorkflowDefinition> =
        db
            .sql("SELECT $columns FROM $m.integration_workflow_definitions d WHERE d.organization_id = :org ORDER BY d.name, d.id")
            .bind("org", organizationId)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): WorkflowDefinition? =
        db
            .sql("SELECT $columns FROM $m.integration_workflow_definitions d WHERE d.organization_id = :org AND d.id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun insert(
        id: UUID,
        organizationId: UUID,
        name: String,
        graph: JsonNode,
        createdBy: UUID,
        now: Instant
    ) {
        db
            .sql(
                "INSERT INTO $m.integration_workflow_definitions (id, organization_id, name, graph, created_by, created_at, updated_at) " +
                    "VALUES (:id, :org, :name, CAST(:graph AS jsonb), :createdBy, :now, :now)"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("name", name)
            .bind("graph", json.writeValueAsString(graph))
            .bind("createdBy", createdBy)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // optimistic lock on the version the caller read. the published version is untouched
    suspend fun updateDraft(
        organizationId: UUID,
        id: UUID,
        name: String,
        graph: JsonNode,
        expectedVersion: Int,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_workflow_definitions SET name = :name, graph = CAST(:graph AS jsonb), version = version + 1, updated_at = :now " +
                    "WHERE organization_id = :org AND id = :id AND version = :version"
            ).bind("name", name)
            .bind("graph", json.writeValueAsString(graph))
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .bind("version", expectedVersion)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $m.integration_workflow_definitions WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // the definition row, locked: run inside a transaction so two publishes take turns
    suspend fun lock(
        organizationId: UUID,
        id: UUID
    ): Int? =
        db
            .sql("SELECT version FROM $m.integration_workflow_definitions WHERE organization_id = :org AND id = :id FOR UPDATE")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> row.get("version", Int::class.javaObjectType)!! }
            .one()
            .awaitFirstOrNull()

    // inside the publishing transaction: the next number, the version, its trigger, the definition's pointer
    suspend fun publish(
        organizationId: UUID,
        id: UUID,
        graph: JsonNode,
        trigger: Trigger,
        publishedBy: UUID,
        now: Instant
    ): Int {
        val number =
            db
                .sql("SELECT COALESCE(MAX(number), 0) + 1 AS next FROM $m.integration_workflow_versions WHERE definition_id = :id")
                .bind("id", id)
                .map { row, _ -> row.get("next", Int::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        db
            .sql(
                "INSERT INTO $m.integration_workflow_versions (definition_id, organization_id, number, graph, published_at, published_by) " +
                    "VALUES (:id, :org, :number, CAST(:graph AS jsonb), :now, :by)"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("number", number)
            .bind("graph", json.writeValueAsString(graph))
            .bind("now", now.odt())
            .bind("by", publishedBy)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        val event = trigger as? Trigger.Event
        db
            .sql(
                "INSERT INTO $m.integration_workflow_triggers (definition_id, organization_id, version_number, type, connection_id, event_type, config, " +
                    "updated_at) VALUES (:id, :org, :number, :type, :connection, :eventType, CAST(:config AS jsonb), :now) " +
                    "ON CONFLICT (definition_id) DO UPDATE SET version_number = EXCLUDED.version_number, type = EXCLUDED.type, " +
                    "connection_id = EXCLUDED.connection_id, event_type = EXCLUDED.event_type, config = EXCLUDED.config, updated_at = EXCLUDED.updated_at"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("number", number)
            .bind("type", trigger.type.name)
            .let { if (event == null) it.bindNull("connection", UUID::class.java) else it.bind("connection", event.connectionId) }
            .let { if (event == null) it.bindNull("eventType", String::class.java) else it.bind("eventType", event.eventType) }
            .bind("config", json.writeValueAsString(graph.get("trigger")))
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        db
            .sql(
                "UPDATE $m.integration_workflow_definitions SET status = 'PUBLISHED', published_version = :number, version = version + 1, " +
                    "updated_at = :now WHERE organization_id = :org AND id = :id"
            ).bind("number", number)
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return number
    }

    // newest first
    suspend fun versions(
        organizationId: UUID,
        id: UUID
    ): List<WorkflowVersionSummary> =
        db
            .sql(
                "SELECT number, published_at, published_by FROM $m.integration_workflow_versions WHERE organization_id = :org AND definition_id = :id " +
                    "ORDER BY number DESC"
            ).bind("org", organizationId)
            .bind("id", id)
            .map { row, _ ->
                WorkflowVersionSummary(
                    row.get("number", Int::class.javaObjectType)!!,
                    row.get("published_at", OffsetDateTime::class.java)!!.toInstant(),
                    row.get("published_by", UUID::class.java)
                )
            }.all()
            .collectList()
            .awaitSingle()

    suspend fun version(
        organizationId: UUID,
        id: UUID,
        number: Int
    ): WorkflowVersionView? =
        db
            .sql(
                "SELECT number, graph::text AS graph, published_at, published_by FROM $m.integration_workflow_versions " +
                    "WHERE organization_id = :org AND definition_id = :id AND number = :number"
            ).bind("org", organizationId)
            .bind("id", id)
            .bind("number", number)
            .map { row, _ ->
                WorkflowVersionView(
                    id,
                    row.get("number", Int::class.javaObjectType)!!,
                    json.readTree(row.get("graph", String::class.java)!!),
                    row.get("published_at", OffsetDateTime::class.java)!!.toInstant(),
                    row.get("published_by", UUID::class.java)
                )
            }.one()
            .awaitFirstOrNull()

    // pausing is not a draft change: the version (ETag) stays
    suspend fun setPaused(
        organizationId: UUID,
        id: UUID,
        paused: Boolean,
        now: Instant
    ): Boolean =
        db
            .sql("UPDATE $m.integration_workflow_definitions SET paused = :paused, updated_at = :now WHERE organization_id = :org AND id = :id")
            .bind("paused", paused)
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // only the hash is kept: the url's secret is shown once, when issued
    suspend fun setWebhookToken(
        organizationId: UUID,
        id: UUID,
        hash: String,
        now: Instant
    ): Boolean =
        db
            .sql("UPDATE $m.integration_workflow_definitions SET webhook_token_hash = :hash, updated_at = :now WHERE organization_id = :org AND id = :id")
            .bind("hash", hash)
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // names of the workflows whose draft or published version uses this connection (ConnectionUsage).
    // the path is a constant; the connection id is a bound jsonpath variable
    suspend fun namesOnConnection(
        organizationId: UUID,
        connectionId: UUID
    ): List<String> =
        db
            .sql(
                "SELECT d.name FROM $m.integration_workflow_definitions d WHERE d.organization_id = :org AND (" +
                    "jsonb_path_exists(d.graph, '$.**.connectionId ? (@ == \$c)', jsonb_build_object('c', CAST(:connection AS text))) " +
                    "OR EXISTS (SELECT 1 FROM $m.integration_workflow_versions v WHERE v.definition_id = d.id AND v.number = d.published_version " +
                    "AND jsonb_path_exists(v.graph, '$.**.connectionId ? (@ == \$c)', jsonb_build_object('c', CAST(:connection AS text))))) ORDER BY d.name"
            ).bind("org", organizationId)
            .bind("connection", connectionId.toString())
            .map { row, _ -> row.get("name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    private fun read(row: Readable): WorkflowDefinition =
        WorkflowDefinition(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            name = row.get("name", String::class.java)!!,
            status = WorkflowStatus.valueOf(row.get("status", String::class.java)!!),
            graph = json.readTree(row.get("graph", String::class.java)!!),
            publishedVersion = row.get("published_version", Int::class.javaObjectType),
            draftPublished = row.get("draft_published", Boolean::class.javaObjectType) ?: false,
            paused = row.get("paused", Boolean::class.javaObjectType) ?: false,
            version = row.get("version", Int::class.javaObjectType)!!,
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant()
        )

    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
