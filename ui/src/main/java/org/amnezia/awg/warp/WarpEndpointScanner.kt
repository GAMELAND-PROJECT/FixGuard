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
 * High-speed clean IP and resilient port discovery engine for ZUN VPN (WARP/AWG).
 * Combines network history, golden seed pools, and high-concurrency route ranking.
 */
class WarpEndpointScanner(context: Context) {
    private val appContext = context.applicationContext
    private val cache = WarpEndpointCache(appContext)
    private val history = WarpEndpointHistory(appContext)

    suspend fun select(apiEndpoint: String, forceRefresh: Boolean = false): WarpEndpointSelection {
        val network = currentPhysicalNetwork()
        val apiCandidate = parseEndpoint(apiEndpoint)?.takeIf { isNumericIp(it.host) }
        val selectedPort = apiCandidate?.port?.takeIf(WARP_PORTS::contains) ?: TOP_BYPASS_PORTS.first()
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

        val winners = (measured + candidates)
            .distinctBy { it.authority }
            .take(RESULT_COUNT)
        val selected = if (winners.isNotEmpty()) {
            WarpEndpointSelection(winners.first(), winners.drop(1))
        } else {
            val safe = apiCandidate
                ?.copy(port = selectedPort)
                ?: WarpEndpoint(DEFAULT_IPV4, selectedPort, Long.MAX_VALUE)
            WarpEndpointSelection(safe, emptyList())
        }
        cache.save(networkKey, selected)
        return selected
    }

    /**
     * Produces high-quality, resilient candidates for the AmneziaWG handshake.
     * Prioritizes proven working endpoints, golden seeds with bypass ports, and ranked clean IPs.
     */
    suspend fun connectionCandidates(apiEndpoints: List<String>): List<WarpEndpoint> {
        val parsedApiEndpoints = apiEndpoints.mapNotNull(::parseEndpoint)
            .filter { isNumericIp(it.host) }
            .distinctBy(WarpEndpoint::authority)
        val canonicalApi = parsedApiEndpoints.firstOrNull()?.authority ?: "$DEFAULT_IPV4:$DEFAULT_PORT"
        val selection = select(canonicalApi, forceRefresh = true)
        val apiCandidate = parsedApiEndpoints.firstOrNull()
        val networkKey = currentNetworkKey(currentPhysicalNetwork())

        // 1. Top priority: Proven endpoints that successfully connected on this exact physical network
        val proven = history.ranked(networkKey).filter { isNumericIp(it.host) }

        val preferredPort = apiCandidate?.port?.takeIf(WARP_PORTS::contains)
            ?: TOP_BYPASS_PORTS.first()
        val orderedPorts = listOf(preferredPort) + TOP_BYPASS_PORTS.filterNot { it == preferredPort }

        // 2. Golden clean seeds known for exceptional uptime and low packet loss in filtered regions
        val goldenCandidates = GOLDEN_SEED_IPS.flatMap { ip ->
            orderedPorts.take(3).map { port -> WarpEndpoint(ip, port, Long.MAX_VALUE) }
        }

        // 3. Probed dynamic routes from the scanner selection
        val discoveredHosts = (listOf(selection.primary.host) + selection.fallbacks.map { it.host } + parsedApiEndpoints.map { it.host })
            .filter(::isNumericIp)
            .distinct()

        val dynamicRoutes = discoveredHosts.flatMap { host ->
            orderedPorts.take(2).map { port -> WarpEndpoint(host, port, Long.MAX_VALUE) }
        }

        val combined = (proven + goldenCandidates + dynamicRoutes)
            .distinctBy(WarpEndpoint::authority)

        return combined.take(MAX_HANDSHAKE_CANDIDATES)
    }

    suspend fun connectionCandidates(apiEndpoint: String): List<WarpEndpoint> =
        connectionCandidates(listOf(apiEndpoint))

    /**
     * Generates endless, rotating candidates for continuous hunting.
     * Starts with proven endpoints on this network, then cycles through golden seed IPs
     * paired with top bypass ports, and finally dynamic Anycast subnets.
     */
    fun getCandidateEndpointAt(index: Int, apiEndpoints: List<String>): WarpEndpoint {
        val networkKey = currentNetworkKey(currentPhysicalNetwork())
        val proven = history.ranked(networkKey).filter { isNumericIp(it.host) }
        if (index < proven.size) {
            return proven[index]
        }
        val adjustedIndex = index - proven.size

        // Total deterministic golden pairs = GOLDEN_SEED_IPS.size * TOP_BYPASS_PORTS.size
        val totalGoldenPairs = GOLDEN_SEED_IPS.size * TOP_BYPASS_PORTS.size
        if (adjustedIndex < totalGoldenPairs) {
            val ip = GOLDEN_SEED_IPS[adjustedIndex % GOLDEN_SEED_IPS.size]
            val portIndex = (adjustedIndex / GOLDEN_SEED_IPS.size) % TOP_BYPASS_PORTS.size
            val port = TOP_BYPASS_PORTS[portIndex]
            return WarpEndpoint(ip, port, Long.MAX_VALUE)
        }

        // Dynamic generation beyond initial golden pairs
        val dynamicIndex = adjustedIndex - totalGoldenPairs
        val prefix = WARP_IPV4_PREFIXES[dynamicIndex % WARP_IPV4_PREFIXES.size]
        val hostLastOctet = ((dynamicIndex * 17 + 7) % 253) + 1
        val port = TOP_BYPASS_PORTS[(dynamicIndex / 3) % TOP_BYPASS_PORTS.size]
        return WarpEndpoint("$prefix.$hostLastOctet", port, Long.MAX_VALUE)
    }

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
        val golden = GOLDEN_SEED_IPS.map { host ->
            WarpEndpoint(host, selectedPort, Long.MAX_VALUE)
        }
        val generated = WARP_IPV4_PREFIXES.flatMap { prefix ->
            (1..SAMPLES_PER_PREFIX).map {
                val host = "$prefix.${random.nextInt(1, 254)}"
                WarpEndpoint(host, selectedPort, Long.MAX_VALUE)
            }
        }
        return (listOfNotNull(apiEndpoint?.copy(port = selectedPort)) + golden + generated.shuffled(random))
            .distinctBy(WarpEndpoint::host)
    }

    private fun probe(endpoint: WarpEndpoint, network: Network?): WarpEndpoint? {
        val started = System.nanoTime()
        return runCatching {
            val socket = network?.socketFactory?.createSocket() ?: Socket()
            socket.use { s ->
                s.tcpNoDelay = true
                if (network != null) {
                    runCatching { network.bindSocket(s) }
                }
                s.connect(InetSocketAddress(endpoint.host, PROBE_PORT), CONNECT_TIMEOUT_MS)
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
        if (':' !in host || !IPV6_SHAPE.matches(host)) return false
        return runCatching { InetAddress.getByName(host) is Inet6Address }.getOrDefault(false)
    }

    private fun currentPhysicalNetwork(): Network? {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = runCatching {
            manager.allNetworks.filter { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@filter false
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrDefault(emptyList())
        return candidates.firstOrNull { network ->
            manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } ?: candidates.firstOrNull()
    }

    private fun currentNetworkKey(network: Network?): String {
        if (network == null) return "offline"
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return "other"
        val capabilities = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }
        return transport
    }

    private companion object {
        const val DEFAULT_IPV4 = "162.159.192.1"
        const val DEFAULT_PORT = 2408
        const val PROBE_PORT = 443
        const val CONNECT_TIMEOUT_MS = 500
        const val SCAN_BUDGET_MS = 2_200L
        const val MAX_CONCURRENCY = 16
        const val SAMPLES_PER_PREFIX = 3
        const val RESULT_COUNT = 10
        const val MAX_HANDSHAKE_CANDIDATES = 16

        // Golden clean Cloudflare seeds thoroughly validated across Iranian mobile/fixed carriers
        val GOLDEN_SEED_IPS = listOf(
            "162.159.192.1", "162.159.192.2", "162.159.192.5", "162.159.192.10", "162.159.192.20",
            "162.159.193.1", "162.159.193.5", "162.159.193.10",
            "162.159.195.1", "162.159.195.5", "162.159.195.10",
            "188.114.96.1", "188.114.96.5", "188.114.97.1", "188.114.97.10",
            "188.114.98.1", "188.114.99.1",
            "162.159.204.1", "162.159.204.5",
        )

        // Ports that bypass DPI pattern matchers and UDP rate limits
        val TOP_BYPASS_PORTS = listOf(
            854, 878, 880, 890, 891, 894, 908, 928, 934, 939, 943, 945, 968, 988, 1074, 1180, 1387, 1743, 2408, 500,
        )

        val WARP_PORTS = listOf(
            2408, 500, 1701, 4500,
            854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928, 934,
            939, 942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010, 1014,
            1018, 1070, 1074, 1180, 1387, 1743, 4088, 8443,
        )

        val WARP_IPV4_PREFIXES = listOf(
            "162.159.192", "162.159.193", "162.159.195", "162.159.204",
            "188.114.96", "188.114.97", "188.114.98", "188.114.99",
        )
        val IPV4_SHAPE = Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$")
        val IPV6_SHAPE = Regex("^[0-9a-fA-F:]+$")
    }
}
