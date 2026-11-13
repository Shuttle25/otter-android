package app.ottermsg.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.ottermsg.core.database.entity.NetLogEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetLogDaoTest {
    private lateinit var db: OtterDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OtterDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() = db.close()

    private fun e(result: String, at: Long, network: String = "wifi", label: String = "ru-1", detail: String = "", operator: String = "") =
        NetLogEntity(nodeLabel = label, endpoint = "wss://1.2.3.4:27443", network = network, operator = operator, result = result,
            detail = detail, rttMs = 0, count = 1, firstMs = at, lastMs = at)

    @Test fun mergesRunsOfTheSameOutcome() = runTest {
        val d = db.netLogDao()
        d.add(e("timeout", 1, detail = "a"))
        d.add(e("timeout", 2, detail = "b"))
        d.add(e("timeout", 3, network = "mobile", operator = "MTS")) // other network → own row
        d.add(e("ok", 4))                                              // other result → new row
        d.add(e("timeout", 5))                                         // not merged into the older timeout run
        val rows = d.all()
        assertEquals(listOf("timeout" to 1, "ok" to 1, "timeout" to 1, "timeout" to 2), rows.map { it.result to it.count })
        val merged = rows.last()
        assertEquals(1L, merged.firstMs); assertEquals(2L, merged.lastMs); assertEquals("b", merged.detail)
        assertEquals("MTS", rows[2].operator)
        assertEquals(rows, d.observe().first())
    }

    @Test fun keepsTheNewest200() = runTest {
        val d = db.netLogDao()
        repeat(250) { d.add(e("ok", it.toLong(), label = "n$it")) }
        val rows = d.all()
        assertEquals(NetLogDaoMax, rows.size)
        assertEquals("n249", rows.first().nodeLabel); assertEquals("n50", rows.last().nodeLabel)
    }

    private companion object { const val NetLogDaoMax = 200 }
}
