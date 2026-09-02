package org.amnezia.awg.model

import android.content.Context

/** Stores only redacted health telemetry; no host, key, address, or account identifier is saved. */
class ConnectionHealthStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun recordProbe(success: Boolean, reason: String, consecutiveFailures: Int) {
        preferences.edit()
            .putLong(LAST_PROBE_AT, System.currentTimeMillis())
            .putBoolean(LAST_PROBE_SUCCESS, success)
            .putString(LAST_REASON, reason)
            .putInt(CONSECUTIVE_FAILURES, consecutiveFailures)
            .apply()
    }

    fun recordRecovery(reason: String) {
        preferences.edit()
            .putLong(LAST_RECOVERY_AT, System.currentTimeMillis())
            .putString(LAST_REASON, reason)
            .apply()
    }

    fun snapshot() = ConnectionHealthSnapshot(
        lastProbeAt = preferences.getLong(LAST_PROBE_AT, 0L),
        lastProbeSucceeded = preferences.getBoolean(LAST_PROBE_SUCCESS, false),
        consecutiveFailures = preferences.getInt(CONSECUTIVE_FAILURES, 0),
        lastRecoveryAt = preferences.getLong(LAST_RECOVERY_AT, 0L),
        lastReason = preferences.getString(LAST_REASON, "-") ?: "-",
    )

    private companion object {
        const val FILE_NAME = "connection_health_v1"
        const val LAST_PROBE_AT = "last_probe_at"
        const val LAST_PROBE_SUCCESS = "last_probe_success"
        const val CONSECUTIVE_FAILURES = "consecutive_failures"
        const val LAST_RECOVERY_AT = "last_recovery_at"
        const val LAST_REASON = "last_reason"
    }
}

data class ConnectionHealthSnapshot(
    val lastProbeAt: Long,
    val lastProbeSucceeded: Boolean,
    val consecutiveFailures: Int,
    val lastRecoveryAt: Long,
    val lastReason: String,
)
