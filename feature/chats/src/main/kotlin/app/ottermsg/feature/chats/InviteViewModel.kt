package app.ottermsg.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.invite.InviteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

sealed interface InviteUiState {
    data object Loading : InviteUiState
    data class Ready(val link: String) : InviteUiState
    data object Error : InviteUiState
}

@HiltViewModel
class InviteViewModel @Inject constructor(private val invites: InviteRepository) : ViewModel() {
    private val _state = MutableStateFlow<InviteUiState>(InviteUiState.Loading)
    val state: StateFlow<InviteUiState> = _state

    init { create() }

    fun create() {
        _state.value = InviteUiState.Loading
        viewModelScope.launch {
            _state.value = try { InviteUiState.Ready(invites.create().link) } catch (e: CancellationException) { throw e } catch (e: Exception) { InviteUiState.Error }
        }
    }
}
