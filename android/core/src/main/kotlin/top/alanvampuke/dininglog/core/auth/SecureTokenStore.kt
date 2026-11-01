package top.alanvampuke.dininglog.core.auth

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyManager { fun encryptionKey(): SecretKey; fun existingKey(): SecretKey }
interface AtomicTokenFile { fun read(): ByteArray?; fun replace(bytes: ByteArray); fun delete() }
data class StoredSession(val userId: String, val sessionId: String, val refreshToken: String,
    val pendingRequestId: String? = null, val pendingStartedAt: Long? = null)

// Android Keystore supplies a non-exportable key through KeyManager. No plaintext fallback.
class AesGcmTokenCipher(private val keys: KeyManager, applicationId: String) {
    private val aad = "dining-log:token-file:v1:$applicationId".toByteArray(Charsets.UTF_8)
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore enforces randomized encryption and generates its own nonce.
        cipher.init(Cipher.ENCRYPT_MODE, keys.encryptionKey(), SecureRandom())
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
    }
    fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size >= 29 && sealed[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.existingKey(), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.copyOfRange(13, sealed.size))
    }
}
class SecureTokenStore(private val file: AtomicTokenFile, private val cipher: AesGcmTokenCipher) {
    @Synchronized fun read(): StoredSession? = try {
        file.read()?.let { sealed ->
            val plain = cipher.decrypt(sealed)
            try {
                DataInputStream(ByteArrayInputStream(plain)).use { input ->
                    val session = StoredSession(input.readUTF(), input.readUTF(), input.readUTF(),
                        if (input.readBoolean()) input.readUTF() else null,
                        if (input.readBoolean()) input.readLong() else null)
                    require(input.available() == 0 && session.refreshToken.isNotBlank())
                    require((session.pendingRequestId == null) == (session.pendingStartedAt == null))
                    session
                }
            } finally { plain.fill(0) }
        }
    } catch (_: Exception) { clear(); null }
    @Synchronized fun write(session: StoredSession) {
        try {
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeUTF(session.userId); out.writeUTF(session.sessionId); out.writeUTF(session.refreshToken)
                    out.writeBoolean(session.pendingRequestId != null); session.pendingRequestId?.let(out::writeUTF)
                    out.writeBoolean(session.pendingStartedAt != null); session.pendingStartedAt?.let(out::writeLong)
                }
            }.toByteArray()
            try { file.replace(cipher.encrypt(bytes)) } finally { bytes.fill(0) }
        } catch (error: Exception) { clear(); throw error }
    }
    @Synchronized fun clear() { file.delete() }
}
