package org.amnezia.awg.warp

data class WarpEndpoint(
    val host: String,
    val port: Int,
    val latencyMs: Long,
) {
    val authority: String
        get() = "$host:$port"
}

data class WarpEndpointSelection(
    val primary: WarpEndpoint,
    val fallbacks: List<WarpEndpoint>,
)
