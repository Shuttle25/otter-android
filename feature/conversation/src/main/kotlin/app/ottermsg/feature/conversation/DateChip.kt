package app.ottermsg.feature.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.ottermsg.core.ui.theme.Dimens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DateChip(date: LocalDate, today: LocalDate = LocalDate.now()) {
    val text = when (date) {
        today -> stringResource(R.string.conv_today)
        today.minusDays(1) -> stringResource(R.string.conv_yesterday)
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMMM" else "d MMMM yyyy", Locale.getDefault()))
    }
    Box(Modifier.fillMaxWidth().padding(vertical = Dimens.s12), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.s12, vertical = Dimens.s4).semantics { heading() })
        }
    }
}
