package app.ottermsg.core.crypto

import app.ottermsg.proto.v1.Record
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorsTest {
    private val v: Map<String, String> = run {
        val text = javaClass.getResource("/vectors/v1.json")!!.readText()
        Regex("\"(\\w+)\":\\s*\"?([^\",\\n]*)\"?").findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun accountKeyMatchesGo() {
        val kp = SeedKeys.accountKeyPair(hex(v.getValue("seed_hex")))
        assertArrayEquals(hex(v.getValue("account_pub_hex")), kp.publicKey)
        assertArrayEquals(hex(v.getValue("user_id_hex")), Ids.id16(kp.publicKey))
    }

    @Test fun networkIdMatchesGo() {
        assertArrayEquals(hex(v.getValue("network_id_hex")), Ids.id16(hex(v.getValue("network_pub_hex"))))
    }

    @Test fun verifiesGoRecords() {
        val dir = Record.parseFrom(hex(v.getValue("directory_entry_record_hex")))
        assertTrue(RecordCodec.verify(dir, hex(v.getValue("account_pub_hex"))))
        val bad = Record.parseFrom(hex(v.getValue("bad_signature_record_hex")))
        assertFalse(RecordCodec.verify(bad, hex(v.getValue("network_pub_hex"))))
    }

    @Test fun verifiesGoManifest() {
        val m = ManifestVerifier.verify(hex(v.getValue("manifest_record_hex")), hex(v.getValue("network_pub_hex")), currentVersion = 0)
        assertEquals(v.getValue("manifest_version").toLong(), m.version)
        assertEquals("chat.example.org", m.getNodes(0).getEndpoints(0).host)
    }

    @Test fun kotlinSignatureVerifiesWithKotlin() {
        val kp = SeedKeys.accountKeyPair(ByteArray(32) { it.toByte() })
        val rec = RecordCodec.sign(RecordCodec.body(
            type = app.ottermsg.proto.v1.RecordType.ACK, author = Ids.id16(kp.publicKey), payload = byteArrayOf(1)), kp)
        assertTrue(RecordCodec.verify(rec, kp.publicKey))
    }
}
