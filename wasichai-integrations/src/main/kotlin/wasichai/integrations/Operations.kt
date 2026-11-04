package wasichai.integrations

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceSchema
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class JobView(
    val id: UUID,
    val kind: String,
    val status: JobStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
    val errorKind: String?,
    // what an operator needs to decide: a dead permanent error will fail again as it is
    val retryable: Boolean,
    val correlationId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?
)

data class HealthView(
    val connections: Map<ConnectionStatus, Int>,
    val failing: List<ConnectionHealthView>,
    val jobs: Map<JobStatus, Long>
)

data class ConnectionHealthView(
    val id: UUID,
    val connectorId: String,
    val name: String,
    val status: ConnectionStatus,
    val health: ConnectionHealth,
    val lastValidatedAt: Instant?,
    val lastError: String?,
    val rateLimitedUntil: Instant?
)

// discovery, jobs and health: what an operator looks at
class OperationsService(
    private val access: IntegrationsAccess,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts,
    private val jobs: JobRepository,
    private val limits: RateLimits,
    private val clock: Clock
) {
    suspend fun resources(connectionId: UUID): List<ResourceDescriptor> {
        val (connection, discovery) = discovery(connectionId)
        return ProviderErrors.mapped { discovery.listResources(contexts.forConnection(connection)) }
    }

    suspend fun schema(
        connectionId: UUID,
        resource: String
    ): ResourceSchema {
        val (connection, discovery) = discovery(connectionId)
        return ProviderErrors.mapped { discovery.describe(contexts.forConnection(connection), resource) }
    }

    suspend fun jobs(
        status: String?,
        kind: String?,
        limit: Int?
    ): List<JobView> {
        val user = access.manager()
        val parsed =
            status?.takeIf { it.isNotBlank() }?.let { s ->
                JobStatus.entries.firstOrNull { it.name == s.uppercase() }
                    ?: throw ValidationException("Invalid filter", "status", "one of ${JobStatus.entries.joinToString()}")
            }
        val size = (limit ?: 50).also { if (it !in 1..500) throw ValidationException("Invalid limit", listOf(FieldViolation("limit", "1 to 500"))) }
        return jobs.list(user.organizationId, parsed, kind?.takeIf { it.isNotBlank() }, size).map(::view)
    }

    suspend fun retry(id: UUID): JobView {
        val user = access.manager()
        if (!jobs.requeue(user.organizationId, id, clock.instant())) {
            jobs.find(user.organizationId, id) ?: throw NotFoundException("Job $id not found")
            throw wasichai.core.common.ConflictException("Only a DEAD job can be retried")
        }
        return view(jobs.find(user.organizationId, id)!!)
    }

    suspend fun health(): HealthView {
        val user = access.reader()
        val all = connections.list(user.organizationId)
        return HealthView(
            connections = ConnectionStatus.entries.associateWith { s -> all.count { it.status == s } },
            failing =
                all
                    .filter { it.status != ConnectionStatus.ACTIVE || it.health == ConnectionHealth.FAILING || it.health == ConnectionHealth.DEGRADED }
                    .map {
                        ConnectionHealthView(
                            it.id,
                            it.connectorId,
                            it.name,
                            it.status,
                            it.health,
                            it.lastValidatedAt,
                            it.lastError,
                            limits.pausedUntil(it.id.toString())
                        )
                    },
            jobs = jobs.counts(user.organizationId)
        )
    }

    private suspend fun discovery(connectionId: UUID): Pair<Connection, ResourceDiscovery> {
        val user = access.reader()
        val connection = connections.find(user.organizationId, connectionId) ?: throw NotFoundException("Connection $connectionId not found")
        val discovery =
            registry.find(connection.connectorId) as? ResourceDiscovery
                ?: throw ValidationException("No discovery", "connectorId", "connector '${connection.connectorId}' has no resource discovery")
        return connection to discovery
    }

    private fun view(job: Job) =
        JobView(
            job.id,
            job.kind,
            job.status,
            job.attempts,
            job.maxAttempts,
            job.nextAttemptAt,
            job.lastError,
            job.errorKind,
            job.errorKind == null || job.errorKind in setOf("transient", "rate_limited", "unexpected"),
            job.correlationId,
            job.createdAt,
            job.updatedAt,
            job.finishedAt
        )
}

@RestController
@RequestMapping("/api/integrations")
class OperationsController(
    private val operations: OperationsService
) {
    @GetMapping("/connections/{id}/resources")
    suspend fun resources(
        @PathVariable id: UUID
    ): List<ResourceDescriptor> = operations.resources(id)

    @GetMapping("/connections/{id}/resources/{resource}/schema")
    suspend fun schema(
        @PathVariable id: UUID,
        @PathVariable resource: String
    ): ResourceSchema = operations.schema(id, resource)

    @GetMapping("/jobs")
    suspend fun jobs(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) kind: String?,
        @RequestParam(required = false) limit: Int?
    ): List<JobView> = operations.jobs(status, kind, limit)

    @PostMapping("/jobs/{id}/retry")
    suspend fun retry(
        @PathVariable id: UUID
    ): JobView = operations.retry(id)

    @GetMapping("/health")
    suspend fun health(): HealthView = operations.health()
}
