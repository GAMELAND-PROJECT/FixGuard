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

    suspend fun createProfile(): Config = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = store.load() ?: api.register(KeyPair()).also(store::save)
            WarpProfileGenerator.generate(identity)
        }
    }

    private companion object {
        val provisionMutex = Mutex()
    }
}
