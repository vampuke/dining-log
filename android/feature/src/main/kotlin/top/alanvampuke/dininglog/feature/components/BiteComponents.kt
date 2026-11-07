package top.alanvampuke.dininglog.feature.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.alanvampuke.dininglog.feature.theme.*

// Draw the solid offset first. No blurred elevation shadows anywhere in the skin.
// Ink & Paper drops the offset shadow and the stroke entirely: a flat fill
// with square corners. QuietGrid keeps the exact current rendering.
@Composable
fun Modifier.brutalBox(fill: Color, stroke: Color, shadow: Color, radius: Dp = 14.dp,
    offset: Dp = 4.dp, width: Dp = 2.5.dp): Modifier {
    if (LocalThemeStyle.current.isInkPaper) return drawBehind { drawRect(fill, size = size) }
    return drawBehind {
        val r = radius.toPx(); val d = offset.toPx(); val w = width.toPx()
        drawRoundRect(shadow, topLeft = Offset(d, d), size = size, cornerRadius = CornerRadius(r))
        drawRoundRect(fill, size = size, cornerRadius = CornerRadius(r))
        drawRoundRect(stroke, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius((r - w / 2).coerceAtLeast(0f)), style = Stroke(w))
    }
}
fun Modifier.paperDots(color: Color, spacing: Dp = 16.dp): Modifier = drawBehind {
    val step = spacing.toPx()
    var x = step / 2
    while (x < size.width) {
        var y = step / 2
        while (y < size.height) { drawCircle(color, 1.dp.toPx(), Offset(x, y)); y += step }
        x += step
    }
}
fun Modifier.revisionRail(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(3.dp.toPx(), 0f), Offset(3.dp.toPx(), size.height), 2.dp.toPx())
    drawCircle(color, 4.dp.toPx(), Offset(3.dp.toPx(), 20.dp.toPx()))
}
@Composable
fun Modifier.revisedStamp(color: Color): Modifier {
    if (LocalThemeStyle.current.isInkPaper) return this
    return rotate(-6f).drawBehind {
        val w = 1.5.dp.toPx()
        drawRoundRect(color, topLeft = Offset(w, w), size = Size(size.width - 2 * w, size.height - 2 * w),
            cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx()))))
    }
}
@Composable fun BitePanel(modifier: Modifier = Modifier, fill: Color = LocalQuietGridColors.current.surface,
    content: @Composable ColumnScope.() -> Unit) {
    val c = LocalQuietGridColors.current
    Column(modifier.fillMaxWidth().brutalBox(fill, c.border, c.shadow).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}
@Composable fun Sticker(text: String, fill: Color, modifier: Modifier = Modifier, tilt: Float = 0f, dieCut: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
    val c = LocalQuietGridColors.current
    // Ink & Paper has no sticker chips: plain small secondary text instead.
    if (LocalThemeStyle.current.isInkPaper) {
        GridText(text, modifier, style = MaterialTheme.typography.labelSmall, color = c.secondary, maxLines = maxLines)
        return
    }
    val outer = if (dieCut) Modifier.drawBehind {
        drawRoundRect(c.dieCut, topLeft = Offset(-3.dp.toPx(), -3.dp.toPx()),
            size = Size(size.width + 6.dp.toPx(), size.height + 6.dp.toPx()), cornerRadius = CornerRadius(11.dp.toPx()))
    } else Modifier
    GridText(text, modifier.rotate(tilt).then(outer).brutalBox(fill, c.stickerInk, c.shadow, 8.dp,
        offset = if (dieCut) 3.dp else 0.dp, width = 1.5.dp).padding(horizontal = 7.dp, vertical = 3.dp)
        .semantics { this[BackgroundArgb] = fill.toArgb() },
        style = MaterialTheme.typography.labelSmall, color = c.stickerInk, maxLines = maxLines)
}
// Unrated is a neutral paper state. These labels are presentation of existing tenths.
fun verdictLabel(tenths: Int?, dish: Boolean = false): String = when {
    tenths == null -> "未评分"
    tenths >= 46 -> if (dish) "惊艳" else "封神必吃"
    tenths >= 40 -> if (dish) "推荐" else "值得二刷"
    tenths >= 32 -> if (dish) "一般" else "普通便餐"
    else -> if (dish) "踩雷" else "踩雷预警"
}
@Composable fun verdictFill(tenths: Int?): Color {
    val c = LocalQuietGridColors.current
    // Ink & Paper stops banding scores by colour: persimmon at 4.0/5.0, ink below.
    if (LocalThemeStyle.current.isInkPaper) return when { tenths == null -> c.paper; tenths >= 40 -> c.accent; else -> c.ink }
    return when { tenths == null -> c.paper; tenths >= 46 -> c.butter; tenths >= 40 -> c.mint; tenths >= 32 -> c.peach; else -> c.popRed }
}
@Composable fun VerdictSticker(value: String?, dish: Boolean = false) {
    val tenths = value?.toIntOrNull()
    val c = LocalQuietGridColors.current
    if (tenths == null) GridText("未评分", style = MaterialTheme.typography.labelSmall, color = c.secondary)
    else if (LocalThemeStyle.current.isInkPaper) GridText(verdictLabel(tenths, dish), style = MaterialTheme.typography.labelSmall,
        color = if (tenths >= 40) c.accent else c.secondary)
    else Sticker(verdictLabel(tenths, dish), verdictFill(tenths))
}
@Composable fun ReceiptEdge(top: Boolean) {
    val c = LocalQuietGridColors.current
    // Ink & Paper keeps the ticket idea but swaps the zigzag edge for a dashed rule.
    if (LocalThemeStyle.current.isInkPaper) {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawLine(c.border, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        }
        return
    }
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val step = 16.dp.toPx(); val depth = 8.dp.toPx()
        val path = Path().apply {
            moveTo(0f, if (top) size.height else 0f)
            var x = 0f
            while (x < size.width) {
                lineTo(x, if (top) depth else size.height - depth)
                lineTo(x + step / 2, if (top) 0f else size.height)
                lineTo(x + step, if (top) depth else size.height - depth)
                x += step
            }
            lineTo(size.width, if (top) size.height else 0f); close()
        }
        drawPath(path, c.surface)
        drawPath(path, c.border, style = Stroke(1.5.dp.toPx()))
    }
}
@Composable fun ReceiptBarcode() {
    val c = LocalQuietGridColors.current
    Canvas(Modifier.fillMaxWidth().height(20.dp)) {
        var x = 4.dp.toPx(); var index = 0
        while (x < size.width - 4.dp.toPx()) {
            val width = (if (index % 3 == 0) 3 else 1).dp.toPx()
            drawRect(c.ink, Offset(x, 0f), Size(width, size.height)); x += width + 3.dp.toPx(); index++
        }
    }
}
@Composable fun BiteButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: ButtonColors? = null, contentPadding: PaddingValues = ButtonDefaults.ContentPadding, content: @Composable RowScope.() -> Unit) {
    val c = LocalQuietGridColors.current
    // Ink & Paper: a flat persimmon capsule, no border, no press shift.
    if (LocalThemeStyle.current.isInkPaper) {
        Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, contentPadding = contentPadding,
            shape = RoundedCornerShape(50), elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
            colors = colors?.copy(contentColor = c.onAccent, disabledContainerColor = c.paper, disabledContentColor = c.secondary) ?: ButtonDefaults.buttonColors(containerColor = c.cta, contentColor = c.onAccent,
                disabledContainerColor = c.paper, disabledContentColor = c.secondary), content = content)
        return
    }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val animate = rememberNavigationAnimationsEnabled()
    val shift by animateFloatAsState(if (pressed) 2f else 0f,
        tween(if (animate) 120 else 0, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)), label = "sticker-press")
    Button(onClick, modifier.heightIn(min = 48.dp).graphicsLayer { translationX = shift.dp.toPx(); translationY = shift.dp.toPx() }
        .brutalBox(if (enabled) colors?.containerColor ?: c.cta else c.paper, c.border, c.shadow, 12.dp, offset = (4f - 1.5f * shift).dp),
        enabled = enabled, contentPadding = contentPadding, shape = RoundedCornerShape(12.dp), border = BorderStroke(2.5.dp, c.border),
        interactionSource = source, elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        colors = colors?.copy(contentColor = c.onAccent, disabledContainerColor = c.paper, disabledContentColor = c.secondary) ?: ButtonDefaults.buttonColors(containerColor = c.cta, contentColor = c.onAccent,
            disabledContainerColor = c.paper, disabledContentColor = c.secondary), content = content)
}
@Composable fun BiteTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val c = LocalQuietGridColors.current
    // Ink & Paper: a plain text link, no bordered chip.
    if (LocalThemeStyle.current.isInkPaper) {
        TextButton(onClick, modifier.heightIn(min = 48.dp), enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = c.accentInk, disabledContentColor = c.secondary), content = content)
        return
    }
    TextButton(onClick, modifier.heightIn(min = 48.dp).border(1.5.dp, c.border, RoundedCornerShape(10.dp)), enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = c.accentInk, disabledContentColor = c.secondary), content = content)
}
// Slot foreground is explicit so GridText and Material Text share label/placeholder roles.
val LocalFieldForeground = staticCompositionLocalOf<Color?> { null }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun BiteTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: (@Composable () -> Unit)? = null, accessibleLabel: String? = null,
    placeholder: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = QuietGridText.input, singleLine: Boolean = false, minLines: Int = 1,
    isError: Boolean = false, supportingText: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, leadingIcon: (@Composable () -> Unit)? = null) {
    val c = LocalQuietGridColors.current
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val labelColor = when { isError -> c.danger; !enabled -> c.secondary; focused -> c.ink; else -> c.secondary }
    val supportingSlot: (@Composable () -> Unit)? = supportingText?.let { content ->
        { Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) { content() } }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink,
        disabledTextColor = c.secondary, errorTextColor = c.ink, focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface, disabledContainerColor = c.surface, errorContainerColor = c.surface,
        focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent,
        focusedLabelColor = c.ink, unfocusedLabelColor = c.secondary, disabledLabelColor = c.secondary,
        errorLabelColor = c.danger, cursorColor = c.ink, errorCursorColor = c.danger,
        focusedPlaceholderColor = c.tertiary, unfocusedPlaceholderColor = c.tertiary,
        disabledPlaceholderColor = c.tertiary, errorPlaceholderColor = c.tertiary,
        focusedLeadingIconColor = c.secondary, unfocusedLeadingIconColor = c.secondary,
        disabledLeadingIconColor = c.secondary, errorLeadingIconColor = c.danger,
        focusedSupportingTextColor = c.secondary, unfocusedSupportingTextColor = c.secondary,
        disabledSupportingTextColor = c.secondary, errorSupportingTextColor = c.danger)
    val fieldModifier = modifier.fillMaxWidth().semantics { if (accessibleLabel != null) contentDescription = accessibleLabel }
    // Always keep the label above the box. Material's collapsed empty label hides placeholders.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Box(Modifier.fillMaxWidth().clearAndSetSemantics {}, propagateMinConstraints = true) {
            CompositionLocalProvider(LocalContentColor provides labelColor, LocalFieldForeground provides labelColor) { label() }
        }
        if (LocalThemeStyle.current.isInkPaper) {
            val ruleColor = if (isError) c.danger else if (focused) c.ink else c.border
            val textColor = textStyle.color.takeOrElse {
                when { !enabled -> fieldColors.disabledTextColor; isError -> fieldColors.errorTextColor;
                    focused -> fieldColors.focusedTextColor; else -> fieldColors.unfocusedTextColor }
            }
            val defaultError = stringResource(androidx.compose.ui.R.string.default_error_message)
            // Match the pinned Material wrapper; only its container painting changes.
            CompositionLocalProvider(LocalTextSelectionColors provides fieldColors.textSelectionColors) {
                BasicTextField(value, onValueChange,
                    fieldModifier.then(if (isError) Modifier.semantics { error(defaultError) } else Modifier)
                        .defaultMinSize(OutlinedTextFieldDefaults.MinWidth, OutlinedTextFieldDefaults.MinHeight),
                    enabled = enabled, interactionSource = source, textStyle = textStyle.merge(TextStyle(color = textColor)),
                    cursorBrush = SolidColor(if (isError) fieldColors.errorCursorColor else fieldColors.cursorColor),
                    singleLine = singleLine, minLines = minLines, visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions,
                    decorationBox = { innerTextField ->
                        OutlinedTextFieldDefaults.DecorationBox(value, innerTextField, enabled, singleLine,
                            visualTransformation, source, isError = isError, supportingText = supportingSlot,
                            leadingIcon = leadingIcon, colors = fieldColors,
                            placeholder = {
                                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                                    CompositionLocalProvider(LocalContentColor provides c.tertiary, LocalFieldForeground provides c.tertiary) {
                                        if (placeholder != null) placeholder() else Text("", style = textStyle)
                                    }
                                }
                            },
                            container = {
                                // Material measures this box without support/error text.
                                OutlinedTextFieldDefaults.Container(enabled, isError, source,
                                    modifier = Modifier.drawWithContent {
                                        drawContent()
                                        val y = size.height - 0.5.dp.toPx()
                                        drawLine(ruleColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                                    }, colors = fieldColors, shape = RoundedCornerShape(10.dp))
                            })
                    })
            }
        } else OutlinedTextField(value, onValueChange,
            fieldModifier.border(2.dp, if (isError) c.danger else c.border, RoundedCornerShape(10.dp)),
            enabled = enabled, interactionSource = source, textStyle = textStyle, singleLine = singleLine, minLines = minLines,
            placeholder = {
                Box(Modifier.fillMaxWidth(), propagateMinConstraints = true) {
                    CompositionLocalProvider(LocalContentColor provides c.tertiary, LocalFieldForeground provides c.tertiary) {
                        // No generic fallback: a bare "请输入" repeats the
                        // label above the box and teaches nothing. Call sites
                        // pass a real example, or nothing at all.
                        if (placeholder != null) placeholder() else Text("", style = textStyle)
                    }
                }
            },
            isError = isError, supportingText = supportingSlot, visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions, leadingIcon = leadingIcon, shape = RoundedCornerShape(10.dp),
            colors = fieldColors)
    }
}
