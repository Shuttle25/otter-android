package app.ottermsg.notify

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullScreenIntentAskTest {
    @Test fun shownOnlyOnApi34PlusWhenNotGrantedAndNotAskedBefore() {
        assertTrue(shouldAskFullScreenIntent(34, canUseFullScreenIntent = false, askedBefore = false))
        assertTrue(shouldAskFullScreenIntent(36, canUseFullScreenIntent = false, askedBefore = false))
        assertFalse(shouldAskFullScreenIntent(33, canUseFullScreenIntent = false, askedBefore = false))
        assertFalse(shouldAskFullScreenIntent(34, canUseFullScreenIntent = true, askedBefore = false))
        assertFalse(shouldAskFullScreenIntent(34, canUseFullScreenIntent = false, askedBefore = true))
    }

    private class Store(var asked: Boolean = false) {
        var reads = 0; var writes = 0
        suspend fun read(): Boolean { reads++; return asked }
        suspend fun mark() { writes++; asked = true }
    }

    @Test fun flagStoredAfterShowingSoItIsAskedOnce() = runTest {
        val s = Store()
        assertTrue(askFullScreenIntentOnce(34, false, s::read, s::mark))
        assertTrue(s.asked); assertEquals(1, s.writes)
        // «Не сейчас» or «Разрешить» without granting: never asked again automatically.
        assertFalse(askFullScreenIntentOnce(34, false, s::read, s::mark))
        assertEquals(1, s.writes)
    }

    @Test fun grantedOrOldAndroidStoresNothing() = runTest {
        val s = Store()
        assertFalse(askFullScreenIntentOnce(34, true, s::read, s::mark))
        assertFalse(askFullScreenIntentOnce(33, false, s::read, s::mark))
        assertFalse(s.asked); assertEquals(0, s.writes); assertEquals(0, s.reads)
    }
}
