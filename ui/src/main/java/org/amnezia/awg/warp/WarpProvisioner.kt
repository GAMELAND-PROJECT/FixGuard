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
    private val appContext = context.applicationContext
    private val store = EncryptedWarpIdentityStore(appContext)
    private val registrationPreferences = appContext.getSharedPreferences(
        REGISTRATION_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val api = WarpApiClient()
    private val endpointScanner = WarpEndpointScanner(context.applicationContext)

    suspend fun createProfile(): Config = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = loadCurrentIdentity()
            val selection = endpointScanner.select(identity.endpoint)
            WarpProfileGenerator.generate(identity, selection.primary.authority)
        }
    }

    /** Returns complete profiles whose endpoints still require a real AWG handshake test. */
    suspend fun createConnectionCandidates(): List<WarpProfileCandidate> = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = loadCurrentIdentity()
            endpointScanner.connectionCandidates(identity.apiEndpoints()).map { endpoint ->
                WarpProfileCandidate(
                    config = WarpProfileGenerator.generate(identity, endpoint.authority),
                    endpoint = endpoint,
                )
            }
        }
    }

    suspend fun recordEndpointSuccess(
        endpoint: WarpEndpoint,
        handshakeMs: Long,
        validationMs: Long,
    ) = withContext(Dispatchers.IO) {
        endpointScanner.recordSuccess(endpoint, handshakeMs, validationMs)
    }

    suspend fun recordEndpointFailure(endpoint: WarpEndpoint) =
        withContext(Dispatchers.IO) { endpointScanner.recordFailure(endpoint) }

    suspend fun optimizeProfile(config: Config): OptimizedWarpProfile = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = loadCurrentIdentity(requireExisting = true)
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

    private fun loadCurrentIdentity(requireExisting: Boolean = false): WarpIdentity {
        val stored = store.load()
        if (stored == null) {
            if (requireExisting) throw IllegalStateException("No stored WARP identity")
            return registerNewIdentity()
        }

        val current = try {
            api.refresh(stored).also(store::save)
        } catch (error: WarpApiException) {
            if (error.statusCode != 401 && error.statusCode != 404) return stored
            // The token/device is definitively invalid. Replace it once instead of looping on
            // transient network, rate-limit, or server failures.
            registerNewIdentity()
        } catch (_: java.io.IOException) {
            // API reachability is not required when an existing cryptographic identity can still
            // establish a tunnel. The real handshake/data-path test remains authoritative.
            stored
        }
        // These API flags are useful diagnostics but are not an authorization verdict for the
        // consumer WireGuard tunnel. In particular, `enabled` can be false for a device whose
        // issued keys still authenticate. A fresh AWG handshake and WARP trace are authoritative.
        return current
    }

    private fun registerNewIdentity(): WarpIdentity {
        val now = System.currentTimeMillis()
        val lastAttempt = registrationPreferences.getLong(LAST_REGISTRATION_ATTEMPT, 0L)
        check(now - lastAttempt >= REGISTRATION_COOLDOWN_MS) {
            "WARP registration is cooling down; try again shortly"
        }
        // Persist before the request so failures and repeated taps cannot hammer the API.
        registrationPreferences.edit().putLong(LAST_REGISTRATION_ATTEMPT, now).apply()
        return api.register(KeyPair()).also(store::save)
    }

    private fun WarpIdentity.apiEndpoints(): List<String> =
        listOf(endpoint, endpointV4, endpointV6).filter(String::isNotBlank).distinct()

    private companion object {
        val provisionMutex = Mutex()
        const val REGISTRATION_PREFERENCES = "warp_registration_guard"
        const val LAST_REGISTRATION_ATTEMPT = "last_attempt"
        const val REGISTRATION_COOLDOWN_MS = 30_000L
    }
}

data class OptimizedWarpProfile(
    val config: Config,
    val endpoints: WarpEndpointSelection,
)

data class WarpProfileCandidate(
    val config: Config,
    val endpoint: WarpEndpoint,
)
