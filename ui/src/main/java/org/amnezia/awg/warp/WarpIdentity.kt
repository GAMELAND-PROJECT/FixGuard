package org.amnezia.awg.warp

/** A complete WARP device registration. Secret fields must only be persisted encrypted. */
data class WarpIdentity(
    val privateKey: String,
    val deviceId: String,
    val accessToken: String,
    val accountId: String,
    val licenseKey: String,
    val accountType: String,
    val createdAt: String,
    val ipv4Address: String,
    val ipv6Address: String,
    val peerPublicKey: String,
    val endpoint: String,
)
