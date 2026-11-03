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

data class VehicleCredential(val vehicleId: String, val token: String) {
    override fun toString(): String = "VehicleCredential(redacted)"
}
interface VehicleCredentialStore {
    fun load(): VehicleCredential?
    fun save(credential: VehicleCredential)
    fun clear()
}

/** Independent key/file/AAD from pickup tokens; no plaintext or backup fallback. */
class EncryptedVehicleCredentialStore(context: Context, pending: Boolean = false) : VehicleCredentialStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, if (pending) "vehicle-enrollment.bin" else "vehicle-credential.bin"))
    private val alias = if (pending) "majung.vehicle-enrollment.v1" else "majung.vehicle-credential.v1"
    private val aad = ((if (pending) "majung-enrollment-v1:" else "majung-vehicle-v1:") + BuildConfig.PICKUP_API).toByteArray()
    @Synchronized override fun load(): VehicleCredential? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        require(file.baseFile.length() <= 4096)
        val bytes = file.readFully()
        require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(aad)
        val data = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        require(data.getInt("version") == 1)
        val credential = VehicleCredential(data.getString("vehicleId"), data.getString("vehicleToken"))
        validate(credential)
        return credential
    }
    @Synchronized override fun save(credential: VehicleCredential) {
        validate(credential)
        val data = JSONObject().put("version", 1).put("vehicleId", credential.vehicleId).put("vehicleToken", credential.token)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(aad)
        check(cipher.iv.size == 12)
        val bytes = cipher.iv + cipher.doFinal(data.toString().toByteArray())
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    @Synchronized override fun clear() {
        file.delete()
        check(!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists())
    }
    private fun validate(credential: VehicleCredential) {
        UUID.fromString(credential.vehicleId)
        require(credential.token.matches(Regex("[A-Za-z0-9_-]{43}")))
    }
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
}
