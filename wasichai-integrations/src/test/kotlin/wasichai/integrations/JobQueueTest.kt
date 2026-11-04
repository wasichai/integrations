package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.TestTenant.Companion.call
import java.time.Duration
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobQueueTest : IntegrationsIntegrationTest() {
    @Autowired
    private lateinit var jobs: JobRepository

    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var recording: RecordingJobs

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { TestTenant.provision(client) }
    private val other by lazy { TestTenant.provision(client) }

    private fun enqueue(
        mode: String,
        key: String? = null
    ): UUID? = runBlocking { jobs.enqueue(tenant.organizationId, "test.record", mapOf("mode" to mode), key, Instant.now().minusSeconds(1), maxAttempts = 3) }

    private fun job(id: UUID) = runBlocking { jobs.find(tenant.organizationId, id)!! }

    // drain until the job settles; other classes' jobs may share the queue
    private fun drainUntil(
        id: UUID,
        done: (Job) -> Boolean
    ): Job {
        repeat(50) {
            runBlocking { runner.drainOnce() }
            val now = job(id)
            if (done(now)) return now
            Thread.sleep(20)
        }
        return job(id)
    }

    @Test
    fun `a job runs once and succeeds`() {
        val id = enqueue("ok")!!
        val done = drainUntil(id) { it.status == JobStatus.SUCCEEDED }
        assertEquals(JobStatus.SUCCEEDED, done.status)
        assertEquals(1, recording.runs[id]!!.get())
    }

    @Test
    fun `the same key waiting twice is enqueued once`() {
        val key = "k-" + TestTenant.unique()
        val first = enqueue("ok", key)
        assertNotNull(first)
        assertNull(enqueue("ok", key))
        drainUntil(first!!) { it.status == JobStatus.SUCCEEDED }
        assertNotNull(enqueue("ok", key), "once done, the key is free again")
    }

    @Test
    fun `a transient failure is retried until it works`() {
        val id = enqueue("flaky")!!
        val done = drainUntil(id) { it.status == JobStatus.SUCCEEDED }
        assertEquals(JobStatus.SUCCEEDED, done.status)
        assertEquals(2, done.attempts)
    }

    @Test
    fun `permanent failures die at once, transient ones after the budget`() {
        val permanent = enqueue("permanent")!!
        val dead = drainUntil(permanent) { it.status == JobStatus.DEAD }
        assertEquals(1, dead.attempts)
        assertEquals("permanent", dead.errorKind)
        val transient = enqueue("transient")!!
        val gave = drainUntil(transient) { it.status == JobStatus.DEAD }
        assertEquals(3, gave.attempts)
        assertEquals("transient", gave.errorKind)
    }

    @Test
    fun `a job whose worker died is claimed again when its lease runs out`() {
        val id = enqueue("ok")!!
        // a worker claims it and dies: RUNNING with a lease, nobody finishing it
        val claimed = runBlocking { jobs.claim(500, Duration.ofMinutes(5), Instant.now()) }.first { it.id == id }
        assertEquals(JobStatus.RUNNING, claimed.status)
        runBlocking { runner.drainOnce() }
        assertEquals(JobStatus.RUNNING, job(id).status, "the lease still holds")
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.integration_jobs SET lease_until = now() - interval '1 second' WHERE id = :id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val done = drainUntil(id) { it.status == JobStatus.SUCCEEDED }
        assertEquals(JobStatus.SUCCEEDED, done.status)
        // the dead worker cannot overwrite the outcome with its stale claim
        assertFalse(runBlocking { jobs.succeed(claimed, Instant.now()) })
    }

    @Test
    fun `operators list dead jobs and send them back, within their tenant`() {
        val id = enqueue("permanent")!!
        drainUntil(id) { it.status == JobStatus.DEAD }
        val dead = call(client, HttpMethod.GET, "/api/integrations/jobs?status=dead", tenant.admin, null, 200)
        assertTrue((0 until dead.size()).any { dead.get(it).get("id").asString() == id.toString() })
        call(client, HttpMethod.GET, "/api/integrations/jobs?status=nope", tenant.admin, null, 400)
        call(client, HttpMethod.POST, "/api/integrations/jobs/$id/retry", other.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/jobs/$id/retry", tenant.member(), null, 403)
        val retried = call(client, HttpMethod.POST, "/api/integrations/jobs/$id/retry", tenant.admin, null, 200)
        assertEquals("PENDING", retried.get("status").asString())
        assertEquals(0, retried.get("attempts").asInt())
        call(client, HttpMethod.POST, "/api/integrations/jobs/$id/retry", tenant.admin, null, 409)
        val health = call(client, HttpMethod.GET, "/api/integrations/health", tenant.member(), null, 200)
        assertTrue(health.get("jobs").has("PENDING"))
    }
}
