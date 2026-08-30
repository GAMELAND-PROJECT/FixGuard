package org.amnezia.awg.warp

import org.amnezia.awg.config.Config
import java.io.ByteArrayInputStream

/** Produces a conservative WARP-compatible AmneziaWG profile. */
object WarpProfileGenerator {
    fun generate(identity: WarpIdentity): Config {
        val endpoint = identity.endpoint.ifBlank { DEFAULT_ENDPOINT }
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

    private const val DEFAULT_ENDPOINT = "engage.cloudflareclient.com:2408"
}
