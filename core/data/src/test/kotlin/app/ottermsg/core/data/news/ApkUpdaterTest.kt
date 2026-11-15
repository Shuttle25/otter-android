package app.ottermsg.core.data.news

import app.ottermsg.core.data.bs
import app.ottermsg.core.data.invite.OnlineApi
import app.ottermsg.core.network.NodeApi
import app.ottermsg.core.network.ReceiveLimits
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.ApkChunk
import app.ottermsg.proto.v1.AppUpdate
import app.ottermsg.proto.v1.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import kotlin.random.Random

class ApkUpdaterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val apk = Random(7).nextBytes(2 * (1 shl 20) + (1 shl 19)) // 2.5 MiB
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk)
    private val hex = sha.joinToString("") { "%02x".format(it) }
    private val update = AppUpdate.newBuilder().setVersionName("0.6.0").setVersionCode(6).setSha256(bs(sha)).setSize(apk.size.toLong()).build()

    /** Serves [data] in 1 MiB chunks; every other NodeApi call fails the test. */
    private class FakeApi(val data: ByteArray?, val corruptAt: Int = -1, val chunkSize: (Int) -> Int? = { null }) : NodeApi by unused() {
        val requested = mutableListOf<Int>()
        override suspend fun apkGet(sha256: ByteArray, index: Int): ApkChunk {
            requested += index
            val d = data ?: throw RpcException(Status.STATUS_NOT_FOUND, "")
            val from = index * ApkUpdater.CHUNK.toInt()
            if (from >= d.size) throw RpcException(Status.STATUS_NOT_FOUND, "")
            val chunk = d.copyOfRange(from, minOf(d.size, from + ApkUpdater.CHUNK.toInt()))
            if (corruptAt in from until from + chunk.size) chunk[corruptAt - from] = (chunk[corruptAt - from] + 1).toByte()
            val n = chunkSize(index) ?: chunk.size
            return ApkChunk.newBuilder().setData(bs(chunk.copyOf(n))).setTotalSize(d.size.toLong()).build()
        }
        companion object {
            fun unused(): NodeApi = Proxy.newProxyInstance(NodeApi::class.java.classLoader, arrayOf(NodeApi::class.java)) { _, m, _ ->
                throw AssertionError("unexpected NodeApi.${m.name}")
            } as NodeApi
        }
    }

    private fun updater(api: NodeApi?) = ApkUpdater(OnlineApi { api }, tmp.root)
    private fun dir() = File(tmp.root, "apk")

    @Test fun downloadsVerifiesAndRenames() = runBlocking {
        val api = FakeApi(apk)
        val progress = mutableListOf<Float>()
        val r = updater(api).download(update) { progress += it }
        assertTrue(r is UpdateResult.Ready)
        val f = (r as UpdateResult.Ready).file
        assertEquals(File(dir(), "$hex.apk"), f)
        assertArrayEquals(apk, f.readBytes())
        assertEquals(listOf(0, 1, 2), api.requested)
        assertEquals(1f, progress.last())
        assertFalse(File(dir(), "$hex.part").exists())
    }

    @Test fun corruptFileIsDeleted() = runBlocking {
        val r = updater(FakeApi(apk, corruptAt = 1_500_000)).download(update) {}
        assertEquals(UpdateResult.Corrupt, r)
        assertTrue(dir().listFiles().orEmpty().isEmpty())
    }

    @Test fun notOnServer() = runBlocking {
        assertEquals(UpdateResult.NotOnServer, updater(FakeApi(null)).download(update) {})
    }

    @Test fun offlineWithoutConnection() = runBlocking {
        assertEquals(UpdateResult.Offline, updater(null).download(update) {})
    }

    @Test fun resumesAtAFullChunkBoundary() = runBlocking {
        dir().mkdirs()
        File(dir(), "$hex.part").writeBytes(apk.copyOfRange(0, (1 shl 20) + 10))
        val api = FakeApi(apk)
        val r = updater(api).download(update) {}
        assertTrue(r is UpdateResult.Ready)
        assertEquals(listOf(1, 2), api.requested)
        assertArrayEquals(apk, (r as UpdateResult.Ready).file.readBytes())
    }

    @Test fun reusesAVerifiedApkAndDropsOlderOnes() = runBlocking {
        dir().mkdirs()
        File(dir(), "$hex.apk").writeBytes(apk)
        val api = FakeApi(apk)
        assertTrue(updater(api).download(update) {} is UpdateResult.Ready)
        assertEquals(emptyList<Int>(), api.requested)

        File(dir(), "$hex.apk").delete()
        File(dir(), "0000.apk").writeBytes(byteArrayOf(1))
        assertTrue(updater(api).download(update) {} is UpdateResult.Ready)
        assertEquals(listOf("$hex.apk"), dir().list()!!.toList())
    }

    @Test fun sizeOverTheLimitIsRefusedWithoutARequest() = runBlocking {
        for (size in listOf(0L, -1L, ReceiveLimits.APK_MAX + 1)) {
            val api = FakeApi(apk)
            val r = updater(api).download(update.toBuilder().setSize(size).build()) {}
            assertEquals(UpdateResult.Corrupt, r)
            assertEquals(emptyList<Int>(), api.requested)
        }
    }

    @Test fun aChunkOfTheWrongSizeIsCorrupt() = runBlocking {
        val api = FakeApi(apk, chunkSize = { i -> if (i == 0) 10 else null })
        assertEquals(UpdateResult.Corrupt, updater(api).download(update) {})
        assertEquals("stops at the first bad chunk", listOf(0), api.requested)
        assertTrue(dir().listFiles().orEmpty().isEmpty())
    }

    @Test fun aNodeCannotGrowTheFilePastTheSignedSize() = runBlocking {
        // the first chunk padded to 3 MiB: without the check it is written and the loop goes on
        val api = FakeApi(apk, chunkSize = { i -> if (i == 0) 3 shl 20 else null })
        assertEquals(UpdateResult.Corrupt, updater(api).download(update) {})
        assertEquals(listOf(0), api.requested)
        assertTrue(dir().listFiles().orEmpty().isEmpty())
    }
}
