package app.ottermsg.feature.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.ottermsg.core.data.calls.CallNotice
import app.ottermsg.core.data.calls.CallStarter
import app.ottermsg.core.data.calls.CallState
import app.ottermsg.core.data.calls.Peer
import app.ottermsg.core.data.directory.FoundPerson
import app.ottermsg.core.data.directory.PersonFinder
import app.ottermsg.core.data.directory.UsernameLookup
import app.ottermsg.core.data.media.PhotoStore
import app.ottermsg.core.data.messaging.ConversationStore
import app.ottermsg.core.data.messaging.GroupView
import app.ottermsg.core.data.push.ActiveConversation
import app.ottermsg.core.database.entity.AttachmentEntity
import app.ottermsg.core.database.entity.ContactEntity
import app.ottermsg.core.database.entity.ConversationEntity
import app.ottermsg.core.database.entity.ConversationType
import app.ottermsg.core.data.news.InstalledVersion
import app.ottermsg.core.data.news.UpdateDownloader
import app.ottermsg.core.data.news.UpdateInstaller
import app.ottermsg.core.data.news.UpdateResult
import app.ottermsg.core.network.RpcException
import app.ottermsg.proto.v1.AppUpdate
import androidx.annotation.StringRes
import java.io.IOException
import app.ottermsg.proto.v1.Announcement
import app.ottermsg.core.database.entity.MessageEntity
import app.ottermsg.core.ui.components.parseGroupEvent
import app.ottermsg.core.database.model.MessageKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId
import javax.inject.Inject

data class ConversationUi(
    /** False until the first database emission (the empty-chat art must not flash while loading). */
    val loaded: Boolean = false,
    val title: String = "", val seed: ByteArray = ByteArray(0), val items: List<ConversationItem> = emptyList(), val lastIncomingId: Long? = null,
    val isGroup: Boolean = false, val family: Boolean = false, val groupIdHex: String = "",
    /** Real member count of a group (subtitle "N участников"). */
    val memberCount: Int = 0,
    /** False for a group we are no longer a member of: the composer is replaced by a notice. */
    val canWrite: Boolean = true,
    val me: ByteArray = ByteArray(0),
    /** Display names by user id hex, for group events. */
    val names: Map<String, String> = emptyMap(),
    /** The «Новости Выдры» chat (D64): localized title, otter avatar, no composer. */
    val isNews: Boolean = false,
    /** 1:1 chat with a contact: the top bar shows the call button (D80). */
    val canCall: Boolean = false,
    val peerId: ByteArray = ByteArray(0),
    /** 1:1 chat with a removed member (D104): «Участник удалён» instead of the composer, no call button. */
    val peerRemoved: Boolean = false,
    /**
     * 1:1 chat with a contact revoked for a re-key (D111 step 1) whose REKEY has not moved the chat yet: «Отправка
     * приостановлена» instead of the composer, no call button. Not a removal: no «Участник удалён».
     */
    val peerRekeying: Boolean = false,
) {
    fun nameOf(id: ByteArray): String = names[id.hex()] ?: "?"
}

enum class PhotoEvent { Unreadable, Saved, SaveFailed }

/** The «Обновить» row of a news message with an app update (D68). */
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data class Downloading(val percent: Int) : UpdateUi
    data class Error(@param:StringRes val message: Int) : UpdateUi
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

/** Pure mapping, unit-tested. */
fun conversationUi(
    conv: ConversationEntity?, msgs: List<MessageEntity>, group: GroupView?, contacts: List<ContactEntity>, me: ByteArray, zone: ZoneId,
    attachments: Map<Long, AttachmentEntity> = emptyMap(), revoked: Set<String> = emptySet(), rekeying: Set<String> = emptySet(),
): ConversationUi {
    val title = conv?.title.orEmpty()
    val names = contacts.associate { it.userId.hex() to it.displayName }
    val isGroup = conv?.groupId != null
    val isNews = conv?.type == ConversationType.NEWS
    val peerRevoked = !isGroup && !isNews && conv?.peerUserId?.hex() in revoked
    val peerRekeying = peerRevoked && conv?.peerUserId?.hex() in rekeying
    val peerRemoved = peerRevoked && !peerRekeying
    val list = msgs.map {
        val author = when {
            it.outgoing -> ""
            isGroup -> names[it.senderId.hex()] ?: "?"
            else -> title
        }
        MessageUi(it.localId, it.outgoing, it.body, it.sentMs, it.status, it.kind == MessageKind.SYSTEM_UNDECRYPTABLE, author,
            authorKey = if (it.outgoing) "me" else it.senderId.hex(),
            event = if (it.kind == MessageKind.SYSTEM_GROUP) parseGroupEvent(it.systemData) else null,
            inGroup = isGroup && !it.outgoing,
            photo = if (it.kind == MessageKind.PHOTO) attachments[it.localId]?.let(::photoUi) else null,
            resetName = if (it.kind == MessageKind.SYSTEM_RESET) (names[it.senderId.hex()] ?: title) else null,
            news = if (it.kind == MessageKind.NEWS) it.systemData?.let { d -> runCatching { Announcement.parseFrom(d) }.getOrNull() } else null,
            call = if (it.kind == MessageKind.CALL) CallLineUi(it.callOutcome, it.callDurationS) else null,
            memberNote = if (it.kind == MessageKind.SYSTEM_MEMBER) it.systemData?.decodeToString() ?: "" else null,
            keyChangedName = if (it.kind == MessageKind.SYSTEM_KEY_CHANGED) (names[it.senderId.hex()] ?: title) else null)
    }
    return ConversationUi(true, title, conv?.peerUserId ?: conv?.groupId ?: ByteArray(0), groupMessages(list, zone),
        msgs.lastOrNull { !it.outgoing && it.kind != MessageKind.SYSTEM_GROUP && it.kind != MessageKind.SYSTEM_RESET &&
            it.kind != MessageKind.SYSTEM_MEMBER && it.kind != MessageKind.SYSTEM_KEY_CHANGED }?.localId,
        isGroup = isGroup, family = group?.group?.family == true, groupIdHex = conv?.groupId?.hex().orEmpty(),
        memberCount = group?.members?.size ?: 0,
        canWrite = !isNews && !peerRevoked && (!isGroup || group?.group?.iAmMember == true),
        me = me, names = names, isNews = isNews,
        canCall = !isGroup && !isNews && !peerRevoked && conv?.peerUserId != null, peerId = conv?.peerUserId ?: ByteArray(0),
        peerRemoved = peerRemoved, peerRekeying = peerRekeying)
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val store: ConversationStore, private val photos: PhotoStore, private val savedState: SavedStateHandle,
    private val active: ActiveConversation = ActiveConversation(),
    private val downloader: UpdateDownloader = object : UpdateDownloader {
        override suspend fun download(u: AppUpdate, progress: (Float) -> Unit) = UpdateResult.Offline
    },
    installedVersion: InstalledVersion = InstalledVersion { Int.MAX_VALUE },
    private val installer: UpdateInstaller = UpdateInstaller {},
    private val calls: CallStarter = CallStarter.NONE,
    private val people: PersonFinder = PersonFinder.NONE,
) : ViewModel() {
    val conversationId: Long = checkNotNull(savedState.get<Long>(ARG_ID)) { "conversation id missing" }
    var zone: ZoneId = ZoneId.systemDefault()

    private class Parts(val conv: ConversationEntity?, val msgs: List<MessageEntity>, val group: GroupView?,
                        val contacts: List<ContactEntity>, val me: ByteArray)

    val ui: StateFlow<ConversationUi> = combine(
        combine(
            store.observeConversation(conversationId), store.observeMessages(conversationId), store.observeGroup(conversationId),
            store.observeContacts(), flow { emit(store.myId()) },
        ) { conv, msgs, group, contacts, me -> Parts(conv, msgs, group, contacts, me) },
        photos.observe(conversationId), store.observeRevokedIds(), store.observeRekeyingIds(),
    ) { p, att, rev, rekeying -> conversationUi(p.conv, p.msgs, p.group, p.contacts, p.me, zone, att, rev, rekeying) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUi())

    /** Decoded images for the bubbles (no Room or crypto in composables). */
    val photoSource: PhotoSource = object : PhotoSource {
        override suspend fun load(localId: Long, maxPx: Int): ImageBitmap? = photos.load(localId, maxPx)?.asImageBitmap()
        override suspend fun thumb(localId: Long): ImageBitmap? = photos.thumb(localId)?.asImageBitmap()
        override suspend fun corrupt(localId: Long): Boolean = photos.corrupt(localId)
    }

    /** Path of the picked/taken photo shown in the preview (survives process death). */
    val pendingPhoto: StateFlow<String?> = savedState.getStateFlow(KEY_PENDING, null)
    private val _photoSending = MutableStateFlow(false)
    val photoSending: StateFlow<Boolean> = _photoSending
    private val _events = MutableSharedFlow<PhotoEvent>(extraBufferCapacity = 4)
    /** One-off messages for a snackbar. */
    val events: SharedFlow<PhotoEvent> = _events

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending
    private val _sent = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emits after an own message was stored (the screen scrolls to the bottom). */
    val sent: SharedFlow<Long> = _sent

    init {
        viewModelScope.launch {
            val draft = store.observeConversation(conversationId).filterNotNull().first().draft
            if (_text.value.isEmpty()) _text.value = draft
            _text.drop(1).debounce(DRAFT_DEBOUNCE_MS).collect { store.setDraft(conversationId, it) }
        }
    }

    fun onText(v: String) { _text.value = v }

    /** The field is cleared only after the message is safely in the local queue; double taps are ignored. */
    fun send() {
        val t = _text.value
        if (t.isBlank() || _sending.value) return
        _sending.value = true
        viewModelScope.launch {
            try {
                val id = store.sendText(conversationId, t)
                if (id != null) {
                    if (_text.value == t) _text.value = ""
                    _sent.tryEmit(id)
                }
            } finally {
                _sending.value = false
            }
        }
    }

    fun onPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val f = photos.importPicked(uri)
            if (f == null) _events.tryEmit(PhotoEvent.Unreadable) else savedState[KEY_PENDING] = f.path
        }
    }

    /** A file for the camera app; remembered so the result survives process death. */
    fun newCameraFile(): File = photos.newCameraFile().also { savedState[KEY_CAMERA] = it.path }

    fun onCameraResult(taken: Boolean) {
        val p = savedState.get<String>(KEY_CAMERA) ?: return
        savedState[KEY_CAMERA] = null
        if (taken && File(p).length() > 0) savedState[KEY_PENDING] = p else photos.discard(File(p))
    }

    /** One send per tap; on success the preview closes and the draft is cleared. */
    fun sendPhoto(caption: String) {
        val path = pendingPhoto.value ?: return
        if (_photoSending.value) return
        _photoSending.value = true
        viewModelScope.launch {
            try {
                val id = store.sendPhoto(conversationId, File(path), caption)
                if (id == null) _events.tryEmit(PhotoEvent.Unreadable)
                else {
                    savedState[KEY_PENDING] = null
                    _text.value = ""
                    _sent.tryEmit(id)
                }
            } finally {
                _photoSending.value = false
            }
        }
    }

    /** Closes the preview; the draft stays. */
    fun cancelPhoto() {
        pendingPhoto.value?.let { photos.discard(File(it)) }
        savedState[KEY_PENDING] = null
    }

    fun download(localId: Long) = viewModelScope.launch { photos.download(localId) }
    fun galleryNeedsPermission() = photos.galleryNeedsPermission()
    fun saveToGallery(localId: Long) = viewModelScope.launch {
        _events.tryEmit(if (photos.saveToGallery(localId)) PhotoEvent.Saved else PhotoEvent.SaveFailed)
    }

    /** «Обновить» shows only for an update newer than this (D68). */
    val installedCode: Int = installedVersion.code()
    private val _updateState = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val updateState: StateFlow<UpdateUi> = _updateState

    /** Downloads and verifies the APK, then opens the installer (on Main); taps while downloading are ignored. */
    fun startUpdate(u: AppUpdate) {
        if (_updateState.value is UpdateUi.Downloading) return
        _updateState.value = UpdateUi.Downloading(0)
        viewModelScope.launch {
            val r = try {
                downloader.download(u) { p -> _updateState.value = UpdateUi.Downloading((p * 100).toInt().coerceIn(0, 100)) }
            } catch (e: IOException) { UpdateResult.Offline } // includes RpcTimeoutException
            catch (e: RpcException) { UpdateResult.Offline }
            _updateState.value = when (r) {
                is UpdateResult.Ready -> { installer.install(r.file); UpdateUi.Idle }
                UpdateResult.Corrupt -> UpdateUi.Error(R.string.news_update_corrupt)
                UpdateResult.NotOnServer -> UpdateUi.Error(R.string.news_update_not_found)
                UpdateResult.Offline -> UpdateUi.Error(R.string.news_update_offline)
            }
        }
    }

    private val _callWarning = MutableStateFlow(false)
    /** The contact may not hear the call (no push): «Позвонить» / «Отмена» before dialing. */
    val callWarning: StateFlow<Boolean> = _callWarning
    private val _callStarted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** A call was dialed: the app opens the call screen. */
    val callStarted: SharedFlow<Unit> = _callStarted
    /** «Звонки недоступны» etc. from the call manager. */
    val callNotices: SharedFlow<CallNotice> = calls.notices
    private var preparing = false

    /** After the microphone permission: ask `push.status`, warn when the contact has no push, else dial. */
    fun prepareCall() {
        val u = ui.value
        if (!u.canCall || preparing) return
        preparing = true
        viewModelScope.launch {
            try {
                if (calls.state.value != CallState.Idle) { _callStarted.tryEmit(Unit); return@launch } // show the call in progress
                if (calls.peerHasPush(u.peerId)) dial() else _callWarning.value = true
            } finally {
                preparing = false
            }
        }
    }

    fun confirmCall() { if (_callWarning.value) { _callWarning.value = false; dial() } }
    fun dismissCallWarning() { _callWarning.value = false }

    private fun dial() {
        val u = ui.value
        calls.dial(Peer.of(u.peerId, u.title))
        _callStarted.tryEmit(Unit)
    }

    // ---- `@username` mentions (M9.5, D137) ----
    private val _person = MutableStateFlow<FoundPerson?>(null)
    /** The person card of a tapped mention. */
    val person: StateFlow<FoundPerson?> = _person
    private val _lookupFailed = MutableSharedFlow<UsernameLookup.Result>(extraBufferCapacity = 1)
    /** A tapped mention found nobody (or the node refused): a snackbar says why. */
    val lookupFailed: SharedFlow<UsernameLookup.Result> = _lookupFailed
    private var looking = false

    /** A tap on `@name` in a message: the exact lookup, then the card. */
    fun openUsername(mention: String) {
        if (looking) return
        looking = true
        viewModelScope.launch {
            try {
                val (found, r) = people.find(mention)
                if (found != null) _person.value = found else _lookupFailed.tryEmit(r)
            } finally { looking = false }
        }
    }

    fun dismissPerson() { _person.value = null }

    /** «Написать» / «Открыть чат» on the card: [onOpen] gets the direct conversation, unless it is this one. */
    fun writeTo(userId: ByteArray, onOpen: (Long) -> Unit) {
        _person.value = null
        viewModelScope.launch { people.chatWith(userId).takeIf { it != conversationId }?.let(onOpen) }
    }

    fun retry(localId: Long) = viewModelScope.launch { store.retry(localId) }
    fun delete(localId: Long) = viewModelScope.launch { store.deleteLocal(localId) }
    fun markRead() = viewModelScope.launch { store.markConversationRead(conversationId) }

    /** D62: no notification for the chat on screen. */
    fun onVisible(v: Boolean) { active.id = if (v) conversationId else active.id.takeIf { it != conversationId } }

    companion object {
        const val KEY_PENDING = "pending_photo"
        const val KEY_CAMERA = "camera_photo"
        const val ARG_ID = "id"
        const val DRAFT_DEBOUNCE_MS = 500L
    }
}
