package app.ottermsg.core.crypto

import org.junit.Assert.assertThrows
import org.junit.Test

class InviteLinkTest {
    @Test fun rejectsScheme() { assertThrows(IllegalArgumentException::class.java) { InviteLink.decode("https://example.org/x") } }
    @Test fun rejectsBase64() { assertThrows(IllegalArgumentException::class.java) { InviteLink.decode("otter://join/@@@") } }
    @Test fun trimsWhitespace() { assertThrows(IllegalArgumentException::class.java) { InviteLink.decode("  otter://join/  ") } }
    @Test fun rejectsGarbageProto() { assertThrows(IllegalArgumentException::class.java) { InviteLink.decode("otter://join/_____w") } }
}
