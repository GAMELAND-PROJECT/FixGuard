package org.amnezia.awg.warp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WarpEndpointCache(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(networkKey: String, now: Long = System.currentTimeMillis()): WarpEndpointSelection? {
        val raw = preferences.getString(networkKey, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val savedAt = root.optLong("savedAt", 0L)
            // Security fix: never use cached endpoints older than max age (prevents stale/replayed routes)
            if (now - savedAt > MAX_AGE_MS || savedAt < 1) return null
            val endpoints = root.getJSONArray("endpoints").toEndpoints()
            if (endpoints.isEmpty()) null
            else WarpEndpointSelection(endpoints.first(), endpoints.drop(1))
        }.getOrNull()
    }

    fun save(networkKey: String, selection: WarpEndpointSelection, now: Long = System.currentTimeMillis()) {
        val endpoints = listOf(selection.primary) + selection.fallbacks
        val array = JSONArray()
        endpoints.forEach { endpoint ->
            array.put(JSONObject().apply {
                put("host", endpoint.host)
                put("port", endpoint.port)
                put("latencyMs", endpoint.latencyMs)
            })
        }
        val root = JSONObject().apply {
            put("savedAt", now)
            put("endpoints", array)
        }
        preferences.edit().putString(networkKey, root.toString()).apply()
    }

    private fun JSONArray.toEndpoints(): List<WarpEndpoint> = buildList {
        for (index in 0 until length()) {
            val item = getJSONObject(index)
            add(WarpEndpoint(item.getString("host"), item.getInt("port"), item.getLong("latencyMs")))
        }
    }

    private companion object {
        const val FILE_NAME = "warp_endpoint_cache"
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
