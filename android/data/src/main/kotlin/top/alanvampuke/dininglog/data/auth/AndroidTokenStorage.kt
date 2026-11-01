package top.alanvampuke.dininglog.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import top.alanvampuke.dininglog.core.auth.*

class KeystoreKeyManager(private val alias: String = "dining-log.refresh.v1") : KeyManager {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized override fun existingKey(): SecretKey = keyStore().getKey(alias, null) as? SecretKey ?: error("Missing token encryption key")
    @Synchronized override fun encryptionKey(): SecretKey {
        val store = keyStore()
        if (store.containsAlias(alias)) return existingKey()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
class AndroidAtomicTokenFile(path: File) : AtomicTokenFile {
    private val file = AtomicFile(path)
    override fun read(): ByteArray? = if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) file.readFully() else null
    override fun replace(bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    override fun delete() { file.delete() }
}
fun secureTokenStore(context: Context) = SecureTokenStore(
    AndroidAtomicTokenFile(File(context.noBackupFilesDir, "auth-session.enc")),
    AesGcmTokenCipher(KeystoreKeyManager(), context.packageName))
class AndroidAccountLocalData(private val context: Context, private val memory: AccountMemory) : AccountLocalData {
    override suspend fun clear() {
        memory.clear()
        // P2 has no Room/list CRUD yet. Delete the reserved cache and draft locations,
        // including database sidecars, so the same account lifecycle is ready for P3.
        context.cacheDir.deleteRecursively()
        File(context.filesDir, "drafts").deleteRecursively()
        for (name in context.databaseList()) check(context.deleteDatabase(name))
    }
}
