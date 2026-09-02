package org.amnezia.awg.warp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Socket
import kotlin.random.Random

/**
 * Quickly ranks WARP routes without changing the active Android VPN.
 *
 * Cloudflare WARP deliberately ignores arbitrary UDP packets, so this stage measures route
 * reachability to Cloudflare on TCP/443. A native authenticated AWG probe can later replace the
 * [probe] method without changing selection, caching, or profile generation.
 */
class WarpEndpointScanner(context: Context) {
    private val appContext = context.applicationContext
    private val cache = WarpEndpointCache(appContext)
    private val history = WarpEndpointHistory(appContext)

    suspend fun select(apiEndpoint: String, forceRefresh: Boolean = false): WarpEndpointSelection {
        val network = currentPhysicalNetwork()
        // The API also returns a hostname, but connection testing and generated profiles must use
        // numeric addresses only. This predicate never performs DNS resolution for hostnames.
        val apiCandidate = parseEndpoint(apiEndpoint)?.takeIf { isNumericIp(it.host) }
        val selectedPort = apiCandidate?.port?.takeIf(WARP_PORTS::contains) ?: DEFAULT_PORT
        // Include the algorithm version and port in the key so selections written by the old
        // random-port implementation can never be restored from cache.
        val networkKey = "${currentNetworkKey(network)}:warp-endpoint-v4:$selectedPort"
        if (!forceRefresh) cache.load(networkKey)?.let { cached ->
            if (isNumericIp(cached.primary.host)) {
                return cached.copy(fallbacks = cached.fallbacks.filter { isNumericIp(it.host) })
            }
        }

        val candidates = buildCandidates(apiCandidate, selectedPort)
        val measured = withTimeoutOrNull(SCAN_BUDGET_MS) {
            coroutineScope {
                val semaphore = Semaphore(MAX_CONCURRENCY)
                candidates.map { endpoint ->
                    async { semaphore.withPermit { probe(endpoint, network) } }
                }.awaitAll().filterNotNull()
            }
        }.orEmpty().sortedBy(WarpEndpoint::latencyMs)

        // TCP/443 is only a weak reachability hint. Never discard a WARP candidate merely because
        // it stayed silent here; only an authenticated UDP handshake can reject it.
        val winners = (measured + candidates)
            .distinctBy { it.authority }
            .take(RESULT_COUNT)
        val selected = if (winners.isNotEmpty()) {
            WarpEndpointSelection(winners.first(), winners.drop(1))
        } else {
            val safe = apiCandidate
                ?.copy(port = selectedPort)
                ?: WarpEndpoint(DEFAULT_IPV4, DEFAULT_PORT, Long.MAX_VALUE)
            WarpEndpointSelection(safe, emptyList())
        }
        cache.save(networkKey, selected)
        return selected
    }

    /**
     * Produces a small, deterministic set for a real tunnel-handshake test. TCP ranking chooses
     * the hosts; the caller must validate these official UDP ports with the AWG backend itself.
     */
    suspend fun connectionCandidates(apiEndpoints: List<String>): List<WarpEndpoint> {
        val parsedApiEndpoints = apiEndpoints.mapNotNull(::parseEndpoint)
            .filter { isNumericIp(it.host) }
            .distinctBy(WarpEndpoint::authority)
        val canonicalApi = parsedApiEndpoints.firstOrNull()?.authority ?: "$DEFAULT_IPV4:$DEFAULT_PORT"
        val selection = select(canonicalApi, forceRefresh = true)
        val apiCandidate = parsedApiEndpoints.firstOrNull()
        val networkKey = currentNetworkKey(currentPhysicalNetwork())
        // Drop hostname entries saved by older versions so they can never re-enter a profile.
        val proven = history.ranked(networkKey).filter { isNumericIp(it.host) }
        val endpoints = (proven + parsedApiEndpoints + selection.primary + selection.fallbacks)
            .distinctBy(WarpEndpoint::host)
        val preferredPort = apiCandidate?.port?.takeIf(WARP_PORTS::contains)
            ?: DEFAULT_PORT
        val orderedPorts = listOf(preferredPort) + WARP_PORTS.filterNot { it == preferredPort }
        val primaryRoutes = endpoints.take(PRIMARY_ROUTE_COUNT)
            .map { endpoint -> endpoint.copy(port = preferredPort) }
        val fallbackHosts = (parsedApiEndpoints + endpoints)
            .distinctBy(WarpEndpoint::host)
            .take(FALLBACK_HOST_COUNT)
        val fallbackRoutes = orderedPorts.drop(1).flatMap { port ->
            fallbackHosts.map { endpoint -> endpoint.copy(port = port) }
        }
        val generated = primaryRoutes + fallbackRoutes
        return (proven + generated)
            .distinctBy(WarpEndpoint::authority)
            .take(MAX_HANDSHAKE_CANDIDATES)
    }

    suspend fun connectionCandidates(apiEndpoint: String): List<WarpEndpoint> =
        connectionCandidates(listOf(apiEndpoint))

    fun recordSuccess(endpoint: WarpEndpoint, handshakeMs: Long, validationMs: Long = 0L) {
        history.recordSuccess(
            currentNetworkKey(currentPhysicalNetwork()),
            endpoint,
            handshakeMs,
            validationMs,
        )
    }

    fun recordFailure(endpoint: WarpEndpoint) {
        history.recordFailure(currentNetworkKey(currentPhysicalNetwork()), endpoint)
    }

    private fun buildCandidates(apiEndpoint: WarpEndpoint?, selectedPort: Int): List<WarpEndpoint> {
        val random = Random(System.nanoTime())
        val generated = WARP_IPV4_PREFIXES.flatMap { prefix ->
            (1..SAMPLES_PER_PREFIX).map {
                val host = "$prefix.${random.nextInt(1, 255)}"
                WarpEndpoint(host, selectedPort, Long.MAX_VALUE)
            }
        }
        return listOfNotNull(apiEndpoint?.copy(port = selectedPort)) + generated.shuffled(random)
    }

    private fun probe(endpoint: WarpEndpoint, network: Network?): WarpEndpoint? {
        val started = System.nanoTime()
        return runCatching {
            (network?.socketFactory?.createSocket() ?: Socket()).use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(endpoint.host, PROBE_PORT), CONNECT_TIMEOUT_MS)
            }
            endpoint.copy(latencyMs = (System.nanoTime() - started) / 1_000_000)
        }.getOrNull()
    }

    private fun parseEndpoint(value: String): WarpEndpoint? {
        val separator = value.lastIndexOf(':')
        if (separator <= 0) return null
        val host = value.substring(0, separator).removePrefix("[").removeSuffix("]")
        val port = value.substring(separator + 1).toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return WarpEndpoint(host, port, Long.MAX_VALUE)
    }

    private fun isNumericIp(host: String): Boolean {
        if (host.isBlank()) return false
        if (IPV4_SHAPE.matches(host)) {
            val parts = host.split('.')
            if (parts.size != 4 || parts.any { part ->
                    part.toIntOrNull()?.let { value -> value in 0..255 } != true
                }) return false
            return runCatching { InetAddress.getByName(host) is Inet4Address }.getOrDefault(false)
        }
        // Requiring a colon and IPv6-only characters guarantees getByName cannot resolve a DNS
        // hostname. It is used only as the final syntax validator for a numeric literal.
        if (':' !in host || !IPV6_SHAPE.matches(host)) return false
        return runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
    }

    private fun currentPhysicalNetwork(): Network? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val candidates = manager.allNetworks.filter { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        return candidates.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    private fun currentNetworkKey(network: Network?): String {
        if (network == null) return "offline"
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(network)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }
        // Network handles change after reconnects. Transport remains stable without requesting
        // location/SSID access and lets successful routes survive application restarts.
        return transport
    }

    private companion object {
        const val DEFAULT_IPV4 = "162.159.192.1"
        const val DEFAULT_PORT = 2408
        const val PROBE_PORT = 443
        const val CONNECT_TIMEOUT_MS = 800
        const val SCAN_BUDGET_MS = 4_500L
        const val MAX_CONCURRENCY = 8
        // 24 generated candidates fit in three 800 ms waves with concurrency 8.
        const val SAMPLES_PER_PREFIX = 4
        const val RESULT_COUNT = 8
        const val MAX_HANDSHAKE_CANDIDATES = 12
        const val PRIMARY_ROUTE_COUNT = 6
        const val FALLBACK_HOST_COUNT = 2

        // Official Cloudflare WireGuard/WARP ports: UDP 2408 is the default and the remaining
        // values are documented fallbacks. Never persist an arbitrary port in a WARP profile.
        val WARP_PORTS = listOf(2408, 500, 1701, 4500)
        val WARP_IPV4_PREFIXES = listOf(
            // Official consumer/Cloudflare One WireGuard ingress seeds.
            "162.159.192", "162.159.193",
            // Community-observed consumer anycast pools. They never become trusted until an
            // authenticated handshake and routed-data verification succeed on this device.
            "188.114.96", "188.114.97", "188.114.98", "188.114.99",
        )
        val IPV4_SHAPE = Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$")
        val IPV6_SHAPE = Regex("^[0-9a-fA-F:]+$")
    }
}
