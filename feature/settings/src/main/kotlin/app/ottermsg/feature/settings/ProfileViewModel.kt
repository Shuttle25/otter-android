package app.ottermsg.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ottermsg.core.data.account.ProfileError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class EditNameError { Empty, TooLong }

const val MAX_NAME = 64

data class EditNameState(val text: String, val original: String, val error: EditNameError?, val saving: Boolean = false) {
    private val trimmed get() = text.trim()
    /** Code points, as the node counts them. */
    val counter: String get() = "${trimmed.codePointCount(0, trimmed.length)} / $MAX_NAME"
    val canSave: Boolean get() = error == null && !saving && trimmed != original
}

@HiltViewModel
class ProfileViewModel @Inject constructor(store: ProfileStore) : ViewModel() {
    val profile = store.profile
    val pending = store.pending
}

@HiltViewModel
class EditNameViewModel @Inject constructor(private val store: ProfileStore) : ViewModel() {
    private val original = store.profile.value?.displayName.orEmpty()
    private val _state = MutableStateFlow(EditNameState(original, original, null))
    val state: StateFlow<EditNameState> = _state

    fun onText(t: String) { _state.value = _state.value.copy(text = t, error = validate(t)) }

    /** Saves and calls [onDone] (back to Profile); does nothing when unchanged or invalid. */
    fun save(onDone: () -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        _state.value = s.copy(saving = true)
        viewModelScope.launch {
            store.setDisplayName(s.text).fold(
                onSuccess = { onDone() },
                onFailure = { e ->
                    _state.value = _state.value.copy(saving = false,
                        error = if (e == ProfileError.TooLong) EditNameError.TooLong else EditNameError.Empty)
                })
        }
    }

    private fun validate(t: String): EditNameError? {
        val n = t.trim()
        return when {
            n.isEmpty() -> EditNameError.Empty
            n.codePointCount(0, n.length) > MAX_NAME -> EditNameError.TooLong
            else -> null
        }
    }
}
