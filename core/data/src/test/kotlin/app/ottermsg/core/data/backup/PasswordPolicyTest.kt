package app.ottermsg.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordPolicyTest {
    @Test fun rules() {
        assertEquals(PasswordPolicy.Result.TOO_SHORT, PasswordPolicy.check("short"))
        assertEquals(PasswordPolicy.Result.WEAK, PasswordPolicy.check("password12"))
        assertEquals(PasswordPolicy.Result.WEAK, PasswordPolicy.check("1234567890"))
        assertEquals(PasswordPolicy.Result.OK, PasswordPolicy.check("корова лодка зима семь"))
        assertEquals(PasswordPolicy.Result.OK, PasswordPolicy.check("Tr0ub4dour&3x!Lake"))
        assertEquals(0, PasswordPolicy.score(""))
        assertTrue(PasswordPolicy.score("Tr0ub4dour&3x!Lake") in 3..4)
    }
}
