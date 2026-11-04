package wasichai.integrations

import wasichai.integrations.sdk.ConnectorException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// a job kind for tests: the payload says how it should end
class RecordingJobs : JobHandler {
    override val kind = "test.record"
    val runs = ConcurrentHashMap<UUID, AtomicInteger>()

    override suspend fun handle(job: Job) {
        val n = runs.computeIfAbsent(job.id) { AtomicInteger() }.incrementAndGet()
        when (job.payload.path("mode").asString()) {
            "transient" -> throw ConnectorException.Transient("provider down")
            "permanent" -> throw ConnectorException.Permanent("bad request")
            "flaky" -> if (n < 2) throw ConnectorException.Transient("first time fails")
        }
    }
}
