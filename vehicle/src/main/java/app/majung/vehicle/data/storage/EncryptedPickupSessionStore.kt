package app.majung.vehicle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One small atomic ciphertext in noBackupFilesDir; never falls back to plaintext. */
class EncryptedPickupSessionStore(context: Context) : PickupSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "pickup-session.bin"))
    private val alias = "majung.pickup-session.v1"
    private val aad = ("majung-session-v1:" + BuildConfig.PICKUP_API).toByteArray(Charsets.UTF_8)

    @Synchronized override fun load(): SavedPickupSession? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= 16384)
        val bytes = file.readFully()
        require(bytes.size in 29..16384)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(data.getInt("version") == 1)
        val id = data.getString("pickupId")
        UUID.fromString(id)
        val driver = data.getString("driverToken")
        val invite = if (data.isNull("inviteToken")) null else data.getString("inviteToken")
        require(driver.matches(Regex("[A-Za-z0-9_-]{43}")))
        require(invite == null || invite.matches(Regex("[A-Za-z0-9_-]{43}")))
        val sequence = data.getLong("vehicleSequence")
        require(sequence >= 0 && sequence < Long.MAX_VALUE)
        val navigationReference = if (!data.has("navigationReference") || data.isNull("navigationReference")) null
            else data.getString("navigationReference")
        require(navigationReference == null || navigationReference.isNotBlank() && navigationReference.length <= 256)
        return SavedPickupSession(id, driver, invite, sequence, navigationReference)
    }

    @Synchronized override fun save(session: SavedPickupSession) {
        val data = JSONObject().put("version", 1).put("pickupId", session.pickupId)
            .put("driverToken", session.driverToken).put("inviteToken", session.inviteToken ?: JSONObject.NULL)
            .put("vehicleSequence", session.vehicleSequence)
            .put("navigationReference", session.navigationReference ?: JSONObject.NULL)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    @Synchronized override fun clear() {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "Session key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
