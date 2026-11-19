package app.ottermsg

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import app.ottermsg.calls.CallActivity
import app.ottermsg.core.data.account.LocalAccount
import app.ottermsg.feature.chats.ChatListRoute
import app.ottermsg.feature.chats.InviteRoute
import app.ottermsg.feature.chats.NewChatRoute
import app.ottermsg.feature.conversation.ConversationRoute
import app.ottermsg.feature.conversation.ConversationViewModel
import app.ottermsg.feature.conversation.MessageInfoRoute
import app.ottermsg.feature.conversation.MessageInfoViewModel
import app.ottermsg.feature.groups.AddMembersRoute
import app.ottermsg.feature.groups.GroupInfoRoute
import app.ottermsg.feature.groups.GroupInfoViewModel
import app.ottermsg.feature.groups.NewGroupRoute
import app.ottermsg.feature.onboarding.OnboardingRoute
import app.ottermsg.feature.conversation.PhotoViewerRoute
import app.ottermsg.feature.conversation.PhotoViewerViewModel
import app.ottermsg.feature.settings.AppLockRoute
import app.ottermsg.feature.settings.AppearanceRoute
import app.ottermsg.feature.settings.RecoveryPhraseRoute
import app.ottermsg.feature.settings.EditNameRoute
import app.ottermsg.feature.settings.BackupRoute
import app.ottermsg.feature.settings.NetLogRoute
import app.ottermsg.feature.settings.NetworkStatusRoute
import app.ottermsg.feature.settings.NotificationsRoute
import app.ottermsg.feature.settings.PhotoStorageRoute
import app.ottermsg.feature.settings.ProfileRoute
import app.ottermsg.feature.settings.SettingsRoute
import app.ottermsg.notify.NotificationPermissionOnce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object Routes {
    const val ONBOARDING = "onboarding"
    const val CHATS = "chats"
    const val NEW_CHAT = "newChat"
    const val CONVERSATION = "conversation/{${ConversationViewModel.ARG_ID}}"
    const val INVITE = "invite"
    const val SETTINGS = "settings"
    const val PROFILE = "settings/profile"
    const val EDIT_NAME = "settings/profile/name"
    const val PHOTOS = "settings/photos"
    const val BACKUP = "settings/backup"
    const val NOTIFICATIONS = "settings/notifications"
    const val NETLOG = "settings/netlog"
    const val APP_LOCK = "settings/lock"
    const val NETWORK = "settings/network"
    const val APPEARANCE = "settings/appearance"
    const val PHRASE = "settings/phrase"
    const val PHOTO = "photo/{${PhotoViewerViewModel.ARG_LOCAL_ID}}"
    fun photo(localId: Long) = "photo/$localId"
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
fun AppNavHost(hasAccount: Boolean, account: StateFlow<LocalAccount?>,
               openConversation: MutableStateFlow<Long?> = MutableStateFlow(null)) {
    val nav = rememberNavController()
    val me by account.collectAsStateWithLifecycle()
    // Notification tap (D62): open that chat on top of the chat list.
    val open by openConversation.collectAsStateWithLifecycle()
    LaunchedEffect(open) {
        val id = open ?: return@LaunchedEffect
        if (hasAccount) nav.navigate(Routes.conversation(id)) { popUpTo(Routes.CHATS) }
        openConversation.value = null
    }
    NavHost(nav, startDestination = if (hasAccount) Routes.CHATS else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingRoute(onDone = { nav.navigate(Routes.CHATS) { popUpTo(Routes.ONBOARDING) { inclusive = true } } })
        }
        composable(Routes.CHATS) {
            NotificationPermissionOnce()
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
            val context = LocalContext.current
            ConversationRoute(onBack = { nav.popBackStack() }, onOpenGroupInfo = { nav.navigate(Routes.groupInfo(it)) },
                onMessageInfo = { nav.navigate(Routes.messageInfo(it)) }, onOpenPhoto = { nav.navigate(Routes.photo(it)) },
                onCallStarted = { context.startActivity(CallActivity.outgoing(context)) },
                onOpenConversation = { id -> nav.navigate(Routes.conversation(id)) { popUpTo(Routes.CHATS) } })
        }
        composable(Routes.MESSAGE_INFO, arguments = listOf(navArgument(MessageInfoViewModel.ARG_LOCAL_ID) { type = NavType.LongType })) {
            MessageInfoRoute(onBack = { nav.popBackStack() })
        }
        composable(Routes.INVITE) { InviteRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.SETTINGS) {
            SettingsRoute(onBack = { nav.popBackStack() }, onInvite = { nav.navigate(Routes.INVITE) },
                onProfile = { nav.navigate(Routes.PROFILE) }, onPhotos = { nav.navigate(Routes.PHOTOS) },
                onBackup = { nav.navigate(Routes.BACKUP) }, onNotifications = { nav.navigate(Routes.NOTIFICATIONS) },
                onNetwork = { nav.navigate(Routes.NETWORK) }, onAppLock = { nav.navigate(Routes.APP_LOCK) },
                onAppearance = { nav.navigate(Routes.APPEARANCE) }, onPhrase = { nav.navigate(Routes.PHRASE) },
                versionText = AppVersion.current)
        }
        composable(Routes.PROFILE) {
            ProfileRoute(onBack = { nav.popBackStack() }, onEditName = { nav.navigate(Routes.EDIT_NAME) })
        }
        composable(Routes.EDIT_NAME) { EditNameRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.PHOTOS) { PhotoStorageRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.BACKUP) { BackupRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.NOTIFICATIONS) { NotificationsRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.APP_LOCK) { AppLockRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.APPEARANCE) { AppearanceRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.PHRASE) { RecoveryPhraseRoute(onBack = { nav.popBackStack() }) }
        composable(Routes.NETWORK) { NetworkStatusRoute(onBack = { nav.popBackStack() }, onNetLog = { nav.navigate(Routes.NETLOG) }) }
        composable(Routes.NETLOG) { NetLogRoute(onBack = { nav.popBackStack() }, versionText = AppVersion.current) }
        composable(Routes.PHOTO, arguments = listOf(navArgument(PhotoViewerViewModel.ARG_LOCAL_ID) { type = NavType.LongType })) {
            PhotoViewerRoute(onBack = { nav.popBackStack() })
        }
    }
}
