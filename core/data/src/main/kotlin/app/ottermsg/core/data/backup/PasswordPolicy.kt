package app.ottermsg.core.data.backup

import com.nulabinc.zxcvbn.Zxcvbn

/** Backup password rule (D55): at least 10 characters and zxcvbn score ≥ 3. */
object PasswordPolicy {
    enum class Result { OK, TOO_SHORT, WEAK }
    const val MIN_LENGTH = 10
    private val zxcvbn by lazy { Zxcvbn() }
    fun score(pw: String): Int = if (pw.isEmpty()) 0 else zxcvbn.measure(pw).score
    fun check(pw: String): Result = when {
        pw.length < MIN_LENGTH -> Result.TOO_SHORT
        score(pw) < 3 -> Result.WEAK
        else -> Result.OK
    }
}
