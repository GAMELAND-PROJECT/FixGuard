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
        val capabilities = connectivity.activeNetwork
            ?.let(connectivity::getNetworkCapabilities)
            ?.takeIf { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                WarpTunnelPolicy(mtu = 1280, keepaliveSeconds = 15)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true ->
                WarpTunnelPolicy(mtu = 1360, keepaliveSeconds = 25)
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true ->
                WarpTunnelPolicy(mtu = 1320, keepaliveSeconds = 20)
            else -> WarpTunnelPolicy(mtu = 1280, keepaliveSeconds = 18)
        }
    }
}
