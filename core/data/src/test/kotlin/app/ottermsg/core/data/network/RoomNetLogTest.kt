package app.ottermsg.core.data.network

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.data.TestClock
import app.ottermsg.core.database.OtterDatabase
import app.ottermsg.core.network.DAY_MS
import app.ottermsg.core.network.FailureClass
import app.ottermsg.core.network.NetLogEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
/** runBlocking, like the other core:data tests: runTest would report coroutines leaked by earlier tests. */
class RoomNetLogTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(ctx, OtterDatabase::class.java).allowMainThreadQueries().build()
    private val clock = TestClock()

    @After fun tearDown() = db.close()

    @Test fun mergesAndCaps() = runBlocking {
        val log = RoomNetLog(db.netLogDao(), object : CurrentNetwork(ctx) { override fun current() = "mobile" to "MTS" }, clock)
        repeat(3) { log.add(NetLogEntry("ru-1", "wss://1.2.3.4:27443", FailureClass.OK)) }
        log.add(NetLogEntry("ru-1", "wss://1.2.3.4:27443", FailureClass.TIMEOUT, "t"))
        val rows = db.netLogDao().all()
        assertEquals(2, rows.size)
        assertEquals("timeout", rows[0].result); assertEquals(3, rows[1].count); assertEquals("MTS", rows[1].operator)
        assertTrue(rows[1].lastMs > rows[1].firstMs)
        log.add(NetLogEntry("ru-1", "e", FailureClass.TLS, "x".repeat(500)))
        assertEquals(200, db.netLogDao().all()[0].detail.length)
        repeat(250) { log.add(NetLogEntry("n$it", "e", FailureClass.OK)) }
        assertEquals(200, db.netLogDao().all().size)
    }

    @Test fun currentNetworkWithoutConnectivityIsNone() {
        val (network, operator) = CurrentNetwork(ctx).current()
        assertTrue(network in setOf("wifi", "mobile", "other", "none"))
        if (network != "mobile") assertEquals("", operator)
    }

    @Test fun throttlesPersistAndDemote() = runBlocking {
        val stats = RoomEndpointStats(db.endpointStatDao())
        val now = 1_800_000_000_000
        stats.success("k", 40, now - 10_000)
        repeat(2) { stats.throttled("k", now + it) }
        stats.get("k")!!.let { assertEquals(2, it.throttleCount); assertEquals(0L, it.demotedUntilMs); assertEquals(40L, it.rttMs) }
        stats.throttled("k", now + 2)
        stats.get("k")!!.let { assertEquals(3, it.throttleCount); assertEquals(now + 2 + DAY_MS, it.demotedUntilMs); assertEquals(3, it.failures) }
        stats.success("k", 30, now + 3) // a success does not lift the demotion early
        assertEquals(now + 2 + DAY_MS, stats.get("k")!!.demotedUntilMs)
    }
}
