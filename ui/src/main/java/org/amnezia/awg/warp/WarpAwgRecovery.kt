package org.amnezia.awg.warp

import android.content.Context
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

/** Rotates safe, client-only AWG first packets without changing WARP's real handshake headers. */
class WarpAwgRecovery(context: Context) {
    private val store = EncryptedWarpIdentityStore(context.applicationContext)
    private val endpointScanner = WarpEndpointScanner(context.applicationContext)
    private val policyResolver = WarpTunnelPolicyResolver(context.applicationContext)

    fun isManagedConfig(config: Config): Boolean {
        val identity = runCatching { store.load() }.getOrNull() ?: return false
        return config.peers.any { it.publicKey.toBase64() == identity.peerPublicKey }
    }

    /** Migrates old hostname-based WARP profiles to a tested numeric endpoint before activation. */
    suspend fun ensureNumericEndpoint(config: Config): Config? {
        val identity = runCatching { store.load() }.getOrNull() ?: return null
        val endpoint = config.peers
            .firstOrNull { it.publicKey.toBase64() == identity.peerPublicKey }
            ?.endpoint?.orElse(null) ?: return null
        if (isNumericIp(endpoint.host)) {
            return WarpProfileGenerator.applyPolicy(
                config,
                identity.peerPublicKey,
                policyResolver.current(),
            ) ?: config
        }
        return bestConfig(config)
    }

    suspend fun nextConfig(config: Config): Config? {
        val identity = runCatching { store.load() }.getOrNull() ?: return null
        if (config.peers.none { it.publicKey.toBase64() == identity.peerPublicKey }) return null

        val currentEndpoint = config.peers
            .firstOrNull { it.publicKey.toBase64() == identity.peerPublicKey }
            ?.endpoint?.orElse(null)
        currentEndpoint?.let {
            endpointScanner.recordFailure(WarpEndpoint(it.host, it.port, Long.MAX_VALUE))
        }
        val candidate = endpointScanner.connectionCandidates(
            listOf(identity.endpoint, identity.endpointV4, identity.endpointV6)
                .filter(String::isNotBlank),
        )
            .firstOrNull { it.authority != currentEndpoint?.toString() }
            ?: return null
        val endpointConfig = WarpProfileGenerator.replaceEndpoint(
            config,
            identity.peerPublicKey,
            candidate.authority,
        ) ?: return null
        val tunedConfig = WarpProfileGenerator.applyPolicy(
            endpointConfig,
            identity.peerPublicKey,
            policyResolver.current(),
        ) ?: endpointConfig

        val current = tunedConfig.getInterface().specialJunkI1.orElse(null)
        val next = when (current) {
            STUN_I1 -> RANDOM_I1
            RANDOM_I1 -> null
            else -> STUN_I1
        }
        return replaceI1(tunedConfig, next)
    }

    /** Selects the best-known route for a newly active physical network without penalizing the old one. */
    suspend fun bestConfig(config: Config): Config? {
        val identity = runCatching { store.load() }.getOrNull() ?: return null
        if (config.peers.none { it.publicKey.toBase64() == identity.peerPublicKey }) return null
        val candidate = endpointScanner.connectionCandidates(
            listOf(identity.endpoint, identity.endpointV4, identity.endpointV6)
                .filter(String::isNotBlank),
        ).firstOrNull() ?: return null
        val endpointConfig = WarpProfileGenerator.replaceEndpoint(
            config,
            identity.peerPublicKey,
            candidate.authority,
        ) ?: return null
        return WarpProfileGenerator.applyPolicy(
            endpointConfig,
            identity.peerPublicKey,
            policyResolver.current(),
        )
    }

    /** Promotes an endpoint only after the backend reports a real authenticated handshake. */
    fun recordConnected(
        config: Config,
        handshakeMs: Long = 0L,
        validationMs: Long = 0L,
    ) {
        val identity = runCatching { store.load() }.getOrNull() ?: return
        val endpoint = config.peers
            .firstOrNull { it.publicKey.toBase64() == identity.peerPublicKey }
            ?.endpoint?.orElse(null) ?: return
        endpointScanner.recordSuccess(
            WarpEndpoint(endpoint.host, endpoint.port, 0L),
            handshakeMs = handshakeMs,
            validationMs = validationMs,
        )
    }

    private fun replaceI1(config: Config, value: String?): Config {
        val withoutI1 = config.toAwgQuickString().replace(I1_LINE, "")
        if (value == null) return parse(withoutI1)
        val privateKeyIndex = PRIVATE_KEY_LINE.find(withoutI1)?.range?.first
            ?: throw IllegalArgumentException("WARP profile has no PrivateKey")
        val updated = withoutI1.substring(0, privateKeyIndex) +
            "I1 = $value\n" + withoutI1.substring(privateKeyIndex)
        return parse(updated)
    }

    private fun parse(value: String): Config =
        Config.parse(ByteArrayInputStream(value.toByteArray(Charsets.UTF_8)))

    private fun isNumericIp(host: String): Boolean = when {
        IPV4_SHAPE.matches(host) -> host.split('.').all { part ->
            part.toIntOrNull()?.let { value -> value in 0..255 } == true
        }
        ':' in host -> IPV6_SHAPE.matches(host)
        else -> false
    }

    private companion object {
        val I1_LINE = Regex("(?mi)^\\s*I1\\s*=.*(?:\\r?\\n)?")
        val PRIVATE_KEY_LINE = Regex("(?mi)^\\s*PrivateKey\\s*=")
        val IPV4_SHAPE = Regex("^[0-9]{1,3}(?:\\.[0-9]{1,3}){3}$")
        val IPV6_SHAPE = Regex("^[0-9a-fA-F:]+$")

        // A valid STUN binding request with a randomized transaction id and software value.
        const val STUN_I1 = "<b 0x000100142112a442><r 12><b 0x80220010><rc 16>"
        const val RANDOM_I1 = "<r 64>"
    }
}
