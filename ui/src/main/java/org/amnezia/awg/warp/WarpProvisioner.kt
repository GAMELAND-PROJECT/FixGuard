package org.amnezia.awg.warp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.amnezia.awg.config.Config
import org.amnezia.awg.crypto.KeyPair

/** Reuses the encrypted registration, creating a new one only when none is available. */
class WarpProvisioner(context: Context) {
    private val store = EncryptedWarpIdentityStore(context.applicationContext)
    private val api = WarpApiClient()
    private val endpointScanner = WarpEndpointScanner(context.applicationContext)

    suspend fun createProfile(): Config = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = store.load() ?: api.register(KeyPair()).also(store::save)
            val selection = endpointScanner.select(identity.endpoint)
            WarpProfileGenerator.generate(identity, selection.primary.authority)
        }
    }

    suspend fun optimizeProfile(config: Config): OptimizedWarpProfile = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = store.load() ?: throw IllegalStateException("No stored WARP identity")
            if (config.peers.none { it.publicKey.toBase64() == identity.peerPublicKey })
                throw IllegalArgumentException("The selected profile does not belong to the stored WARP identity")
            val selection = endpointScanner.select(identity.endpoint, forceRefresh = true)
            val updated = WarpProfileGenerator.replaceEndpoint(
                config,
                identity.peerPublicKey,
                selection.primary.authority,
            ) ?: throw IllegalArgumentException("WARP peer was not found in the profile")
            OptimizedWarpProfile(updated, selection)
        }
    }

    private companion object {
        val provisionMutex = Mutex()
    }
}

data class OptimizedWarpProfile(
    val config: Config,
    val endpoints: WarpEndpointSelection,
)
