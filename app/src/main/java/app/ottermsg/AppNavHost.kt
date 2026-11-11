package app.ottermsg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.feature.chats.ChatListRoute
import app.ottermsg.feature.chats.InviteRoute
import app.ottermsg.feature.chats.NewChatRoute
import app.ottermsg.feature.chats.SettingsRoute
import app.ottermsg.feature.conversation.ConversationRoute
import app.ottermsg.feature.conversation.ConversationViewModel
import app.ottermsg.feature.onboarding.OnboardingRoute
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val NEW_CHAT = "newChat"
    const val CONVERSATION = "conversation/{${ConversationViewModel.ARG_ID}}"
    const val INVITE = "invite"
    const val SETTINGS = "settings"
    fun conversation(id: Long) = "conversation/$id"
}

@Composable
fun AppNavHost(hasAccount: Boolean, account: StateFlow<LocalAccount?>) {
    val nav = rememberNavController()
    val me by account.collectAsStateWithLifecycle()
    NavHost(nav, startDestination = if (hasAccount) Routes.CHATS else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(onDone = { nav.navigate(Routes.CHATS) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.CHATS) {
            ChatListRoute(
                myName = me?.displayName.orEmpty(), mySeed = me?.userId ?: ByteArray(0),
                onOpen = { nav.navigate(Routes.conversation(it)) },
                onNewChat = { nav.navigate(Routes.NEW_CHAT) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.NEW_CHAT) {
            NewChatRoute(
                onBack = { nav.popBackStack() },
                onOpen = { id -> nav.navigate(Routes.conversation(id)) { popUpTo(Routes.CHATS) } },
                onInvite = { nav.navigate(Routes.INVITE) },
            )
        }
        composable(Routes.CONVERSATION, arguments = listOf(navArgument(ConversationViewModel.ARG_ID) { type = NavType.LongType })) {
            ConversationRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.INVITE) { InviteRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsRoute(onBack = { nav.popBackStack() }, onInvite = { nav.navigate(Routes.INVITE) }) }
    }
}
