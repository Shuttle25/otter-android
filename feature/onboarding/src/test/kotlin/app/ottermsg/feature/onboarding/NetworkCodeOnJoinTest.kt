package app.ottermsg.feature.onboarding

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.ottermsg.core.crypto.InviteLink
import app.ottermsg.core.data.backup.Restorer
import app.ottermsg.core.data.registration.Registrar
import app.ottermsg.core.ui.theme.OtterTheme
import app.ottermsg.feature.onboarding.screens.ProfileScreen
import app.ottermsg.proto.v1.InviteBundle
import app.ottermsg.proto.v1.Record
import com.google.protobuf.ByteString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D139: the profile step shows the code of the network the invite link leads to. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru")
class NetworkCodeOnJoinTest {
    @get:Rule val compose = createComposeRule()

    private val registrar = object : Registrar {
        override suspend fun register(link: String, displayName: String, username: String, seed: ByteArray) = Result.success(Unit)
    }
    private val restorer = object : Restorer {
        override suspend fun withPassword(link: String, username: String, password: String) = Result.success(Unit)
        override suspend fun withWords(link: String, words: List<String>) = Result.success(Unit)
        override suspend fun addHistory(password: String) = Result.success(Unit)
    }
    // network_pub = 32 zero bytes: the first shared vector (vectors/network_code.json)
    private val link = InviteLink.encode(InviteBundle.newBuilder().setSecret(ByteString.copyFrom(ByteArray(32)))
        .setNetworkPub(ByteString.copyFrom(ByteArray(32))).setInviterName("Мама").setInvite(Record.getDefaultInstance()).build())

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun profileStepShowsTheNetworkCode() {
        val vm = OnboardingViewModel(registrar, restorer)
        vm.start(); vm.onLink(link); vm.submitLink()
        val state = vm.state.value
        assertEquals(OnboardingStep.PROFILE, state.step)
        assertEquals("92144 18366 54483", state.networkCode)

        compose.setContent { OtterTheme { ProfileScreen(state, {}, {}, {}, {}) } }
        compose.onNodeWithText("Вас приглашает Мама").assertExists()
        compose.onNodeWithText("Код сети: 92144 18366 54483").assertExists()
            .assertContentDescriptionEquals("Код сети: 9 2 1 4 4 1 8 3 6 6 5 4 4 8 3")
        compose.onNodeWithText("Сверьте его с тем, кто вас пригласил").assertExists()
    }
}
