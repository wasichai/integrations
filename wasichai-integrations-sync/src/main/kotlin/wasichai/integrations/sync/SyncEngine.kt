package wasichai.integrations.sync

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ValidationException
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationEvent
import wasichai.integrations.IntegrationEventListener
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.Job
import wasichai.integrations.JobHandler
import wasichai.integrations.JobRepository
import wasichai.integrations.JobRunner
import wasichai.integrations.PermanentJobFailure
import wasichai.integrations.Transactions
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.WriteResult
import wasichai.integrations.sync.autoconfigure.SyncProperties
import java.time.Clock
import java.util.UUID

// runs a definition: reads each side's changes, pairs records through links, merges against the snapshot
// and writes only what the rules allow (sync-semantics.md). one run per definition at a time: the job key.
class SyncEngine(
    private val definitions: SyncDefinitionRepository,
    private val state: SyncStateRepository,
    private val runs: SyncRunRepository,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts,
    private val jobs: JobRepository,
    private val tx: Transactions,
    private val meters: IntegrationsMeters,
    private val properties: SyncProperties,
    private val clock: Clock
) : JobHandler,
    IntegrationEventListener {
    private val log = LoggerFactory.getLogger(javaClass)

    override val kind = KIND

    // null when a run of this definition is already waiting or running
    suspend fun enqueue(
        organizationId: UUID,
        definitionId: UUID,
        trigger: RunTrigger
    ): UUID? {
        val now = clock.instant()
        val runId = runs.queue(organizationId, definitionId, trigger, now)
        val payload = mapOf("definitionId" to definitionId.toString(), "runId" to runId.toString(), "trigger" to trigger.name)
        if (jobs.enqueue(organizationId, KIND, payload, "sync:$definitionId", now) == null) {
            runs.drop(runId)
            return null
        }
        return runId
    }

    // an event is a hint: run the definitions it may concern; their feeds read what really changed
    override suspend fun onEvent(event: IntegrationEvent): Boolean {
        val concerned =
            definitions.activeOnConnection(event.organizationId, event.connectionId).filter { d ->
                Side.entries.any { s ->
                    d.side(s).connectionId == event.connectionId &&
                        (event.resourceType == null || d.side(s).resource == event.resourceType) &&
                        d.direction.allows(s, s.other)
                }
            }
        concerned.forEach { enqueue(event.organizationId, it.id, RunTrigger.EVENT) }
        return concerned.isNotEmpty()
    }

    override suspend fun handle(job: Job) {
        val organizationId = job.organizationId
        val definitionId = UUID.fromString(job.payload.path("definitionId").asString())
        val runId = UUID.fromString(job.payload.path("runId").asString())
        val trigger = RunTrigger.valueOf(job.payload.path("trigger").asString())
        val definition = definitions.find(organizationId, definitionId) ?: return
        val counts = RunCounts()
        runs.start(runId, clock.instant())
        var outcome = "failed"
        try {
            if (definition.status != DefinitionStatus.ACTIVE) {
                runs.finish(runId, RunStatus.FAILED, counts, "the definition is not active", clock.instant(), "permanent")
                return
            }
            Run(definition, trigger, counts).execute()
            val status = if (counts.failed > 0) RunStatus.PARTIAL else RunStatus.SUCCEEDED
            runs.finish(runId, status, counts, null, clock.instant())
            outcome = status.name.lowercase()
        } catch (e: Exception) {
            // transient: the job tries again later; permanent: a person must act (operators read it on the status)
            val (retryable, _, _) = JobRunner.classify(e)
            val kind = if (retryable) "transient" else "permanent"
            runs.finish(runId, RunStatus.FAILED, counts, "${e.javaClass.simpleName}: ${e.message}", clock.instant(), kind)
            if (e is ConnectorException.AuthExpired) throw PermanentJobFailure("connection needs a new authorization", e)
            throw e
        } finally {
            record(counts, trigger, outcome)
        }
    }

    // a person settled a conflict: write the chosen values where the direction allows, then the pair runs again
    suspend fun applyResolution(
        definition: SyncDefinition,
        link: RecordLink,
        conflict: ConflictView,
        choice: Side?,
        values: Map<String, JsonNode>,
        picks: Map<String, String> = emptyMap()
    ) {
        val run = Run(definition, RunTrigger.MANUAL, RunCounts())
        val a = run.read(Side.A, link.aId!!) ?: throw ConflictException("The record in A is gone; resolve again after the next run")
        val b = run.read(Side.B, link.bId!!) ?: throw ConflictException("The record in B is gone; resolve again after the next run")
        val keys =
            (0 until conflict.fields.size())
                .map {
                    conflict.fields
                        .get(it)
                        .path("key")
                        .asString()
                }.toSet()
        val writes = mapOf(Side.A to mutableMapOf<String, FieldValue>(), Side.B to mutableMapOf())
        val violations = mutableListOf<FieldViolation>()
        for (m in definition.mappings.filter { it.key in keys }) {
            val transform = Transform.of(m)
            val chosenA =
                Resolution.chooseA(
                    m,
                    transform,
                    a.fields[m.aField] ?: FieldValue.Null,
                    b.fields[m.bField] ?: FieldValue.Null,
                    choice,
                    values[m.aField],
                    picks[m.key],
                    violations
                ) ?: continue
            val chosenB = transform.toB(chosenA)
            if (definition.direction.allows(Side.B, Side.A) &&
                !FieldValues.same(chosenA, a.fields[m.aField] ?: FieldValue.Null)
            ) {
                writes.getValue(Side.A)[m.aField!!] = chosenA
            }
            if (definition.direction.allows(Side.A, Side.B) &&
                !FieldValues.same(chosenB, b.fields[m.bField] ?: FieldValue.Null)
            ) {
                writes.getValue(Side.B)[m.bField!!] = chosenB
            }
        }
        if (violations.isNotEmpty()) throw ValidationException("Invalid resolution", violations)
        try {
            writes.forEach { (side, fields) -> if (fields.isNotEmpty()) run.update(side, link.id(side)!!, fields, (if (side == Side.A) a else b).version) }
        } catch (e: ConnectorException.Conflict) {
            throw ConflictException("A record changed while resolving; look at it again")
        }
        val afterA = run.read(Side.A, a.id) ?: a
        val afterB = run.read(Side.B, b.id) ?: b
        val base = state.snapshot(link.id).orEmpty().toMutableMap()
        definition.mappings.filter { it.key in keys }.forEach { m ->
            base[m.key] =
                Agreed(afterA.fields[m.aField] ?: FieldValue.Null, afterB.fields[m.bField] ?: FieldValue.Null)
        }
        tx.run {
            state.saveSnapshot(link.id, base, clock.instant())
            // no hashes: the next run compares the pair again and carries any other pending change
            state.settle(link, a.id, b.id, afterA.version, afterB.version, null, null, clock.instant())
        }
    }

    private fun record(
        counts: RunCounts,
        trigger: RunTrigger,
        outcome: String
    ) {
        val registry = meters.registry
        registry.counter("integrations.sync.runs", "kind", trigger.name.lowercase(), "outcome", outcome).increment()
        registry.counter("integrations.sync.reconcile", "finding", "missing").increment(counts.missing.toDouble())
        registry.counter("integrations.sync.reconcile", "finding", "diverged").increment(counts.diverged.toDouble())
        registry.counter("integrations.sync.reconcile", "finding", "cursor_reset").increment(counts.cursorResets.toDouble())
        registry.counter("integrations.sync.records", "outcome", "created").increment(counts.created.toDouble())
        registry.counter("integrations.sync.records", "outcome", "updated").increment(counts.updated.toDouble())
        registry.counter("integrations.sync.records", "outcome", "skipped").increment(counts.skipped.toDouble())
        registry.counter("integrations.sync.records", "outcome", "failed").increment(counts.failed.toDouble())
        registry.counter("integrations.sync.conflicts").increment(counts.conflicts.toDouble())
    }

    // one execution: the connectors, contexts and a lazily built match index for each side
    private inner class Run(
        val definition: SyncDefinition,
        val trigger: RunTrigger,
        val counts: RunCounts
    ) {
        private val organizationId = definition.organizationId
        private val sides = mutableMapOf<Side, Pair<ConnectorContext, Any>>()
        private val matchIndex = mutableMapOf<Side, Map<String, ProviderRecord>>()

        // marker mode: the target's records by the create token they carry, read once per run
        private val tokenIndex = mutableMapOf<Side, Map<String, ProviderRecord>>()
        private val reconcile = trigger == RunTrigger.RECONCILE
        private val startedAt = clock.instant()

        // a reconcile reads every side whole: what it saw, by id, to find what is missing or drifted
        private val seen = mutableMapOf<Side, MutableMap<String, ProviderRecord>>()

        private suspend fun side(side: Side): Pair<ConnectorContext, Any> =
            sides.getOrPut(side) {
                val connection =
                    connections.find(organizationId, definition.side(side).connectionId!!)
                        ?: throw PermanentJobFailure("connection of side $side is gone")
                val connector = registry.find(connection.connectorId) ?: throw PermanentJobFailure("connector '${connection.connectorId}' is not installed")
                contexts.forConnection(connection) to connector
            }

        private fun resource(side: Side) = definition.side(side).resource!!

        suspend fun read(
            side: Side,
            id: String
        ): ProviderRecord? {
            val (context, connector) = side(side)
            val reader = connector as? ResourceReader ?: throw PermanentJobFailure("side $side cannot read records")
            return reader.read(context, resource(side), id)
        }

        suspend fun update(
            side: Side,
            id: String,
            fields: Map<String, FieldValue>,
            expectedVersion: String?
        ) {
            val (context, connector) = side(side)
            (
                connector as? ResourceWriter ?: throw PermanentJobFailure(
                    "side $side cannot write records"
                )
            ).update(context, resource(side), id, fields, expectedVersion)
        }

        suspend fun execute() {
            recoverPending()
            if (trigger == RunTrigger.INITIAL) Side.entries.forEach { state.saveCursor(definition.id, it, null, clock.instant()) }
            for (side in Side.entries) {
                // a reconcile reads a side that never flows too: drift and deletions there count as well
                if (definition.direction.allows(side, side.other) || reconcile) feed(side)
            }
            // a reconcile, or a side whose checkpoint was refused and was read whole again
            if (seen.isNotEmpty()) reconcileLinks()
        }

        // what the feeds could not say (reconcile only, after reading both sides whole): a linked record that is gone,
        // or a target that drifted from what was agreed without a change anyone saw. links settled during this run
        // are left alone: their records were written after the side was read.
        private suspend fun reconcileLinks() {
            for (link in state.inState(definition.id, LinkState.LINKED)) {
                if (link.lastSyncedAt?.isBefore(startedAt) != true) continue
                val missing = Side.entries.firstOrNull { side -> seen.containsKey(side) && link.id(side) != null && link.id(side) !in seen.getValue(side) }
                if (missing != null) {
                    counts.missing++
                    log.info("integrations: sync {} reconcile: record {} of {} is gone", definition.id, link.id(missing), missing)
                    deleted(missing, link.id(missing)!!)
                    continue
                }
                // a side that does not flow was read but not processed: a drift there is put back by the other side
                for (side in Side.entries.filterNot { definition.direction.allows(it, it.other) }) {
                    val record = seen[side]?.get(link.id(side)) ?: continue
                    if (FieldValues.hash(MergePlanner.mapped(definition.mappings, side, record.fields)) == link.hash(side)) continue
                    val other = seen[side.other]?.get(link.id(side.other)) ?: read(side.other, link.id(side.other)!!) ?: continue
                    counts.diverged++
                    merge(link, side.other, other, record, adopt = false)
                }
            }
        }

        // a create that started and never settled: the record may exist on the other side or not. the target's
        // externalIdField finds it again (upsert by the create token, or a lookup of the token in marker mode);
        // without one the pair is ORPHANED with an open conflict for an operator, never created twice.
        private suspend fun recoverPending() {
            state.inState(definition.id, LinkState.PENDING_CREATE).forEach { link ->
                val source = if (link.aId != null) Side.A else Side.B
                val target = source.other
                val mode = CreateMode.of(definition.side(target).externalIdField, side(target).second)
                val record = if (mode == CreateMode.NONE) null else read(source, link.id(source)!!)
                if (record == null) {
                    orphan(link, if (mode == CreateMode.NONE) "was left creating" else "was left creating and its source record is gone")
                    return@forEach
                }
                try {
                    if (mode == CreateMode.MARKER) {
                        val found = byToken(target, link.createToken.toString())
                        if (found != null) {
                            adoptCreated(link, source, record, found)
                            return@forEach
                        }
                    }
                    write(link, source, record, recovering = true)
                } catch (e: ConnectorException) {
                    if (e.retryable) throw e
                    // refused now, though the first attempt may have created it: a person decides, nothing is repeated
                    orphan(link, "could not be recovered (${e.javaClass.simpleName})")
                }
            }
        }

        private suspend fun orphan(
            link: RecordLink,
            why: String
        ) {
            tx.run {
                state.setState(link.id, LinkState.ORPHANED, clock.instant())
                runs.openConflict(organizationId, definition.id, link.id, ConflictKind.ORPHANED_CREATE, emptyList(), clock.instant())
            }
            counts.failed++
            counts.conflicts++
            log.warn("integrations: sync {} link {} {}; marked ORPHANED", definition.id, link.id, why)
        }

        // the record a cut-short create left behind, found by its token: link it, then carry what changed since
        private suspend fun adoptCreated(
            link: RecordLink,
            source: Side,
            record: ProviderRecord,
            found: ProviderRecord
        ) {
            val ids = if (source == Side.A) record.id to found.id else found.id to record.id
            val aVersion = if (source == Side.A) record.version else found.version
            val bVersion = if (source == Side.A) found.version else record.version
            state.settle(link, ids.first, ids.second, aVersion, bVersion, null, null, clock.instant())
            log.info("integrations: sync {} link {} recovered: record {} found by its create token", definition.id, link.id, found.id)
            merge(link.copy(aId = ids.first, bId = ids.second), source, record, found, adopt = true)
        }

        private suspend fun byToken(
            target: Side,
            token: String
        ): ProviderRecord? {
            val field = definition.side(target).externalIdField!!
            val index =
                tokenIndex.getOrPut(target) {
                    val (context, connector) = side(target)
                    val reader = connector as? ResourceReader ?: throw PermanentJobFailure("side $target cannot read records")
                    val all = mutableMapOf<String, ProviderRecord>()
                    var cursor: String? = null
                    do {
                        val page = reader.list(context, ReadRequest(resource(target), cursor, properties.pageSize))
                        page.records.forEach { r -> CreateMode.token(r, field)?.let { all.putIfAbsent(it, r) } }
                        cursor = page.nextCursor
                    } while (cursor != null)
                    all
                }
            return index[token]
        }

        private suspend fun feed(source: Side) {
            val (context, connector) = side(source)
            // a reconcile reads everything again and leaves the cursor where it was
            val keepCursor = !reconcile
            val flows = definition.direction.allows(source, source.other)
            var all = if (reconcile) mutableMapOf<String, ProviderRecord>() else null
            if (connector is ChangeFeed) {
                var checkpoint = if (keepCursor) state.cursor(definition.id, source) else null
                var reset = false
                while (true) {
                    val page =
                        try {
                            connector.changes(context, resource(source), checkpoint, properties.pageSize)
                        } catch (e: ConnectorException.Permanent) {
                            // a checkpoint the provider no longer accepts (expired, too old): start this side over once.
                            // the full read that follows is harmless: links and hashes skip what is already in step
                            if (checkpoint == null || reset) throw e
                            log.warn("integrations: sync {} side {} checkpoint refused ({}); reading from the start", definition.id, source, e.message)
                            reset = true
                            counts.cursorResets++
                            checkpoint = null
                            // read whole now: what it sees feeds the same missing and drift checks as a reconcile
                            all = mutableMapOf()
                            state.saveCursor(definition.id, source, null, clock.instant())
                            continue
                        }
                    val collect = all
                    page.changes.forEach { change ->
                        if (change is Change.Upserted) collect?.put(change.record.id, change.record)
                        if (flows) process(source, change) else counts.read++
                    }
                    checkpoint = page.checkpoint
                    // saved after the page: a crash re-reads it, and links and hashes make that harmless
                    if (keepCursor) state.saveCursor(definition.id, source, checkpoint, clock.instant())
                    if (!page.hasMore || page.changes.isEmpty()) break
                }
            } else {
                val reader = connector as? ResourceReader ?: throw PermanentJobFailure("side $source can neither feed nor list records")
                var cursor: String? = null
                do {
                    val page = reader.list(context, ReadRequest(resource(source), cursor, properties.pageSize))
                    page.records.forEach { record ->
                        all?.put(record.id, record)
                        if (flows) process(source, Change.Upserted(record)) else counts.read++
                    }
                    cursor = page.nextCursor
                } while (cursor != null)
            }
            // only a side read to its end counts as seen: a failure above leaves it out, so nothing is called missing
            if (all != null) seen[source] = all
        }

        private suspend fun process(
            source: Side,
            change: Change
        ) {
            counts.read++
            try {
                when (change) {
                    is Change.Upserted -> upsert(source, change.record)
                    is Change.Deleted -> deleted(source, change.id)
                }
            } catch (e: ConnectorException) {
                // a provider that is down or limiting fails the run (the job retries); a bad record fails alone
                if (e.retryable) throw e
                counts.failed++
                log.warn("integrations: sync {} record {} of {} failed: {}", definition.id, change.id, source, e.javaClass.simpleName)
            }
        }

        private suspend fun upsert(
            source: Side,
            record: ProviderRecord
        ) {
            val target = source.other
            val link = state.link(definition.id, source, record.id)
            if (link == null) {
                adoptOrCreate(source, record)
                return
            }
            if (link.state != LinkState.LINKED) {
                counts.skipped++
                return
            }
            // our own write coming back, or nothing that the sync cares about changed
            if (FieldValues.hash(MergePlanner.mapped(definition.mappings, source, record.fields)) == link.hash(source)) {
                counts.skipped++
                return
            }
            // in a reconcile, a record that moved with no change seen in the feed is drift
            if (reconcile) counts.diverged++
            val other = read(target, link.id(target)!!)
            if (other == null) {
                deleted(target, link.id(target)!!)
                return
            }
            merge(link, source, record, other, adopt = false)
        }

        private suspend fun adoptOrCreate(
            source: Side,
            record: ProviderRecord
        ) {
            val target = source.other
            if (!definition.direction.allows(source, target)) {
                counts.skipped++
                return
            }
            val match = match(source, record)
            if (match != null) {
                val ids = if (source == Side.A) record.id to match.id else match.id to record.id
                val link = state.insertLinked(organizationId, definition.id, ids.first, ids.second, clock.instant())
                merge(link, source, record, match, adopt = true)
                return
            }
            create(source, record)
        }

        // an existing record on the other side with the same matchOn value and no link yet
        private suspend fun match(
            source: Side,
            record: ProviderRecord
        ): ProviderRecord? {
            val on = definition.matchOn ?: return null
            val target = source.other
            val sourceField = if (source == Side.A) on.aField!! else on.bField!!
            val targetField = if (target == Side.A) on.aField!! else on.bField!!
            val value = record.fields[sourceField]?.takeUnless { it is FieldValue.Null } ?: return null
            val index =
                matchIndex.getOrPut(target) {
                    val (context, connector) = side(target)
                    val reader = connector as? ResourceReader ?: return null
                    val all = mutableMapOf<String, ProviderRecord>()
                    var cursor: String? = null
                    do {
                        val page = reader.list(context, ReadRequest(resource(target), cursor, properties.pageSize))
                        page.records.forEach { r ->
                            r.fields[targetField]?.takeUnless { it is FieldValue.Null }?.let { all.putIfAbsent(FieldValues.canonical(it), r) }
                        }
                        cursor = page.nextCursor
                    } while (cursor != null)
                    all
                }
            val candidate = index[FieldValues.canonical(value)] ?: return null
            return if (state.link(definition.id, target, candidate.id) == null) candidate else null
        }

        private suspend fun create(
            source: Side,
            record: ProviderRecord
        ) {
            // committed first: a crash after the provider call leaves the token to find the record by
            val link = state.insertPending(organizationId, definition.id, source, record.id, clock.instant())
            write(link, source, record, recovering = false)
        }

        // the create of a pending link: an upsert keyed by its create token where the target can, otherwise a create
        // that carries the token in the marker field when one is set
        private suspend fun write(
            link: RecordLink,
            source: Side,
            record: ProviderRecord,
            recovering: Boolean
        ) {
            val target = source.other
            val fields =
                definition.mappings
                    .filter { it.flows(definition.direction, source, target) }
                    .associate { m -> m.field(target) to Transform.of(m).apply(record.fields[m.field(source)] ?: FieldValue.Null, target) }
            val key = definition.side(target).externalIdField
            val token = link.createToken.toString()
            val (context, connector) = side(target)
            val mode = CreateMode.of(key, connector)
            val result: WriteResult =
                try {
                    when (mode) {
                        CreateMode.UPSERT -> (connector as ResourceUpserter).upsert(context, resource(target), key!!, token, fields)
                        CreateMode.MARKER -> writer(target, connector).create(context, resource(target), fields + (key!! to FieldValue.Text(token)))
                        CreateMode.NONE -> writer(target, connector).create(context, resource(target), fields)
                    }
                } catch (e: ConnectorException) {
                    // refused for sure: nothing exists over there, so the pending link goes; a timeout keeps it.
                    // a retried create may have succeeded the first time: recovery decides, never this delete
                    if (!e.retryable && !recovering) state.delete(link.id)
                    throw e
                }
            val created = read(target, result.id) ?: ProviderRecord(result.id, result.version, result.modifiedAt, fields)
            settle(link, source, record, created, emptySet(), null)
            if (recovering) log.info("integrations: sync {} link {} recovered: record {} written with its create token", definition.id, link.id, result.id)
            // an upsert that found the record from a cut-short create updated it; anything else created one
            if (mode != CreateMode.UPSERT || result.created) counts.created++ else counts.updated++
        }

        private fun writer(
            side: Side,
            connector: Any
        ): ResourceWriter = connector as? ResourceWriter ?: throw PermanentJobFailure("side $side cannot write records")

        private suspend fun merge(
            link: RecordLink,
            source: Side,
            sourceRecord: ProviderRecord,
            targetRecord: ProviderRecord,
            adopt: Boolean
        ) {
            var s = sourceRecord
            var t = targetRecord
            val snapshot = if (adopt) null else state.snapshot(link.id)
            repeat(2) { attempt ->
                val a = if (source == Side.A) s else t
                val b = if (source == Side.A) t else s
                val plan = MergePlanner.plan(definition.mappings, definition.direction, source, a.fields, b.fields, snapshot, a.modifiedAt, b.modifiedAt)
                if (plan.conflicts.isNotEmpty()) {
                    hold(link, plan.conflicts)
                    return
                }
                if (!plan.hasWrites) {
                    settle(link, source, s, t, plan.kept, snapshot)
                    counts.skipped++
                    return
                }
                try {
                    plan.writes.forEach { (side, fields) ->
                        if (fields.isNotEmpty()) update(side, link.id(side)!!, fields, if (side == Side.A) a.version else b.version)
                    }
                } catch (e: ConnectorException.Conflict) {
                    // it moved between our read and our write: read again and merge once more, then a person decides
                    if (attempt == 1) {
                        hold(link, contested(plan, a, b, snapshot))
                        return
                    }
                    s = read(source, s.id) ?: return deleted(source, s.id)
                    t = read(source.other, t.id) ?: return deleted(source.other, t.id)
                    return@repeat
                }
                val afterS = read(source, s.id) ?: s
                val afterT = read(source.other, t.id) ?: t
                settle(link, source, afterS, afterT, plan.kept, snapshot)
                counts.updated++
                return
            }
        }

        // the mappings we tried to write, keyed like MergePlanner's conflicts so resolve finds them
        private fun contested(
            plan: MergePlan,
            a: ProviderRecord,
            b: ProviderRecord,
            snapshot: Map<String, Agreed>?
        ): List<ConflictField> {
            val toA = plan.writes[Side.A].orEmpty().keys
            val toB = plan.writes[Side.B].orEmpty().keys
            return definition.mappings
                .filter { it.aField in toA || it.bField in toB }
                .map { m ->
                    ConflictField(
                        m.key,
                        m.aField!!,
                        m.bField!!,
                        a.fields[m.aField] ?: FieldValue.Null,
                        b.fields[m.bField] ?: FieldValue.Null,
                        snapshot?.get(m.key)
                    )
                }
        }

        // nothing is written for a pair with a conflict until a person resolves it
        private suspend fun hold(
            link: RecordLink,
            fields: List<ConflictField>
        ) {
            tx.run {
                state.setState(link.id, LinkState.CONFLICT, clock.instant())
                runs.openConflict(organizationId, definition.id, link.id, ConflictKind.FIELDS, fields, clock.instant())
            }
            counts.conflicts++
        }

        private suspend fun settle(
            link: RecordLink,
            source: Side,
            sourceRecord: ProviderRecord,
            targetRecord: ProviderRecord,
            kept: Set<String>,
            base: Map<String, Agreed>?
        ) {
            val a = if (source == Side.A) sourceRecord else targetRecord
            val b = if (source == Side.A) targetRecord else sourceRecord
            val snapshot =
                definition.mappings.associate { m ->
                    m.key to
                        (base?.get(m.key)?.takeIf { m.key in kept } ?: Agreed(a.fields[m.aField] ?: FieldValue.Null, b.fields[m.bField] ?: FieldValue.Null))
                }
            val aHash = FieldValues.hash(MergePlanner.mapped(definition.mappings, Side.A, a.fields))
            val bHash = FieldValues.hash(MergePlanner.mapped(definition.mappings, Side.B, b.fields))
            tx.run {
                state.settle(link, a.id, b.id, a.version, b.version, aHash, bHash, clock.instant())
                state.saveSnapshot(link.id, snapshot, clock.instant())
            }
        }

        private suspend fun deleted(
            source: Side,
            id: String
        ) {
            val link = state.link(definition.id, source, id)
            if (link == null) {
                counts.skipped++
                return
            }
            val target = source.other
            when {
                definition.deletePolicy == DeletePolicy.PROPAGATE && definition.direction.allows(source, target) -> {
                    link.id(target)?.let { targetId ->
                        val (context, connector) = side(target)
                        try {
                            (connector as? ResourceWriter)?.delete(context, resource(target), targetId)
                        } catch (_: ConnectorException.NotFound) {
                            // already gone there: the goal is met
                        }
                    }
                    state.delete(link.id)
                    counts.updated++
                }
                definition.deletePolicy == DeletePolicy.FLAG -> {
                    tx.run {
                        state.setState(link.id, LinkState.ORPHANED, clock.instant())
                        runs.openConflict(organizationId, definition.id, link.id, ConflictKind.DELETED, emptyList(), clock.instant())
                    }
                    counts.conflicts++
                }
                else -> {
                    state.setState(link.id, LinkState.ORPHANED, clock.instant())
                    counts.skipped++
                }
            }
        }
    }

    companion object {
        const val KIND = "integrations.sync.run"
    }
}
