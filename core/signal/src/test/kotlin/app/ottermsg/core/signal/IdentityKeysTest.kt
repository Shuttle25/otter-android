package app.ottermsg.core.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityKeysTest {
    private val v: Map<String, String> = run {
        val text = javaClass.getResource("/vectors/v1.json")!!.readText()
        Regex("\"(\\w+)\":\\s*\"?([^\",\\n]*)\"?").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test fun identityMatchesGoX25519() {
        val id = IdentityKeys.fromSeed(v.getValue("seed_hex").hexToBytes())
        assertArrayEquals(v.getValue("identity_pub_hex").hexToBytes(), id.publicKey.serialize())
    }

    @Test fun accountBinding() {
        val id = IdentityKeys.fromSeed(ByteArray(32) { 3 })
        val account = ByteArray(32) { 9 }
        val sig = IdentityKeys.bindAccount(id, account)
        assertTrue(IdentityKeys.verifyBinding(id.publicKey.serialize(), account, sig))
        sig[0] = (sig[0].toInt() xor 1).toByte()
        assertFalse(IdentityKeys.verifyBinding(id.publicKey.serialize(), account, sig))
    }
}
