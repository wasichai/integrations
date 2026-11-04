package wasichai.integrations

import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// r2dbc-postgresql binds timestamptz as OffsetDateTime
internal fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

// a transaction around a suspending block. the operator is made on first use: wiring tests run without a database
class Transactions(
    factory: () -> org.springframework.transaction.reactive.TransactionalOperator
) {
    private val operator by lazy(factory)

    suspend fun <T> run(block: suspend () -> T): T = operator.executeAndAwait { block() }
}

// the app's meter registry when it has one (actuator), a private one otherwise: code never checks
class IntegrationsMeters(
    val registry: io.micrometer.core.instrument.MeterRegistry
)
