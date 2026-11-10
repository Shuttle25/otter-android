package app.ottermsg.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun s(size: Int, line: Int, w: Int) = TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(w))

/** Guide §4. Mapping: titleLarge=screen title, headlineSmall=profile name, bodyLarge=message/input,
 *  titleMedium=settings row title, labelLarge(600)=sender name & buttons, bodyMedium=caption, bodySmall=time/status,
 *  titleSmall=section header. */
val OtterTypography = Typography(
    titleLarge = s(22, 28, 600),
    headlineSmall = s(24, 32, 600),
    bodyLarge = s(16, 24, 400),
    titleMedium = s(16, 24, 500),
    labelLarge = s(14, 20, 600),
    bodyMedium = s(14, 20, 400),
    bodySmall = s(12, 16, 400),
    titleSmall = s(14, 20, 600),
)
