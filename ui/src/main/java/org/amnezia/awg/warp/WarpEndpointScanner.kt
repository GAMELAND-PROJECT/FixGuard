package org.amnezia.awg.warp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
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

    suspend fun select(apiEndpoint: String, forceRefresh: Boolean = false): WarpEndpointSelection {
        val networkKey = currentNetworkKey()
        if (!forceRefresh) cache.load(networkKey)?.let { return it }

        val apiCandidate = parseEndpoint(apiEndpoint)
        val candidates = buildCandidates(apiCandidate)
        val measured = withTimeoutOrNull(SCAN_BUDGET_MS) {
            coroutineScope {
                val semaphore = Semaphore(MAX_CONCURRENCY)
                candidates.map { endpoint ->
                    async { semaphore.withPermit { probe(endpoint) } }
                }.awaitAll().filterNotNull()
            }
        }.orEmpty().sortedBy(WarpEndpoint::latencyMs)

        val winners = measured.distinctBy { it.authority }.take(RESULT_COUNT)
        val selected = if (winners.isNotEmpty()) {
            WarpEndpointSelection(winners.first(), winners.drop(1))
        } else {
            val safe = apiCandidate ?: WarpEndpoint(DEFAULT_HOST, DEFAULT_PORT, Long.MAX_VALUE)
            WarpEndpointSelection(safe, emptyList())
        }
        cache.save(networkKey, selected)
        return selected
    }

    private fun buildCandidates(apiEndpoint: WarpEndpoint?): List<WarpEndpoint> {
        val random = Random(System.nanoTime())
        val generated = WARP_IPV4_PREFIXES.flatMap { prefix ->
            (1..SAMPLES_PER_PREFIX).map {
                val host = "$prefix.${random.nextInt(1, 255)}"
                WarpEndpoint(host, COMMON_PORTS[random.nextInt(COMMON_PORTS.size)], Long.MAX_VALUE)
            }
        }
        return listOfNotNull(apiEndpoint) + generated.shuffled(random)
    }

    private fun probe(endpoint: WarpEndpoint): WarpEndpoint? {
        val started = System.nanoTime()
        return runCatching {
            Socket().use { socket ->
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

    private fun currentNetworkKey(): String {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return "offline"
        val capabilities = manager.getNetworkCapabilities(network)
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }
        return "$transport:${network.networkHandle}"
    }

    private companion object {
        const val DEFAULT_HOST = "engage.cloudflareclient.com"
        const val DEFAULT_PORT = 2408
        const val PROBE_PORT = 443
        const val CONNECT_TIMEOUT_MS = 800
        const val SCAN_BUDGET_MS = 4_500L
        const val MAX_CONCURRENCY = 8
        // 32 generated candidates fit in five 800 ms waves with concurrency 8.
        const val SAMPLES_PER_PREFIX = 4
        const val RESULT_COUNT = 3

        val COMMON_PORTS = intArrayOf(2408, 500, 1701, 4500)
        val WARP_IPV4_PREFIXES = listOf(
            "162.159.192", "162.159.193", "162.159.195", "162.159.204",
            "188.114.96", "188.114.97", "188.114.98", "188.114.99",
        )
    }
}
