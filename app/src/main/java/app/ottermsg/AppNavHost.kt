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
import app.ottermsg.feature.conversation.MessageInfoRoute
import app.ottermsg.feature.conversation.MessageInfoViewModel
import app.ottermsg.feature.groups.AddMembersRoute
import app.ottermsg.feature.groups.GroupInfoRoute
import app.ottermsg.feature.groups.GroupInfoViewModel
import app.ottermsg.feature.groups.NewGroupRoute
import app.ottermsg.feature.onboarding.OnboardingRoute
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val NEW_CHAT = "newChat"
    const val CONVERSATION = "conversation/{${ConversationViewModel.ARG_ID}}"
    const val INVITE = "invite"
    const val SETTINGS = "settings"
    const val NEW_GROUP = "newGroup"
    const val GROUP_INFO = "groupInfo/{${GroupInfoViewModel.ARG_GROUP}}"
    const val ADD_MEMBERS = "addMembers/{${GroupInfoViewModel.ARG_GROUP}}"
    fun conversation(id: Long) = "conversation/$id"
    fun groupInfo(groupIdHex: String) = "groupInfo/$groupIdHex"
    const val MESSAGE_INFO = "messageInfo/{${MessageInfoViewModel.ARG_LOCAL_ID}}"
    fun messageInfo(localId: Long) = "messageInfo/$localId"
    fun addMembers(groupIdHex: String) = "addMembers/$groupIdHex"
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
                onNewGroup = { nav.navigate(Routes.NEW_GROUP) },
            )
        }
        composable(Routes.NEW_GROUP) {
            NewGroupRoute(onBack = { nav.popBackStack() },
                onCreated = { id -> nav.navigate(Routes.conversation(id)) { popUpTo(Routes.CHATS) } })
        }
        composable(Routes.GROUP_INFO, arguments = listOf(navArgument(GroupInfoViewModel.ARG_GROUP) { type = NavType.StringType })) {
            GroupInfoRoute(onBack = { nav.popBackStack() }, onAddMembers = { nav.navigate(Routes.addMembers(it)) })
        }
        composable(Routes.ADD_MEMBERS, arguments = listOf(navArgument(GroupInfoViewModel.ARG_GROUP) { type = NavType.StringType })) {
            AddMembersRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.CONVERSATION, arguments = listOf(navArgument(ConversationViewModel.ARG_ID) { type = NavType.LongType })) {
            ConversationRoute(onBack = { nav.popBackStack() }, onOpenGroupInfo = { nav.navigate(Routes.groupInfo(it)) },
                onMessageInfo = { nav.navigate(Routes.messageInfo(it)) })
        }
        composable(Routes.MESSAGE_INFO, arguments = listOf(navArgument(MessageInfoViewModel.ARG_LOCAL_ID) { type = NavType.LongType })) {
            MessageInfoRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.INVITE) { InviteRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) { SettingsRoute(onBack = { nav.popBackStack() }, onInvite = { nav.navigate(Routes.INVITE) }) }
    }
}
