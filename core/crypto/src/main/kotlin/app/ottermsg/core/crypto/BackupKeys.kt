package app.ottermsg.core.crypto

import com.google.crypto.tink.subtle.Hkdf
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom

/** Password backup keys (docs/04 "Password backup", D55). The password never leaves the phone. */
object BackupKeys {
    const val M_KIB = 65536
    const val T = 3
    const val P = 1
    const val INFO_ENC = "otter/backup/enc/v1"
    const val INFO_AUTH = "otter/backup/auth/v1"

    class Keys(val backupKey: ByteArray, val authSecret: ByteArray) {
        val verifier: ByteArray get() = MessageDigest.getInstance("SHA-256").digest(authSecret)
    }

    fun newSalt(): ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)

    fun argon2id(password: ByteArray, salt: ByteArray, mKib: Int, t: Int, p: Int, outLen: Int,
                 secret: ByteArray? = null, ad: ByteArray? = null): ByteArray {
        val b = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt).withMemoryAsKB(mKib).withIterations(t).withParallelism(p)
        secret?.let { b.withSecret(it) }
        ad?.let { b.withAdditional(it) }
        val out = ByteArray(outLen)
        Argon2BytesGenerator().apply { init(b.build()) }.generateBytes(password, out)
        return out
    }

    /** master = Argon2id(password) → 64 bytes; backup_key / auth_secret = HKDF(master, info). */
    fun derive(password: String, salt: ByteArray, mKib: Int = M_KIB, t: Int = T, p: Int = P): Keys {
        val master = argon2id(password.toByteArray(Charsets.UTF_8), salt, mKib, t, p, 64)
        return Keys(Hkdf.computeHkdf("HMACSHA256", master, ByteArray(0), INFO_ENC.toByteArray(), 32),
            Hkdf.computeHkdf("HMACSHA256", master, ByteArray(0), INFO_AUTH.toByteArray(), 32))
    }
}
