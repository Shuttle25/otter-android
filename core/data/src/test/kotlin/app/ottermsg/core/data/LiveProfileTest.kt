package app.ottermsg.core.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Profile edit against a real node. Skipped unless OTTER_DEV_INVITE holds a fresh bootstrap link:
 *   otter-server/scripts/dev-node.sh --host 127.0.0.1   (or the VM: otter-server/deploy/test/e2e.sh --keep)
 *   OTTER_DEV_INVITE="otter://join/…" ./gradlew :core:data:testDebugUnitTest --tests '*LiveProfileTest*'
 */
@RunWith(RobolectricTestRunner::class)
class LiveProfileTest {
    @Test fun renameReachesTheOtherMember() = runBlocking {
        val invite = System.getenv("OTTER_DEV_INVITE")
        assumeTrue("set OTTER_DEV_INVITE to a fresh bootstrap link", !invite.isNullOrBlank())
        val suffix = (1000..9999).random()
        val a = LiveClient(); val b = LiveClient()
        try {
            a.register(invite!!, "Мама", "mama_$suffix")
            a.online()
            b.register(a.invites.create().link, "Боря", "borya_$suffix")
            b.online()
            eventually(timeoutMs = 30_000, what = "B sees A") { b.db.contactDao().get(a.me.userId) != null }
            a.profile.setDisplayName("Мама $suffix").getOrThrow()
            eventually(timeoutMs = 30_000, what = "published") { !a.settings.profilePending() }
            assertEquals(2, a.settings.entryVersion())
            eventually(timeoutMs = 30_000, what = "B sees the new name") {
                b.db.contactDao().get(a.me.userId)!!.displayName == "Мама $suffix"
            }
        } finally {
            a.close(); b.close()
        }
    }
}
