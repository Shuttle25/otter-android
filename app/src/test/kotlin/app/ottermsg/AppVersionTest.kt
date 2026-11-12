package app.ottermsg

import org.junit.Assert.assertEquals
import org.junit.Test

class AppVersionTest {
    @Test fun formats() {
        assertEquals("Otter 0.5.0 (abc1234)", AppVersion.text("0.5.0", "abc1234"))
        assertEquals("Otter 0.5.0 (dev)", AppVersion.text("0.5.0", ""))
    }
}
