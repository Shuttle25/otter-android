package app.ottermsg.core.crypto

import com.google.crypto.tink.subtle.Hkdf
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom

/** Password backup keys (docs/04 "Password backup", D55). The password never leaves the phone. */
object BackupKeys {
    const val INFO_ENC = "otter/backup/enc/v1"
    const val INFO_AUTH = "otter/backup/auth/v1"
    const val SALT_LEN = 16

    /**
     * Client-owned KDF profiles (C1). The numbers a node returns are only an ID: they must equal one of these exactly,
     * or the restore stops before the password is used. A stronger profile is added here and chosen by the phone
     * ([CURRENT]) when a password is set — never by a node.
     */
    enum class Profile(val mKib: Int, val t: Int, val p: Int) {
        P1(65536, 3, 1);

        companion object {
            val CURRENT = P1
            fun match(mKib: Int, t: Int, p: Int, saltLen: Int): Profile? =
                if (saltLen != SALT_LEN) null else entries.firstOrNull { it.mKib == mKib && it.t == t && it.p == p }
        }
    }

    class Keys(val backupKey: ByteArray, val authSecret: ByteArray) {
        val verifier: ByteArray get() = MessageDigest.getInstance("SHA-256").digest(authSecret)
    }

    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also(SecureRandom()::nextBytes)

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

    /** master = Argon2id(password) with [profile] → 64 bytes; backup_key / auth_secret = HKDF(master, info). */
    fun derive(password: String, salt: ByteArray, profile: Profile): Keys {
        require(salt.size == SALT_LEN) { "salt must be $SALT_LEN bytes" }
        return derive(password, salt, profile.mKib, profile.t, profile.p)
    }

    /** Raw parameters: only for [derive] with a profile and for tests (cheap parameters). */
    fun derive(password: String, salt: ByteArray, mKib: Int, t: Int, p: Int): Keys {
        val master = argon2id(password.toByteArray(Charsets.UTF_8), salt, mKib, t, p, 64)
        return Keys(Hkdf.computeHkdf("HMACSHA256", master, ByteArray(0), INFO_ENC.toByteArray(), 32),
            Hkdf.computeHkdf("HMACSHA256", master, ByteArray(0), INFO_AUTH.toByteArray(), 32))
    }
}
