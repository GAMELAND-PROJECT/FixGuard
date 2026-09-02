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
    private val policyResolver = WarpTunnelPolicyResolver(appContext)

    suspend fun createProfile(): Config = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = loadCurrentIdentity()
            val selection = endpointScanner.select(identity.endpoint)
            WarpProfileGenerator.generate(identity, selection.primary.authority, policyResolver.current())
        }
    }

    /** Returns complete profiles whose endpoints still require a real AWG handshake test. */
    suspend fun createConnectionCandidates(): List<WarpProfileCandidate> = withContext(Dispatchers.IO) {
        provisionMutex.withLock {
            val identity = loadCurrentIdentity()
            val policy = policyResolver.current()
            endpointScanner.connectionCandidates(identity.apiEndpoints()).map { endpoint ->
                WarpProfileCandidate(
                    config = WarpProfileGenerator.generate(identity, endpoint.authority, policy),
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

    /** Returns operational account state without exposing IDs, tokens, keys, or addresses. */
    fun diagnostics(now: Long = System.currentTimeMillis()): WarpAccountDiagnostics {
        val identity = runCatching { store.load() }.getOrNull()
        val retryAt = registrationPreferences.getLong(NEXT_REGISTRATION_ATTEMPT, 0L)
        return WarpAccountDiagnostics(
            registered = identity != null,
            accountType = identity?.accountType?.ifBlank { "free" } ?: "-",
            deviceEnabled = identity?.enabled,
            warpEnabled = identity?.warpEnabled,
            updatedAt = identity?.updatedAt?.ifBlank { identity.createdAt }?.ifBlank { "-" } ?: "-",
            registrationFailures = registrationPreferences.getInt(REGISTRATION_FAILURES, 0),
            retryAfterSeconds = ((retryAt - now + 999L) / 1_000L).coerceAtLeast(0L),
        )
    }

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

        val now = System.currentTimeMillis()
        val lastRefreshAt = registrationPreferences.getLong(LAST_IDENTITY_REFRESH_AT, 0L)
        if (now - lastRefreshAt < IDENTITY_REFRESH_INTERVAL_MS) return stored

        val current = try {
            api.refresh(stored).also { identity ->
                store.save(identity)
                registrationPreferences.edit().putLong(LAST_IDENTITY_REFRESH_AT, now).apply()
            }
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
        val nextAttempt = registrationPreferences.getLong(NEXT_REGISTRATION_ATTEMPT, 0L)
        check(now >= nextAttempt) {
            val seconds = ((nextAttempt - now + 999L) / 1_000L).coerceAtLeast(1L)
            "WARP registration is rate-limited; retry in $seconds seconds"
        }
        return try {
            api.register(KeyPair()).also { identity ->
                store.save(identity)
                registrationPreferences.edit()
                    .putInt(REGISTRATION_FAILURES, 0)
                    .putLong(NEXT_REGISTRATION_ATTEMPT, 0L)
                    .putLong(LAST_IDENTITY_REFRESH_AT, now)
                    .apply()
            }
        } catch (error: Throwable) {
            val failures = (registrationPreferences.getInt(REGISTRATION_FAILURES, 0) + 1)
                .coerceAtMost(MAX_BACKOFF_EXPONENT)
            val exponentialDelay = INITIAL_BACKOFF_MS * (1L shl (failures - 1))
            val delay = (error as? WarpApiException)?.retryAfterMs
                ?.coerceAtLeast(exponentialDelay)
                ?: exponentialDelay
            registrationPreferences.edit()
                .putInt(REGISTRATION_FAILURES, failures)
                .putLong(NEXT_REGISTRATION_ATTEMPT, now + delay.coerceAtMost(MAX_BACKOFF_MS))
                .apply()
            throw error
        }
    }

    private fun WarpIdentity.apiEndpoints(): List<String> =
        listOf(endpoint, endpointV4, endpointV6).filter(String::isNotBlank).distinct()

    private companion object {
        val provisionMutex = Mutex()
        const val REGISTRATION_PREFERENCES = "warp_registration_guard"
        const val NEXT_REGISTRATION_ATTEMPT = "next_attempt"
        const val REGISTRATION_FAILURES = "failures"
        const val LAST_IDENTITY_REFRESH_AT = "last_identity_refresh_at"
        const val IDENTITY_REFRESH_INTERVAL_MS = 15 * 60_000L
        const val INITIAL_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_MS = 15 * 60_000L
        const val MAX_BACKOFF_EXPONENT = 6
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

data class WarpAccountDiagnostics(
    val registered: Boolean,
    val accountType: String,
    val deviceEnabled: Boolean?,
    val warpEnabled: Boolean?,
    val updatedAt: String,
    val registrationFailures: Int,
    val retryAfterSeconds: Long,
)
