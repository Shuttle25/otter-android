package app.ottermsg.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.ottermsg.core.data.network.EndpointRow
import app.ottermsg.core.data.network.NodeRow
import app.ottermsg.core.data.push.PushMode
import app.ottermsg.core.network.ConnectionState
import app.ottermsg.core.ui.theme.OtterTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class NetworkStatusScreenTest {
    @get:Rule val compose = createComposeRule()

    private val noon = 1_700_049_600_000L // 2023-11-15 12:00 UTC
    private val now = noon - 3_600_000

    private fun ep(name: String, success: Long? = null, failure: Long? = null, demoted: Long = 0, cdn: Boolean = false) =
        EndpointRow("k$name$cdn", "u", name, cdn, success, failure, success?.let { 80L }, if (failure != null) 2 else 0, demoted)

    private val ui = NetworkStatusUi(
        connection = ConnectionState.Waiting(noon + 5_000, "failed to connect to /203.0.113.7 (port 443)"),
        sinceMs = null, network = "mobile", operator = "Оператор",
        nodes = listOf(
            NodeRow("fi", listOf(ep("wss:443", success = now - 60_000), ep("wss:443", cdn = true)), version = "0.11.0"),
            NodeRow("ru", listOf(ep("wss:27443", failure = now - 120_000, demoted = noon))),
        ),
        pushMode = PushMode.FAST, googleWake = true, lastWakeMs = now - 300_000, nowMs = now,
        networkCode = "92144 18366 54483",
    )

    private fun show(u: NetworkStatusUi, restricted: Boolean, onBattery: () -> Unit = {}, onAppDetails: () -> Unit = {},
                     onNetLog: () -> Unit = {}) = compose.setContent {
        OtterTheme { NetworkStatusContent(u, restricted, onBack = {}, onBattery = onBattery, onAppDetails = onAppDetails,
            onNetLog = onNetLog, zone = ZoneOffset.UTC) }
    }

    @Test fun restrictedBatteryOffersTheSystemDialog() {
        var battery = 0; var details = 0
        show(ui, restricted = true, onBattery = { battery++ }, onAppDetails = { details++ })
        compose.onNodeWithText("Android может задерживать уведомления Otter").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Не ограничивать Otter").performScrollTo().performClick()
        compose.onNodeWithText("Автозапуск и фон").performScrollTo().performClick()
        assertEquals(1, battery); assertEquals(1, details)
    }

    @Test fun unrestrictedBatterySaysSoAndStillOffersAutostart() {
        show(ui, restricted = false)
        compose.onNodeWithText("Ограничений нет").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Автозапуск и фон").performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Не ограничивать Otter"))
    }

    @Test fun nodesShowHistoryDemotionAndNeverTried() {
        show(ui, restricted = false)
        compose.onNodeWithText("fi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ещё не подключались", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("понижен до 12:00", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("CDN", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun nowPhoneAndNotificationSections() {
        show(ui, restricted = false)
        // the retry time and reason; an address in the error text is masked (nothing but labels on this screen)
        compose.onNodeWithText("Нет подключения, повтор в 12:00:05", substring = true).assertIsDisplayed()
        compose.onNodeWithText("<ip>", substring = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("203.0.113.7", substring = true))
        compose.onNodeWithText("Мобильная · Оператор").assertIsDisplayed()
        compose.onNodeWithText("Быстрые уведомления").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Последнее пробуждение: 10:55", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun netLogRowOpensTheLog() {
        var opened = false
        show(ui.copy(connection = ConnectionState.Connecting, network = "none"), restricted = false, onNetLog = { opened = true })
        compose.onNodeWithText("Подключаемся…").assertIsDisplayed()
        compose.onNodeWithText("Нет сети").assertIsDisplayed()
        compose.onNodeWithText("Журнал подключений").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test fun networkCodeOnTopAndVersionOnlyForReachedNodes() {
        show(ui, restricted = false)
        compose.onNodeWithText("Код сети").assertIsDisplayed()
        compose.onNodeWithText("92144 18366 54483").assertIsDisplayed()
        compose.onNodeWithContentDescription("Код сети: 9 2 1 4 4 1 8 3 6 6 5 4 4 8 3").assertExists()
        compose.onNodeWithText("Версия 0.11.0").performScrollTo().assertIsDisplayed()
        assertEquals(1, compose.onAllNodesWithTextCount("Версия", substring = true)) // ru was never reached
    }

    @Test fun noCodeRowWithoutANetworkKey() {
        show(ui.copy(networkCode = ""), restricted = false)
        assertEquals(0, compose.onAllNodesWithTextCount("Код сети"))
    }

    @Test fun transportIsNamedFromTheUrlScheme() {
        assertEquals("WSS", transportOf("wss://h1:443/p"))
        assertEquals("WS", transportOf("ws://h1:8080/p"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String, substring: Boolean = false) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring)).fetchSemanticsNodes().size
}
