package org.amnezia.awg.warp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts the WARP private key and API token with a non-exportable Android Keystore key. */
class EncryptedWarpIdentityStore(private val context: Context) {
    fun load(): WarpIdentity? {
        return loadAll().firstOrNull()
    }

    fun loadAll(): List<WarpIdentity> {
        val file = identityFile()
        if (!file.isFile) return emptyList()
        return try {
            val envelope = JSONObject(file.readText(Charsets.UTF_8))
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)),
            )
            decodePayload(String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalStateException("Stored WARP identity could not be decrypted", error)
        }
    }

    fun save(identity: WarpIdentity) {
        val current = loadAll().toMutableList()
        val index = current.indexOfFirst { it.deviceId == identity.deviceId }
        if (index >= 0) current[index] = identity else current.add(identity)
        saveAll(current)
    }

    fun saveAll(identities: List<WarpIdentity>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(encodePayload(identities).toString().toByteArray(Charsets.UTF_8))
        val envelope = JSONObject()
            .put("version", 2)
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        val atomicFile = AtomicFile(identityFile())
        val stream = atomicFile.startWrite()
        try {
            stream.write(envelope.toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Exception) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    fun clear() {
        identityFile().delete()
    }

    private fun identityFile() = File(context.noBackupFilesDir, FILE_NAME)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encode(value: WarpIdentity) = JSONObject()
        .put("privateKey", value.privateKey)
        .put("deviceId", value.deviceId)
        .put("accessToken", value.accessToken)
        .put("accountId", value.accountId)
        .put("licenseKey", value.licenseKey)
        .put("accountType", value.accountType)
        .put("createdAt", value.createdAt)
        .put("ipv4Address", value.ipv4Address)
        .put("ipv6Address", value.ipv6Address)
        .put("peerPublicKey", value.peerPublicKey)
        .put("endpoint", value.endpoint)
        .put("endpointV4", value.endpointV4)
        .put("endpointV6", value.endpointV6)
        .put("enabled", value.enabled)
        .put("warpEnabled", value.warpEnabled)
        .put("updatedAt", value.updatedAt)

    private fun decode(value: JSONObject) = WarpIdentity(
        privateKey = value.getString("privateKey"),
        deviceId = value.getString("deviceId"),
        accessToken = value.getString("accessToken"),
        accountId = value.optString("accountId"),
        licenseKey = value.optString("licenseKey"),
        accountType = value.optString("accountType", "free"),
        createdAt = value.optString("createdAt"),
        ipv4Address = value.getString("ipv4Address"),
        ipv6Address = value.getString("ipv6Address"),
        peerPublicKey = value.getString("peerPublicKey"),
        endpoint = value.getString("endpoint"),
        endpointV4 = value.optString("endpointV4"),
        endpointV6 = value.optString("endpointV6"),
        enabled = value.optBoolean("enabled", true),
        warpEnabled = value.optBoolean("warpEnabled", true),
        updatedAt = value.optString("updatedAt"),
    )

    private fun encodePayload(identities: List<WarpIdentity>) = JSONObject()
        .put("identities", JSONArray().apply {
            identities.distinctBy { it.deviceId }.forEach { put(encode(it)) }
        })

    private fun decodePayload(payload: String): List<WarpIdentity> {
        val value = JSONObject(payload)
        val identities = value.optJSONArray("identities")
        if (identities == null) return listOf(decode(value))
        return buildList {
            for (index in 0 until identities.length()) {
                add(decode(identities.getJSONObject(index)))
            }
        }.distinctBy { it.deviceId }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fixguard_warp_identity_v1"
        const val FILE_NAME = "warp-identity.enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
