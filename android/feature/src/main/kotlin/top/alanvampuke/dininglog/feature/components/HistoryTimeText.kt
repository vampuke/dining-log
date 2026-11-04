package top.alanvampuke.dininglog.feature.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.alanvampuke.dininglog.core.formatAbsoluteTime
import top.alanvampuke.dininglog.core.relativeTime
import top.alanvampuke.dininglog.feature.theme.LocalQuietGridColors
import top.alanvampuke.dininglog.feature.theme.QuietGridText

@Composable fun HistoryTimeText(iso: String, absolute: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    // A nested clickable consumes the time action independently of its card.
    // Large fonts may wrap the full timestamp inside the minimum 48dp target.
    val large = LocalDensity.current.fontScale > 1.3f
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 48.dp)
        .clickable(role = Role.Button,
            onClickLabel = if (absolute) "切换为相对时间" else "切换为绝对时间", onClick = onToggle)
        .semantics { stateDescription = if (absolute) "绝对时间" else "相对时间" },
        contentAlignment = Alignment.CenterStart) {
        GridText(if (absolute) formatAbsoluteTime(iso) else relativeTime(iso), textModifier,
            style = QuietGridText.date, color = LocalQuietGridColors.current.secondary,
            maxLines = if (large) Int.MAX_VALUE else 1, overflow = if (large) TextOverflow.Clip else TextOverflow.Ellipsis)
    }
}
