package org.amnezia.awg.warp

import org.amnezia.awg.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/** Minimal, direct client for the consumer WARP registration API. */
class WarpApiClient {
    fun register(keyPair: KeyPair, model: String = "Android"): WarpIdentity {
        val body = JSONObject()
            .put("key", keyPair.publicKey.toBase64())
            .put("fcm_token", "")
            .put("install_id", "")
            .put("locale", "en_US")
            .put("model", model)
            .put("tos", Instant.now().toString())
            .put("type", "Android")

        val response = request("$API_URL/$API_VERSION/reg", "POST", body)
        return parseIdentity(response, keyPair.privateKey.toBase64(), response.getString("token"))
    }

    /** Fetches the authoritative device state and latest tunnel configuration. */
    fun refresh(identity: WarpIdentity): WarpIdentity {
        val response = request(
            "$API_URL/$API_VERSION/reg/${identity.deviceId}",
            method = "GET",
            accessToken = identity.accessToken,
        )
        return parseIdentity(response, identity.privateKey, identity.accessToken)
    }

    private fun parseIdentity(response: JSONObject, privateKey: String, accessToken: String): WarpIdentity {
        val account = response.getJSONObject("account")
        val config = response.getJSONObject("config")
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val endpointObject = peer.getJSONObject("endpoint")
        val endpointHost = endpointObject.optString("host").takeIf(::isValidEndpoint).orEmpty()
        val endpointV4 = endpointObject.optString("v4").takeIf(::isValidEndpoint).orEmpty()
        val endpointV6 = endpointObject.optString("v6").takeIf(::isValidEndpoint).orEmpty()
        val endpoint = endpointV4.ifBlank { endpointV6 }
            .ifBlank { endpointHost }
            .takeIf(::isValidEndpoint)
            ?: DEFAULT_ENDPOINT

        return WarpIdentity(
            privateKey = privateKey,
            deviceId = response.getString("id"),
            accessToken = accessToken,
            accountId = account.optString("id"),
            licenseKey = account.optString("license"),
            accountType = account.optString("account_type", "free"),
            createdAt = account.optString("created"),
            ipv4Address = addresses.getString("v4"),
            ipv6Address = addresses.getString("v6"),
            peerPublicKey = peer.getString("public_key"),
            endpoint = endpoint,
            endpointV4 = endpointV4,
            endpointV6 = endpointV6,
            enabled = response.optBoolean("enabled", true),
            warpEnabled = response.optBoolean("warp_enabled", true),
            updatedAt = response.optString("updated"),
        )
    }

    private fun request(
        url: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }.socketFactory
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doOutput = body != null
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
            connection.setRequestProperty("CF-Client-Version", "a-6.3-1922")
            connection.setRequestProperty("Content-Type", "application/json")
            accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            body?.let { payload ->
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val responseText = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(responseText).optString("message") }.getOrNull()
                val retryAfterMs = connection.getHeaderField("Retry-After")
                    ?.toLongOrNull()?.times(1_000L)
                throw WarpApiException(
                    status,
                    message?.takeIf { it.isNotBlank() } ?: "WARP API request failed",
                    retryAfterMs,
                )
            }
            return JSONObject(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun isValidEndpoint(value: String): Boolean {
        if (value.isBlank()) return false
        val separator = value.lastIndexOf(':')
        if (separator <= 0) return false
        val port = value.substring(separator + 1).toIntOrNull() ?: return false
        return port in 1..65535
    }

    private companion object {
        const val API_URL = "https://api.cloudflareclient.com"
        const val API_VERSION = "v0a1922"
        // Used only if the API omits both numeric endpoint fields. Keep it numeric so tunnel
        // discovery never needs to test or persist an endpoint hostname.
        const val DEFAULT_ENDPOINT = "162.159.192.1:2408"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

class WarpApiException(
    val statusCode: Int,
    message: String,
    val retryAfterMs: Long? = null,
) : Exception(message)
