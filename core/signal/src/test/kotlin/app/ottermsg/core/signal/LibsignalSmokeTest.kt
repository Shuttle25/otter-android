package app.ottermsg.core.signal

import org.junit.Assert.assertEquals
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.ECKeyPair

class LibsignalSmokeTest {
    @Test fun nativesLoad() {
        val id = IdentityKeyPair.generate()
        assertEquals(33, id.publicKey.serialize().size)
        assertEquals(33, ECKeyPair.generate().publicKey.serialize().size)
    }
}
