package app.ottermsg.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ottermsg.core.ui.components.OtterTopBar
import app.ottermsg.core.ui.theme.Dimens

/** Edit name: one field, counter in code points, Save; back without saving discards. */
@Composable
fun EditNameRoute(onBack: () -> Unit, vm: EditNameViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { OtterTopBar(stringResource(R.string.edit_name_title), onBack = onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenGutter, vertical = Dimens.s16)) {
            OutlinedTextField(
                value = s.text, onValueChange = vm::onText, singleLine = true,
                label = { Text(stringResource(R.string.edit_name_label)) },
                isError = s.error != null,
                supportingText = {
                    Row(Modifier.fillMaxWidth()) {
                        Text(when (s.error) {
                            EditNameError.Empty -> stringResource(R.string.edit_name_empty)
                            EditNameError.TooLong -> stringResource(R.string.edit_name_too_long)
                            null -> stringResource(R.string.edit_name_hint)
                        }, Modifier.weight(1f))
                        Text(s.counter)
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.save(onBack) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(Dimens.s16))
            Button(onClick = { vm.save(onBack) }, enabled = s.canSave,
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch)) {
                Text(stringResource(R.string.edit_name_save))
            }
        }
    }
}
