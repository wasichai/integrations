package wasichai.integrations

import io.netty.resolver.AddressResolver
import io.netty.resolver.AddressResolverGroup
import io.netty.resolver.InetNameResolver
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Promise
import reactor.core.scheduler.Schedulers
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException

// the address a provider call connects to is the address that was checked: netty asks this resolver when it
// connects, so there is no second lookup a dns rebinding could answer differently (ADR-0011).
// lookups run off the event loop, on reactor's bounded elastic workers.
class GuardedResolverGroup(
    private val allowPrivate: Boolean,
    private val lookup: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() }
) : AddressResolverGroup<InetSocketAddress>() {
    override fun newResolver(executor: EventExecutor): AddressResolver<InetSocketAddress> =
        GuardedNameResolver(executor, allowPrivate, lookup).asAddressResolver()
}

class GuardedNameResolver(
    executor: EventExecutor,
    private val allowPrivate: Boolean,
    private val lookup: (String) -> List<InetAddress>
) : InetNameResolver(executor) {
    override fun doResolve(
        inetHost: String,
        promise: Promise<InetAddress>
    ) {
        resolveChecked(inetHost, { promise.trySuccess(it.first()) }, { promise.tryFailure(it) })
    }

    override fun doResolveAll(
        inetHost: String,
        promise: Promise<MutableList<InetAddress>>
    ) {
        resolveChecked(inetHost, { promise.trySuccess(it.toMutableList()) }, { promise.tryFailure(it) })
    }

    private fun resolveChecked(
        host: String,
        success: (List<InetAddress>) -> Unit,
        failure: (Throwable) -> Unit
    ) {
        Schedulers.boundedElastic().schedule {
            try {
                val addresses = lookup(host)
                if (addresses.isEmpty()) throw UnknownHostException(host)
                checked(host, addresses, allowPrivate)
                success(addresses)
            } catch (e: Throwable) {
                failure(e)
            }
        }
    }

    companion object {
        // every address must be public: a name answering one public and one private address is refused
        fun checked(
            host: String,
            addresses: List<InetAddress>,
            allowPrivate: Boolean
        ): List<InetAddress> {
            if (allowPrivate) return addresses
            val blocked = addresses.firstOrNull { it.isPrivateTarget() }
            if (blocked != null) throw BlockedAddressException(host)
            return addresses
        }

        private fun InetAddress.isPrivateTarget(): Boolean =
            isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress ||
                // unique local ipv6 (fc00::/7) is not "site local" to java
                (address.size == 16 && (address[0].toInt() and 0xfe) == 0xfc)
    }
}

class BlockedAddressException(
    host: String
) : UnknownHostException("host $host resolves to a private address")
