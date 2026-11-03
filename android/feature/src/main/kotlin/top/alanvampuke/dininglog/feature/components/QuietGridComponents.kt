package top.alanvampuke.dininglog.feature.components

import android.graphics.Rect
import android.os.Build
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import top.alanvampuke.dininglog.core.*
import top.alanvampuke.dininglog.feature.theme.*

val BackgroundArgb = SemanticsPropertyKey<Int>("QuietGridBackgroundArgb")
val ForegroundArgb = SemanticsPropertyKey<Int>("QuietGridForegroundArgb")
val ImeBottomPx = SemanticsPropertyKey<Int>("ImeBottomPx")
@Composable fun GridText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = LocalFieldForeground.current ?: LocalQuietGridColors.current.ink, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip) {
    Text(text, modifier.semantics { this[ForegroundArgb] = color.toArgb() }, color = color, style = style, maxLines = maxLines, overflow = overflow)
}
@Composable fun ScreenFrame(tag: String, bottomBar: @Composable () -> Unit = {}, floatingBottomBar: Boolean = false, content: @Composable (PaddingValues) -> Unit) {
    val c = LocalQuietGridColors.current
    val density = LocalDensity.current
    val view = LocalView.current
    var legacyIme by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        // Before API 30, edge-to-edge can report zero IME insets while the IME
        // covers the decor. Measure that actual occlusion instead of guessing a
        // keyboard height. System bars alone never count as a keyboard.
        val root = view.rootView
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            if (Build.VERSION.SDK_INT < 30) {
                val visible = Rect(); root.getWindowVisibleDisplayFrame(visible)
                val location = IntArray(2); root.getLocationOnScreen(location)
                val obscured = (location[1] + root.height - visible.bottom).coerceAtLeast(0)
                val bars = ViewCompat.getRootWindowInsets(root)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
                legacyIme = if (obscured > bars) obscured else 0
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    var barHeight by remember { mutableStateOf(64.dp) }
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val reportedIme = WindowInsets.ime.getBottom(density)
    val ime = maxOf(reportedIme, legacyIme)
    val extra = PaddingValues(bottom = with(density) { (ime - reportedIme).toDp() })
    Scaffold(Modifier.fillMaxSize().imePadding().padding(extra).consumeWindowInsets(extra)
        .testTag(tag).semantics { this[BackgroundArgb] = c.bg.toArgb(); this[ImeBottomPx] = ime },
        containerColor = c.bg, contentColor = c.ink,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = { if (!floatingBottomBar) bottomBar() }) { padding ->
        Box(Modifier.fillMaxSize().paperDots(c.dots)) {
            val showCapsule = floatingBottomBar && ime == 0
            val contentPadding = if (floatingBottomBar) PaddingValues(
                start = padding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = padding.calculateTopPadding(), end = padding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                bottom = if (showCapsule) barHeight + navigationBottom + 16.dp + 4.dp else navigationBottom) else padding
            content(contentPadding)
            // Hide while typing: leaves the entire resized viewport available to inputs.
            if (showCapsule) Box(Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(start = 20.dp, end = 20.dp, bottom = navigationBottom + 16.dp)
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } }) { bottomBar() }
        }
    }
}
// The list keeps its full viewport; bottom clearance belongs to scrollable content.
fun listViewportPadding(padding: PaddingValues) = PaddingValues(
    start = padding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    top = padding.calculateTopPadding(), end = padding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr))
fun listContentPadding(padding: PaddingValues) = PaddingValues(
    start = QuietGridDimensions.edge, top = QuietGridDimensions.edge, end = QuietGridDimensions.edge,
    bottom = QuietGridDimensions.edge + padding.calculateBottomPadding())
@Composable fun PageHeader(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().brutalBox(LocalQuietGridColors.current.paper, LocalQuietGridColors.current.border, LocalQuietGridColors.current.shadow).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onBack != null) BiteTextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text("‹ 返回") }
            GridText(title, Modifier.weight(1f).testTag("page-title"), style = MaterialTheme.typography.headlineSmall)
        }
        if (subtitle != null) GridText(subtitle, color = LocalQuietGridColors.current.secondary)
    }
}
@Composable fun ErrorMessage(message: String, tag: String = "error") {
    GridText(message, Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite }, color = LocalQuietGridColors.current.danger)
}
@Composable fun CacheStatus(offline: Boolean, fetchedAt: Long?, stale: Boolean = false, localSearch: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (offline) GridText("离线缓存 · 联网后可手动刷新", Modifier.testTag("offline"), color = LocalQuietGridColors.current.accentInk)
        if (localSearch) GridText("只搜索已缓存内容；未缓存的内容未参与搜索", Modifier.testTag("cache-scope"), color = LocalQuietGridColors.current.secondary)
        if (fetchedAt != null && (offline || stale)) GridText("上次更新 ${formatAbsoluteTimeMillis(fetchedAt)}", style = QuietGridText.date, color = LocalQuietGridColors.current.secondary)
        if (stale) GridText("此详情已有较新摘要，正在等待刷新", Modifier.testTag("stale-detail"), color = LocalQuietGridColors.current.secondary)
    }
}
@Composable fun Score(value: String?, prominent: Boolean = false, meter: Boolean = false, modifier: Modifier = Modifier) {
    val c = LocalQuietGridColors.current
    val tenths = value?.toIntOrNull()
    val fill = verdictFill(tenths)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridText(displayValue("rating_tenths", value), Modifier.rotate(-3f)
            .drawBehind {
                drawRoundRect(c.dieCut, topLeft = Offset(-3.dp.toPx(), -3.dp.toPx()),
                    size = Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()))
            }.brutalBox(fill, c.border, c.shadow, 10.dp, 3.dp).padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { this[BackgroundArgb] = fill.toArgb() },
            style = if (prominent) QuietGridText.detailRating else QuietGridText.listRating,
            color = if (tenths == null) c.ink else c.stickerInk)
        if (meter && value != null) {
            val filled = (value.toInt() / 5.0).roundToInt()
            Row(Modifier.width(120.dp).semantics { contentDescription = "评分 ${Rating.fromTenths(value.toInt()).display}，满分 5.0" }, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(10) { index -> Box(Modifier.weight(1f).height(5.dp).background(if (index < filled) c.accent else c.borderStrong)) }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RatingEditor(value: String?, onChange: (String?) -> Unit, enabled: Boolean, tag: String = "rating") {
    Column(Modifier.fillMaxWidth().brutalBox(LocalQuietGridColors.current.paper, LocalQuietGridColors.current.border, LocalQuietGridColors.current.shadow).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GridText("评分 · 选填", style = MaterialTheme.typography.titleMedium)
        Score(value, meter = true)
        // Unrated must not draw as 4.0, and it must not borrow a valid score either.
        // Previously null mapped to (null ?: 40) / 10, so an unrated record showed a
        // mid-track fill while its label said 未评分. Mapping null to the 1f endpoint
        // fixed the picture but kept 无评分 encoded as the legal score 1.0, which made
        // a touch on that endpoint a no-op while an accessibility SetProgress(1f)
        // still wrote "10". So render a non-interactive empty track until a score
        // exists; 设置评分 below is the only explicit way in.
        if (value == null) {
            // Canvas draws in DrawScope, which is not composable, so the theme lookup
            // has to be hoisted out of the lambda.
            val c = LocalQuietGridColors.current
            Canvas(Modifier.fillMaxWidth().height(14.dp).testTag(tag).semantics {
                contentDescription = "未评分"
                if (enabled) setProgress { selected ->
                    onChange((selected * 10).roundToInt().coerceIn(10, 50).toString()); true
                }
            }) {
                drawRoundRect(c.bg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                drawRoundRect(c.border, topLeft = Offset(1.25.dp.toPx(), 1.25.dp.toPx()),
                    size = Size(size.width - 2.5.dp.toPx(), size.height - 2.5.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()), style = Stroke(2.5.dp.toPx()))
            }
        } else Slider(value = (value.toIntOrNull() ?: 40) / 10f, onValueChange = { onChange((it * 10).roundToInt().coerceIn(10, 50).toString()) },
            enabled = enabled, valueRange = 1f..5f, steps = 39, modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
                contentDescription = "评分，0.1 步进"
                if (enabled) setProgress { selected ->
                    onChange((selected * 10).roundToInt().coerceIn(10, 50).toString())
                    true
                }
            }, thumb = {
                Box(Modifier.size(28.dp).brutalBox(LocalQuietGridColors.current.popRed,
                    LocalQuietGridColors.current.border, LocalQuietGridColors.current.shadow, 6.dp, 2.dp))
            }, track = { slider ->
                val c = LocalQuietGridColors.current
                Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                    drawRoundRect(c.bg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                    val fraction = (slider.value - 1f) / 4f
                    drawRoundRect(c.butter, size = Size(size.width * fraction, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
                    drawRoundRect(c.border, topLeft = Offset(1.25.dp.toPx(), 1.25.dp.toPx()),
                        size = Size(size.width - 2.5.dp.toPx(), size.height - 2.5.dp.toPx()),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()), style = Stroke(2.5.dp.toPx()))
                }
            })
        // The steppers previously defaulted from 40 as well, so they implied a rating
        // existed before one was set. Hide them until a rating exists; "设置评分" below
        // sets an explicit 4.0 and is the only entry point for an unrated record.
        if (value != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BiteTextButton(onClick = { onChange(((value.toInt()) - 1).coerceAtLeast(10).toString()) }, enabled = enabled, modifier = Modifier.testTag("$tag:minus")) { Text("− 0.1") }
            BiteTextButton(onClick = { onChange(((value.toInt()) + 1).coerceAtMost(50).toString()) }, enabled = enabled, modifier = Modifier.testTag("$tag:plus")) { Text("+ 0.1") }
        }
        BiteTextButton(onClick = { onChange(if (value == null) "40" else null) }, enabled = enabled) { Text(if (value == null) "设置评分" else "清除评分") }
    }
}
fun fieldPlaceholder(field: String): String = when (field) {
    "name" -> "例如：兰巴赫"
    "cuisine", "filter-cuisine" -> "例如：川菜"
    "dining_date", "first-visit-date", "filter-from", "filter-to" -> "例如：2026-11-02"
    // Was "选填" for all of these, duplicating the "· 选填" already in the
    // label. Give each a real example instead; optionality stays in the label.
    "address" -> "例如：中山路 123 号"
    "review" -> "例如：花椒香，鸡块酥脆"
    "note", "first-visit-note" -> "例如：下次带朋友再来"
    else -> "必填"
}
@Composable fun FormField(field: String, value: String?, onChange: (String) -> Unit, enabled: Boolean = true,
    error: String? = null, label: String = fieldLabel(field), minLines: Int = 1, supporting: String? = null, placeholder: String = fieldPlaceholder(field)) {
    BiteTextField(value ?: "", onChange, modifier = Modifier.fillMaxWidth().testTag("field:$field"), enabled = enabled,
        label = { GridText(label) }, accessibleLabel = label, placeholder = { GridText(placeholder, style = QuietGridText.input) }, textStyle = QuietGridText.input, minLines = minLines, isError = error != null,
        supportingText = { if (error != null) GridText(error, color = LocalQuietGridColors.current.danger) else if (supporting != null) GridText(supporting, style = MaterialTheme.typography.bodySmall, color = LocalQuietGridColors.current.secondary) })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ChoiceChips(options: List<Pair<String?, String>>, selected: String?, onSelect: (String?) -> Unit, enabled: Boolean = true) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (code, label) -> FilterChip(selected = selected == code, onClick = { onSelect(code) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), label = { Text(label) },
            border = FilterChipDefaults.filterChipBorder(enabled, selected == code, borderColor = LocalQuietGridColors.current.border,
                selectedBorderColor = LocalQuietGridColors.current.border, borderWidth = 1.5.dp, selectedBorderWidth = 2.dp),
            colors = FilterChipDefaults.filterChipColors(containerColor = LocalQuietGridColors.current.surface,
                labelColor = LocalQuietGridColors.current.ink, selectedContainerColor = LocalQuietGridColors.current.accentSoft,
                selectedLabelColor = LocalQuietGridColors.current.accentInk, disabledContainerColor = LocalQuietGridColors.current.paper,
                disabledLabelColor = LocalQuietGridColors.current.secondary, disabledSelectedContainerColor = LocalQuietGridColors.current.paper)) }
    }
}
@Composable fun SaveBar(enabled: Boolean, saving: Boolean, onSave: () -> Unit, message: String? = null) {
    Surface(color = LocalQuietGridColors.current.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(QuietGridDimensions.edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message != null) Column(Modifier.heightIn(max = 112.dp).verticalScroll(rememberScrollState())) {
                ErrorMessage(message, "save-message")
            }
            BiteButton(onClick = onSave, enabled = enabled && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save"),
                colors = ButtonDefaults.buttonColors(containerColor = LocalQuietGridColors.current.cta, contentColor = LocalQuietGridColors.current.onAccent)) {
                Text(if (saving) "正在保存…" else "保存")
            }
        }
    }
}
@Composable fun ExpandableValue(field: String, value: String?, tag: String) {
    var expanded by remember(value) { mutableStateOf(false) }
    val text = displayValue(field, value)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GridText(if (!expanded && text.length > 240) text.take(240) + "…" else text, Modifier.testTag(tag), style = QuietGridText.paragraph)
        if (text.length > 240) BiteTextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("expand:$tag")) { Text(if (expanded) "收起全文" else "展开全文") }
    }
}
@Composable fun ConfirmAction(title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { GridText(title, style = MaterialTheme.typography.headlineSmall) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { GridText(message) } }, containerColor = LocalQuietGridColors.current.surface,
        confirmButton = { BiteTextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm")) { Text(confirmText, color = LocalQuietGridColors.current.danger) } },
        dismissButton = { BiteTextButton(onClick = onDismiss) { Text("取消") } })
}
