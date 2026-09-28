package org.amnezia.awg.warp

import org.amnezia.awg.crypto.KeyPair
import org.json.JSONObject
import java.net.URL
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.HostnameVerifier

/** Ultra-resilient, multi-route client for the consumer WARP registration API. */
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

        val response = requestWithFailover("/$API_VERSION/reg", "POST", body)
        return parseIdentity(response, keyPair.privateKey.toBase64(), response.getString("token"))
    }

    /** Fetches the authoritative device state and latest tunnel configuration. */
    fun refresh(identity: WarpIdentity): WarpIdentity {
        val response = requestWithFailover(
            "/$API_VERSION/reg/${identity.deviceId}",
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

    private fun requestWithFailover(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
    ): JSONObject {
        var lastException: Exception? = null
        for (baseHost in API_BASE_HOSTS) {
            try {
                return request(baseHost, path, method, body, accessToken)
            } catch (apiEx: WarpApiException) {
                // If API actively rejected with 4xx/5xx (e.g. rate limit, bad token), propagate
                if (apiEx.statusCode in 400..499 && apiEx.statusCode != 408) {
                    throw apiEx
                }
                lastException = apiEx
            } catch (e: Exception) {
                lastException = e
            }
        }
        throw lastException ?: WarpApiException(500, "All WARP API routes failed")
    }

    private fun request(
        baseHost: String,
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
    ): JSONObject {
        val urlString = "$baseHost$path"
        val connection = URL(urlString).openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = SSLContext.getInstance("TLSv1.2").apply { init(null, null, null) }.socketFactory
            connection.hostnameVerifier = HostnameVerifier { hostname, session ->
                if (DIRECT_IP_SET.contains(hostname)) {
                    true
                } else {
                    HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)
                }
            }
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.doOutput = body != null
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
            connection.setRequestProperty("CF-Client-Version", "a-6.3-1922")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Host", DEFAULT_API_DOMAIN)
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
                    message?.takeIf { it.isNotBlank() } ?: "WARP API request failed ($status)",
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
        const val DEFAULT_API_DOMAIN = "api.cloudflareclient.com"
        const val API_VERSION = "v0a1922"
        const val DEFAULT_ENDPOINT = "162.159.192.1:2408"
        const val CONNECT_TIMEOUT_MS = 4_500
        const val READ_TIMEOUT_MS = 7_000

        val API_BASE_HOSTS = listOf(
            "https://api.cloudflareclient.com",
            "https://engage.cloudflareclient.com",
            "https://162.159.192.1",
            "https://162.159.193.1",
            "https://188.114.96.1",
            "https://188.114.97.1",
        )
        val DIRECT_IP_SET = setOf(
            "162.159.192.1",
            "162.159.193.1",
            "188.114.96.1",
            "188.114.97.1",
        )
    }
}

class WarpApiException(
    val statusCode: Int,
    message: String,
    val retryAfterMs: Long? = null,
) : Exception(message)
