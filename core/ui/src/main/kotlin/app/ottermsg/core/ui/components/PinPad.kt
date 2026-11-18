package app.ottermsg.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.R
import app.ottermsg.core.ui.theme.Dimens

/** Dots for the digits typed so far; TalkBack reads «Введено n из m» (never the digits). */
@Composable
fun PinDots(entered: Int, max: Int, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.ui_pin_entered, entered, max)
    Row(modifier.clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        repeat(max) { i ->
            val c = MaterialTheme.colorScheme.primary
            Box(Modifier.size(14.dp).let { if (i < entered) it.background(c, CircleShape) else it.border(1.5.dp, c, CircleShape) })
        }
    }
}

/** 3×4 PIN pad: 1–9, then «Стереть», 0, «OK». Each key is at least 64 dp. */
@Composable
fun PinPad(onDigit: (Char) -> Unit, onErase: () -> Unit, onOk: () -> Unit, okEnabled: Boolean, enabled: Boolean = true) {
    val key = Modifier.size(width = 88.dp, height = 64.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s8)) {
        for (row in listOf("123", "456", "789")) Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            row.forEach { d -> TextButton(onClick = { onDigit(d) }, enabled = enabled, modifier = key) {
                Text(d.toString(), style = MaterialTheme.typography.headlineMedium)
            } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            IconButton(onClick = onErase, enabled = enabled, modifier = key) {
                Icon(Icons.AutoMirrored.Outlined.Backspace, contentDescription = stringResource(R.string.ui_pin_erase))
            }
            TextButton(onClick = { onDigit('0') }, enabled = enabled, modifier = key) {
                Text("0", style = MaterialTheme.typography.headlineMedium)
            }
            TextButton(onClick = onOk, enabled = enabled && okEnabled, modifier = key) {
                Text(stringResource(R.string.ui_pin_ok), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/**
 * Title, dots, a message line and the pad. «OK» submits from [minLength] digits (the PIN length is not stored, so the
 * pad cannot submit by itself); the typed digits are cleared on every submit. A new [resetKey] also clears them.
 */
@Composable
fun PinEntry(title: String, message: String?, onSubmit: (String) -> Unit, enabled: Boolean = true,
             minLength: Int = 4, maxLength: Int = 8, resetKey: Any? = null) {
    var pin by remember(resetKey) { mutableStateOf("") } // not saveable: digits never go into saved state
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s24))
        PinDots(pin.length, maxLength)
        Spacer(Modifier.height(Dimens.s16))
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center, modifier = Modifier.width(280.dp).semantics { liveRegion = LiveRegionMode.Polite })
        }
        Spacer(Modifier.height(Dimens.s8))
        PinPad(
            onDigit = { if (pin.length < maxLength) pin += it },
            onErase = { pin = pin.dropLast(1) },
            onOk = { val p = pin; pin = ""; onSubmit(p) },
            okEnabled = pin.length >= minLength, enabled = enabled,
        )
    }
}
