package org.amnezia.awg.warp

import org.amnezia.awg.config.Config
import org.amnezia.awg.config.InetEndpoint
import org.amnezia.awg.config.Peer
import java.io.ByteArrayInputStream

/** Produces a conservative WARP-compatible AmneziaWG profile. */
object WarpProfileGenerator {
    fun generate(identity: WarpIdentity, endpointOverride: String? = null): Config {
        val endpoint = endpointOverride ?: identity.endpoint.ifBlank { DEFAULT_ENDPOINT }
        val ipv4Address = identity.ipv4Address.substringBefore('/')
        val ipv6Address = identity.ipv6Address.substringBefore('/')
        val text = """
            [Interface]
            PrivateKey = ${identity.privateKey}
            Address = $ipv4Address/32, $ipv6Address/128
            DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
            MTU = 1280
            Jc = 5
            Jmin = 10
            Jmax = 40
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
            PersistentKeepalive = 25
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

    private const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"
}
