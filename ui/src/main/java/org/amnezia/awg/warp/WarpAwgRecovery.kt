package org.amnezia.awg.warp

import android.content.Context
import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

/** Rotates safe, client-only AWG first packets without changing WARP's real handshake headers. */
class WarpAwgRecovery(context: Context) {
    private val store = EncryptedWarpIdentityStore(context.applicationContext)
    private val endpointScanner = WarpEndpointScanner(context.applicationContext)

    suspend fun nextConfig(config: Config): Config? {
        val identity = runCatching { store.load() }.getOrNull() ?: return null
        if (config.peers.none { it.publicKey.toBase64() == identity.peerPublicKey }) return null

        val selection = endpointScanner.select(identity.endpoint, forceRefresh = true)
        val endpointConfig = WarpProfileGenerator.replaceEndpoint(
            config,
            identity.peerPublicKey,
            selection.primary.authority,
        ) ?: return null

        val current = endpointConfig.getInterface().specialJunkI1.orElse(null)
        val next = when (current) {
            STUN_I1 -> RANDOM_I1
            RANDOM_I1 -> null
            else -> STUN_I1
        }
        return replaceI1(endpointConfig, next)
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

    private companion object {
        val I1_LINE = Regex("(?mi)^\\s*I1\\s*=.*(?:\\r?\\n)?")
        val PRIVATE_KEY_LINE = Regex("(?mi)^\\s*PrivateKey\\s*=")

        // A valid STUN binding request with a randomized transaction id and software value.
        const val STUN_I1 = "<b 0x000100142112a442><r 12><b 0x80220010><rc 16>"
        const val RANDOM_I1 = "<r 64>"
    }
}
