package org.amnezia.awg.warp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

data class WarpTunnelPolicy(val mtu: Int, val keepaliveSeconds: Int)

/** Conservative network-aware tuning; values stay inside WARP-compatible limits. */
class WarpTunnelPolicyResolver(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    fun current(): WarpTunnelPolicy {
        val cm = connectivity ?: return WarpTunnelPolicy(mtu = 1280, keepaliveSeconds = 12)
        val capabilities = runCatching {
            cm.activeNetwork
                ?.let(cm::getNetworkCapabilities)
                ?.takeIf { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        }.getOrNull()

        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                WarpTunnelPolicy(mtu = 1280, keepaliveSeconds = 12)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                WarpTunnelPolicy(mtu = 1420, keepaliveSeconds = 20)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true ->
                WarpTunnelPolicy(mtu = 1360, keepaliveSeconds = 15)
            else -> WarpTunnelPolicy(mtu = 1280, keepaliveSeconds = 12)
        }
    }
}
