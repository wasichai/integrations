package wasichai.integrations.connector.salesforce.pubsub

import com.google.protobuf.ByteString
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.kotlin.ClientCalls
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.avro.Schema
import wasichai.integrations.connector.salesforce.pubsub.proto.ConsumerEvent
import wasichai.integrations.connector.salesforce.pubsub.proto.FetchRequest
import wasichai.integrations.connector.salesforce.pubsub.proto.PubSubGrpc
import wasichai.integrations.connector.salesforce.pubsub.proto.ReplayPreset
import wasichai.integrations.connector.salesforce.pubsub.proto.SchemaRequest
import wasichai.integrations.sdk.ConnectorException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

// what the pub/sub api wants on every call: the oauth token, the instance it belongs to and the org id
internal class PubSubAuth(
    accessToken: String,
    instanceUrl: String,
    tenantId: String
) {
    val metadata =
        Metadata().apply {
            put(key("accesstoken"), accessToken)
            put(key("instanceurl"), instanceUrl)
            put(key("tenantid"), tenantId)
        }

    // never printed: the metadata holds the token
    override fun toString() = "PubSubAuth(***)"

    private fun key(name: String) = Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER)
}

internal class Pulled(
    val events: List<ConsumerEvent>,
    // the newest replay id the server named, from an event or from latest_replay_id; null when it named none
    val replayId: ByteString?
)

// one bounded pull: open Subscribe, ask for up to `wanted` events, take what arrives within `drain`, close.
// everything suspends on grpc's callbacks (grpc-kotlin); no thread waits
internal class PubSubClient(
    private val channel: Channel
) {
    // schema ids name immutable schemas: safe to keep for the life of the connector
    private val schemas = ConcurrentHashMap<String, Schema>()

    suspend fun pull(
        auth: PubSubAuth,
        topic: String,
        replayId: ByteArray?,
        wanted: Int,
        drain: Duration
    ): Pulled {
        val request =
            FetchRequest
                .newBuilder()
                .setTopicName(topic)
                .setNumRequested(wanted)
                .apply {
                    if (replayId == null) {
                        // no position yet: the oldest retained event; the caller skips what its full read covered
                        setReplayPreset(ReplayPreset.EARLIEST)
                    } else {
                        setReplayPreset(ReplayPreset.CUSTOM)
                        setReplayId(ByteString.copyFrom(replayId))
                    }
                }.build()
        // the request stream stays open while we read: closing it would end the subscription early
        val requests =
            flow {
                emit(request)
                awaitCancellation()
            }
        val events = mutableListOf<ConsumerEvent>()
        var latest: ByteString? = null
        try {
            withTimeoutOrNull(drain.toMillis()) {
                ClientCalls
                    .bidiStreamingRpc(channel, PubSubGrpc.getSubscribeMethod(), requests, CallOptions.DEFAULT, auth.metadata)
                    .transformWhile { response ->
                        emit(response)
                        events.size < wanted
                    }.collect { response ->
                        events += response.eventsList
                        latest = response.latestReplayId.takeUnless { it.isEmpty } ?: response.eventsList.lastOrNull()?.replayId ?: latest
                    }
            }
        } catch (e: StatusException) {
            throw classify(e.status, e.trailers, topic, replayId != null)
        } catch (e: StatusRuntimeException) {
            throw classify(e.status, e.trailers, topic, replayId != null)
        }
        return Pulled(events, latest)
    }

    suspend fun schema(
        auth: PubSubAuth,
        schemaId: String
    ): Schema {
        schemas[schemaId]?.let { return it }
        val info =
            try {
                ClientCalls.unaryRpc(
                    channel,
                    PubSubGrpc.getGetSchemaMethod(),
                    SchemaRequest.newBuilder().setSchemaId(schemaId).build(),
                    CallOptions.DEFAULT,
                    auth.metadata
                )
            } catch (e: StatusException) {
                throw classify(e.status, e.trailers, "schema", false)
            } catch (e: StatusRuntimeException) {
                throw classify(e.status, e.trailers, "schema", false)
            }
        val parsed = Schema.Parser().parse(info.schemaJson)
        schemas[schemaId] = parsed
        return parsed
    }

    companion object {
        // salesforce names the cause in an "error-code" trailer (sfdc.platform.eventbus.grpc...); see ADR-0024
        val ERROR_CODE: Metadata.Key<String> = Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER)

        private val REFUSALS = setOf(Status.Code.INVALID_ARGUMENT, Status.Code.FAILED_PRECONDITION, Status.Code.NOT_FOUND, Status.Code.OUT_OF_RANGE)

        // only a refused stored replay id is Permanent: the sync engine turns exactly that into a cursor reset and a
        // full read. anything else must fail the run instead, or a wrong channel would read everything every run
        fun classify(
            status: Status,
            trailers: Metadata?,
            what: String,
            customReplay: Boolean
        ): ConnectorException {
            val code = trailers?.get(ERROR_CODE)
            val message = "salesforce pub/sub ${status.code}${code?.let { " ($it)" } ?: ""} on $what"
            if (customReplay && status.code in REFUSALS && replayRefused(code, status.description)) {
                return ConnectorException.Permanent(message, mapOf("reason" to REPLAY_REFUSED) + listOfNotNull(code?.let { "errorCode" to it }))
            }
            return when (status.code) {
                Status.Code.UNAUTHENTICATED -> ConnectorException.AuthExpired(message)
                Status.Code.RESOURCE_EXHAUSTED -> ConnectorException.RateLimited(message, null)
                Status.Code.NOT_FOUND -> ConnectorException.NotFound(message)
                Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.INTERNAL, Status.Code.ABORTED, Status.Code.UNKNOWN,
                Status.Code.CANCELLED -> ConnectorException.Transient(message)
                // permission, entitlement, a channel salesforce does not know: a person must act
                else -> ConnectorException.PermissionDenied(message)
            }
        }

        private fun replayRefused(
            code: String?,
            description: String?
        ): Boolean = code?.contains("replayid", ignoreCase = true) == true || description?.contains("replay", ignoreCase = true) == true

        const val REPLAY_REFUSED = "replay_id_refused"
    }
}
