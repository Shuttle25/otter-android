package app.ottermsg.feature.onboarding.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.ottermsg.core.ui.theme.Dimens
import app.ottermsg.feature.onboarding.OnboardingUiState
import app.ottermsg.feature.onboarding.R
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.client.android.Intents
import androidx.activity.compose.rememberLauncherForActivityResult

@Composable
fun InviteScreen(state: OnboardingUiState, onLink: (String) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit,
                 title: Int = R.string.onb_invite_title, text: Int = R.string.onb_invite_text) {
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    val scanPrompt = stringResource(R.string.onb_scan_prompt)
    // The scanner activity asks for the camera permission itself, only when the user taps "Scan".
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) { cameraDenied = false; onLink(text); onSubmit() }
        else if (result.originalIntent?.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION) == true) cameraDenied = true
    }
    OnboardingScaffold(
        title = stringResource(title), onBack = onBack,
        bottom = { PrimaryButton(stringResource(R.string.onb_invite_continue), onSubmit, enabled = state.link.isNotBlank()) },
    ) {
        BodyText(stringResource(text))
        OutlinedButton(
            onClick = {
                scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                    .setOrientationLocked(false).setPrompt(scanPrompt))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.minTouch),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
            Text("  " + stringResource(R.string.onb_invite_scan))
        }
        if (cameraDenied) Text(stringResource(R.string.onb_camera_denied), color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.link, onValueChange = onLink,
            label = { Text(stringResource(R.string.onb_invite_paste_label)) },
            isError = state.linkError != null,
            supportingText = state.linkError?.let { { Text(stringResource(it)) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            minLines = 2, maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
