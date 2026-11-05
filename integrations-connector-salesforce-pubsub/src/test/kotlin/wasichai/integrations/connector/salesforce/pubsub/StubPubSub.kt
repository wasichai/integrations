package wasichai.integrations.connector.salesforce.pubsub

import com.google.protobuf.ByteString
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import wasichai.integrations.connector.salesforce.pubsub.proto.ConsumerEvent
import wasichai.integrations.connector.salesforce.pubsub.proto.FetchRequest
import wasichai.integrations.connector.salesforce.pubsub.proto.FetchResponse
import wasichai.integrations.connector.salesforce.pubsub.proto.ProducerEvent
import wasichai.integrations.connector.salesforce.pubsub.proto.PubSubGrpc
import wasichai.integrations.connector.salesforce.pubsub.proto.ReplayPreset
import wasichai.integrations.connector.salesforce.pubsub.proto.SchemaInfo
import wasichai.integrations.connector.salesforce.pubsub.proto.SchemaRequest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

// salesforce's pub/sub api in process: one retained stream per topic, Subscribe from EARLIEST, LATEST or after a
// replay id, GetSchema by id. records what it was asked and with which headers. deterministic, no network
class StubPubSub : AutoCloseable {
    class Retained(
        val replayId: ByteArray,
        val schemaId: String,
        val payload: ByteArray
    )

    class Call(
        val method: String,
        val headers: Map<String, String>,
        val fetch: FetchRequest? = null
    )

    private val name = "pubsub-" + UUID.randomUUID()
    private val topics = mutableMapOf<String, MutableList<Retained>>()
    private val schemas = mutableMapOf<String, String>()
    private val recorded = CopyOnWriteArrayList<Call>()

    // tokens the stub answers UNAUTHENTICATED to
    val refusedTokens = mutableSetOf<String>()

    // when set, a Subscribe answers this status (and error-code trailer) instead of events
    var failSubscribe: Pair<Status, String?>? = null

    // what salesforce answers for a replay id it no longer has (ADR-0024): INVALID_ARGUMENT with an error-code trailer
    var refuseUnknownReplay = true

    val calls: List<Call> get() = recorded.toList()

    fun fetches(): List<FetchRequest> = recorded.mapNotNull { it.fetch }

    @Synchronized
    fun publish(
        topic: String,
        schemaId: String,
        payload: ByteArray
    ): ByteArray {
        val list = topics.getOrPut(topic) { mutableListOf() }
        // opaque to clients; deliberately not a counter they could compute
        val replay = UUID.randomUUID().toString().toByteArray()
        list += Retained(replay, schemaId, payload)
        return replay
    }

    @Synchronized
    fun schema(
        id: String,
        json: String
    ) {
        schemas[id] = json
    }

    // the oldest retained events fall out of the 72-hour window
    @Synchronized
    fun expire(
        topic: String,
        count: Int
    ) {
        repeat(count) { topics[topic]?.removeAt(0) }
    }

    private val headerCatcher =
        object : ServerInterceptor {
            override fun <Q, R> interceptCall(
                call: ServerCall<Q, R>,
                headers: Metadata,
                next: ServerCallHandler<Q, R>
            ): ServerCall.Listener<Q> {
                val seen =
                    headers.keys().filterNot { it.endsWith("-bin") }.associateWith {
                        headers.get(Metadata.Key.of(it, Metadata.ASCII_STRING_MARSHALLER))!!
                    }
                return Contexts.interceptCall(Context.current().withValue(HEADERS, seen), call, headers, next)
            }
        }

    private val service =
        object : PubSubGrpc.PubSubImplBase() {
            override fun subscribe(responses: StreamObserver<FetchResponse>): StreamObserver<FetchRequest> {
                val headers = HEADERS.get() ?: emptyMap()
                return object : StreamObserver<FetchRequest> {
                    override fun onNext(request: FetchRequest) {
                        recorded += Call("Subscribe", headers, request)
                        answer(request, headers, responses)
                    }

                    override fun onError(t: Throwable) = Unit

                    override fun onCompleted() = responses.onCompleted()
                }
            }

            override fun getSchema(
                request: SchemaRequest,
                responses: StreamObserver<SchemaInfo>
            ) {
                val headers = HEADERS.get() ?: emptyMap()
                recorded += Call("GetSchema", headers)
                val json = synchronized(this@StubPubSub) { schemas[request.schemaId] }
                if (json == null) {
                    responses.onError(Status.NOT_FOUND.withDescription("no schema ${request.schemaId}").asException())
                    return
                }
                responses.onNext(
                    SchemaInfo
                        .newBuilder()
                        .setSchemaId(request.schemaId)
                        .setSchemaJson(json)
                        .build()
                )
                responses.onCompleted()
            }
        }

    @Synchronized
    private fun answer(
        request: FetchRequest,
        headers: Map<String, String>,
        responses: StreamObserver<FetchResponse>
    ) {
        if (headers["accesstoken"] in refusedTokens) {
            responses.onError(Status.UNAUTHENTICATED.withDescription("invalid session").asException())
            return
        }
        failSubscribe?.let { (status, code) ->
            responses.onError(status.asException(trailers(code)))
            return
        }
        val retained = topics[request.topicName].orEmpty()
        val start =
            when (request.replayPreset) {
                ReplayPreset.EARLIEST -> 0
                ReplayPreset.LATEST -> retained.size
                else -> {
                    val at = retained.indexOfFirst { it.replayId.contentEquals(request.replayId.toByteArray()) }
                    if (at < 0 && refuseUnknownReplay) {
                        val status = Status.INVALID_ARGUMENT.withDescription("The Replay ID validation failed. Ensure that the Replay ID is valid.")
                        responses.onError(status.asException(trailers(REPLAY_CORRUPTED)))
                        return
                    }
                    at + 1
                }
            }
        val events = retained.drop(start).take(request.numRequested)
        if (events.isEmpty()) return
        val response =
            FetchResponse
                .newBuilder()
                .setLatestReplayId(ByteString.copyFrom(events.last().replayId))
                .setPendingNumRequested(request.numRequested - events.size)
        events.forEach {
            response.addEvents(
                ConsumerEvent
                    .newBuilder()
                    .setReplayId(ByteString.copyFrom(it.replayId))
                    .setEvent(
                        ProducerEvent
                            .newBuilder()
                            .setId(UUID.randomUUID().toString())
                            .setSchemaId(it.schemaId)
                            .setPayload(ByteString.copyFrom(it.payload))
                    )
            )
        }
        // the stream stays open, as salesforce keeps it open waiting for more
        responses.onNext(response.build())
    }

    private fun trailers(code: String?) = Metadata().apply { code?.let { put(PubSubClient.ERROR_CODE, it) } }

    private val server: Server =
        InProcessServerBuilder
            .forName(name)
            .addService(ServerInterceptors.intercept(service, headerCatcher))
            .build()
            .start()

    fun channel(): ManagedChannel = InProcessChannelBuilder.forName(name).build()

    override fun close() {
        server.shutdownNow()
    }

    companion object {
        const val REPLAY_CORRUPTED = "sfdc.platform.eventbus.grpc.subscription.fetch.replayid.corrupted"

        // what the call carried, handed from the interceptor to the handlers through the grpc context
        private val HEADERS: Context.Key<Map<String, String>> = Context.key("headers")
    }
}
