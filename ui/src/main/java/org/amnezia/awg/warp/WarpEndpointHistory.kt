package org.amnezia.awg.warp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent, per-network evidence collected exclusively from real AWG connection attempts. */
class WarpEndpointHistory(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun ranked(networkKey: String, now: Long = System.currentTimeMillis()): List<WarpEndpoint> =
        read(networkKey)
            .filter { it.quarantineUntil <= now }
            .sortedWith(
                compareByDescending<EndpointEvidence> { it.successes > 0 }
                    .thenBy { it.qualityScoreMs() }
                    .thenByDescending { it.successes }
                    .thenByDescending { it.lastSuccessAt }
                    .thenBy { it.consecutiveFailures },
            )
            .map { WarpEndpoint(it.host, it.port, it.qualityScoreMs()) }

    @Synchronized
    fun recordSuccess(
        networkKey: String,
        endpoint: WarpEndpoint,
        handshakeMs: Long,
        validationMs: Long = 0L,
    ) {
        val now = System.currentTimeMillis()
        update(networkKey, endpoint) { previous ->
            val average = when {
                handshakeMs <= 0 -> previous.averageHandshakeMs
                previous.averageHandshakeMs <= 0 -> handshakeMs
                else -> ((previous.averageHandshakeMs * 3L) + handshakeMs) / 4L
            }
            val validationAverage = when {
                validationMs <= 0 -> previous.averageValidationMs
                previous.averageValidationMs <= 0 -> validationMs
                else -> ((previous.averageValidationMs * 3L) + validationMs) / 4L
            }
            val jitterSample = if (previous.averageHandshakeMs > 0 && handshakeMs > 0)
                kotlin.math.abs(handshakeMs - previous.averageHandshakeMs) else 0L
            val jitterAverage = when {
                jitterSample <= 0 -> previous.averageJitterMs
                previous.averageJitterMs <= 0 -> jitterSample
                else -> ((previous.averageJitterMs * 3L) + jitterSample) / 4L
            }
            previous.copy(
                attempts = previous.attempts + 1,
                successes = previous.successes + 1,
                consecutiveFailures = 0,
                averageHandshakeMs = average,
                averageValidationMs = validationAverage,
                averageJitterMs = jitterAverage,
                lastSuccessAt = now,
                quarantineUntil = 0L,
            )
        }
    }

    @Synchronized
    fun recordFailure(networkKey: String, endpoint: WarpEndpoint) {
        val now = System.currentTimeMillis()
        update(networkKey, endpoint) { previous ->
            val consecutiveFailures = previous.consecutiveFailures + 1
            previous.copy(
                attempts = previous.attempts + 1,
                failures = previous.failures + 1,
                consecutiveFailures = consecutiveFailures,
                lastFailureAt = now,
                quarantineUntil = if (consecutiveFailures >= FAILURES_BEFORE_QUARANTINE)
                    now + QUARANTINE_MS else 0L,
            )
        }
    }

    private fun update(
        networkKey: String,
        endpoint: WarpEndpoint,
        transform: (EndpointEvidence) -> EndpointEvidence,
    ) {
        val entries = read(networkKey).toMutableList()
        val index = entries.indexOfFirst { it.host == endpoint.host && it.port == endpoint.port }
        val previous = entries.getOrNull(index) ?: EndpointEvidence(endpoint.host, endpoint.port)
        val updated = transform(previous)
        if (index >= 0) entries[index] = updated else entries.add(updated)
        val trimmed = entries.sortedByDescending { maxOf(it.lastSuccessAt, it.lastFailureAt) }
            .take(MAX_SAVED_ENDPOINTS)
        write(networkKey, trimmed)
    }

    private fun read(networkKey: String): List<EndpointEvidence> = runCatching {
        val array = JSONArray(preferences.getString(networkKey, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    EndpointEvidence(
                        host = item.getString("host"),
                        port = item.getInt("port"),
                        attempts = item.optInt("attempts", item.optInt("successes") + item.optInt("failures")),
                        successes = item.optInt("successes"),
                        failures = item.optInt("failures"),
                        consecutiveFailures = item.optInt("consecutiveFailures", item.optInt("failures")),
                        averageHandshakeMs = item.optLong("averageHandshakeMs"),
                        averageValidationMs = item.optLong("averageValidationMs"),
                        averageJitterMs = item.optLong("averageJitterMs"),
                        lastSuccessAt = item.optLong("lastSuccessAt"),
                        lastFailureAt = item.optLong("lastFailureAt"),
                        quarantineUntil = item.optLong("quarantineUntil"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun write(networkKey: String, entries: List<EndpointEvidence>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply {
                put("host", entry.host)
                put("port", entry.port)
                put("attempts", entry.attempts)
                put("successes", entry.successes)
                put("failures", entry.failures)
                put("consecutiveFailures", entry.consecutiveFailures)
                put("averageHandshakeMs", entry.averageHandshakeMs)
                put("averageValidationMs", entry.averageValidationMs)
                put("averageJitterMs", entry.averageJitterMs)
                put("lastSuccessAt", entry.lastSuccessAt)
                put("lastFailureAt", entry.lastFailureAt)
                put("quarantineUntil", entry.quarantineUntil)
            })
        }
        preferences.edit().putString(networkKey, array.toString()).apply()
    }

    private data class EndpointEvidence(
        val host: String,
        val port: Int,
        val attempts: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val averageHandshakeMs: Long = 0L,
        val averageValidationMs: Long = 0L,
        val averageJitterMs: Long = 0L,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val quarantineUntil: Long = 0L,
    ) {
        fun qualityScoreMs(): Long {
            if (averageHandshakeMs <= 0) return Long.MAX_VALUE
            val observedAttempts = attempts.coerceAtLeast(successes + failures).coerceAtLeast(1)
            val lossPenalty = failures.toLong() * LOSS_PENALTY_MS / observedAttempts
            return averageHandshakeMs + averageValidationMs.coerceAtLeast(0L) +
                (averageJitterMs * JITTER_WEIGHT) + lossPenalty
        }
    }

    private companion object {
        const val FILE_NAME = "warp_endpoint_history_v1"
        const val MAX_SAVED_ENDPOINTS = 40
        const val FAILURES_BEFORE_QUARANTINE = 2
        const val QUARANTINE_MS = 15 * 60_000L
        const val LOSS_PENALTY_MS = 2_000L
        const val JITTER_WEIGHT = 2L
    }
}
