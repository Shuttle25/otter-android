package app.ottermsg.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.directory.ContactRepository
import app.ottermsg.core.data.messaging.MessageRepository
import app.ottermsg.core.database.entity.ContactEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NewChatViewModel @Inject constructor(contacts: ContactRepository, private val messages: MessageRepository) : ViewModel() {
    val contacts: StateFlow<List<ContactEntity>?> = contacts.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun open(userId: ByteArray, onOpened: (Long) -> Unit) {
        viewModelScope.launch { onOpened(messages.conversationWith(userId)) }
    }
}
