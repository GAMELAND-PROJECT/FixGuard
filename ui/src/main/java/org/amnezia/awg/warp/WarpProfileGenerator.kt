package org.amnezia.awg.warp

import org.amnezia.awg.config.Config
import org.amnezia.awg.config.InetEndpoint
import org.amnezia.awg.config.Peer
import java.io.ByteArrayInputStream

/** Produces a conservative WARP-compatible AmneziaWG profile. */
object WarpProfileGenerator {
    fun generate(
        identity: WarpIdentity,
        endpointOverride: String? = null,
        policy: WarpTunnelPolicy = WarpTunnelPolicy(1280, 25),
    ): Config {
        // Endpoint discovery always supplies a numeric address. Keep the defensive fallback
        // numeric too, so this generator can never silently reintroduce DNS into a tunnel.
        val endpoint = endpointOverride ?: DEFAULT_ENDPOINT
        val ipv4Address = identity.ipv4Address.substringBefore('/')
        val ipv6Address = identity.ipv6Address.substringBefore('/')
        // Stable per-device/per-route diversity avoids changing the profile on every launch while
        // preventing every generated client from sharing one obvious junk-packet fingerprint.
        val diversitySeed = (identity.deviceId + endpoint).hashCode().toLong() and 0xffff_ffffL
        val junkCount = 4 + (diversitySeed % 3L).toInt()
        val junkMin = 10 + (diversitySeed % 11L).toInt()
        val junkMax = junkMin + 25 + (diversitySeed % 16L).toInt()
        val text = """
            [Interface]
            I1 = $STUN_INITIAL_PACKET
            PrivateKey = ${identity.privateKey}
            Address = $ipv4Address/32, $ipv6Address/128
            DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
            MTU = ${policy.mtu}
            Jc = $junkCount
            Jmin = $junkMin
            Jmax = $junkMax
            S1 = 0
            S2 = 0
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4

            [Peer]
            PublicKey = ${identity.peerPublicKey}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = $endpoint
            PersistentKeepalive = ${policy.keepaliveSeconds}
        """.trimIndent()
        return Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    /** Replaces only the WARP peer endpoint, preserving every user-edited profile option. */
    fun replaceEndpoint(config: Config, peerPublicKey: String, endpoint: String): Config? {
        var matched = false
        val peers = config.peers.map { peer ->
            if (peer.publicKey.toBase64() != peerPublicKey) return@map peer
            matched = true
            Peer.Builder()
                .addAllowedIps(peer.allowedIps)
                .setPublicKey(peer.publicKey)
                .setEndpoint(InetEndpoint.parse(endpoint))
                .apply {
                    peer.persistentKeepalive.ifPresent { setPersistentKeepalive(it) }
                    peer.preSharedKey.ifPresent { setPreSharedKey(it) }
                }
                .build()
        }
        if (!matched) return null
        return Config.Builder()
            .setInterface(config.getInterface())
            .addPeers(peers)
            .build()
    }

    /** Applies transport-aware settings only to a verified WARP profile. */
    fun applyPolicy(config: Config, peerPublicKey: String, policy: WarpTunnelPolicy): Config? {
        if (config.peers.none { it.publicKey.toBase64() == peerPublicKey }) return null
        var text = config.toAwgQuickString()
        text = if (MTU_LINE.containsMatchIn(text)) {
            text.replaceFirst(MTU_LINE, "MTU = ${policy.mtu}")
        } else {
            val privateKeyLine = PRIVATE_KEY_LINE.find(text)
                ?: return null
            text.replaceRange(
                privateKeyLine.range,
                "MTU = ${policy.mtu}\n${privateKeyLine.value}",
            )
        }
        text = if (KEEPALIVE_LINE.containsMatchIn(text)) {
            text.replaceFirst(KEEPALIVE_LINE, "PersistentKeepalive = ${policy.keepaliveSeconds}")
        } else {
            text.trimEnd() + "\nPersistentKeepalive = ${policy.keepaliveSeconds}\n"
        }
        return Config.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    private const val DEFAULT_ENDPOINT = "162.159.192.1:2408"
    private const val STUN_INITIAL_PACKET = "<b 0x000100142112a442><r 12><b 0x80220010><rc 16>"
    private val MTU_LINE = Regex("(?mi)^\\s*MTU\\s*=.*$")
    private val PRIVATE_KEY_LINE = Regex("(?mi)^\\s*PrivateKey\\s*=.*$")
    private val KEEPALIVE_LINE = Regex("(?mi)^\\s*PersistentKeepalive\\s*=.*$")
}
