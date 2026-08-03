package dev.ide.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.cancel
import dev.ide.ui.generated.resources.color_hue
import dev.ide.ui.generated.resources.color_lightness
import dev.ide.ui.generated.resources.color_picker_title
import dev.ide.ui.generated.resources.color_saturation
import dev.ide.ui.generated.resources.save
import dev.ide.ui.generated.resources.settings_advanced
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource

/**
 * Reusable controls for the Settings screen — the generic renderer composes pages out of these, so a
 * built-in and a plugin-contributed page look identical. They follow the same token recipe as the module
 * config form (rounded surface cards, accent switch, chip segments).
 */

/** A grouped card with an optional uppercase section header (e.g. "APPEARANCE"). */
@Composable
fun SettingsCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.lg))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.lg)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (title != null) {
            Text(title.uppercase(), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

/** A title + optional description column (left side of a setting row). */
@Composable
private fun RowScopeLabel(title: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (description != null) {
            Text(description, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** A boolean setting: label/description on the left, a switch on the right. */
@Composable
fun SettingsToggleRow(title: String, description: String?, value: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RowScopeLabel(title, description, Modifier.weight(1f))
        CaSwitch(value, onToggle)
    }
}

/** An iOS-style accent switch. */
@Composable
fun CaSwitch(on: Boolean, onToggle: (Boolean) -> Unit) {
    val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest, tween(Motion.FAST), label = "switchBg")
    Box(
        Modifier.size(width = 44.dp, height = 26.dp).background(bg, RoundedCornerShape(Ca.radius.pill))
            .clickable(remember { MutableInteractionSource() }, null) { onToggle(!on) }.padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(Ca.radius.pill)))
    }
}

/** A one-of-N choice rendered as a horizontally-scrolling row of accent chips. */
@Composable
fun SettingsChoiceRow(
    title: String,
    description: String?,
    selected: String,
    options: List<Pair<String, String>>, // value to label
    onSelect: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowScopeLabel(title, description)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) -> SettingsChip(label, value == selected) { onSelect(value) } }
        }
    }
}

@Composable
private fun SettingsChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, tween(Motion.FAST), label = "chipBg")
    Box(
        Modifier.background(bg, RoundedCornerShape(Ca.radius.pill)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** An integer setting on a slider, with the current value shown as a chip. Snaps to [step]. */
@Composable
fun SettingsSliderRow(
    title: String,
    description: String?,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    unit: String?,
    onChange: (Int) -> Unit,
) {
    val steps = (((max - min) / step) - 1).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RowScopeLabel(title, description, Modifier.weight(1f))
            Chip("$value${unit?.let { " $it" } ?: ""}", fill = MaterialTheme.colorScheme.primaryContainer, textColor = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = (((raw - min) / step).roundToInt() * step + min).coerceIn(min, max)
                if (snapped != value) onChange(snapped)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                activeTickColor = MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                inactiveTickColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0f),
            ),
        )
    }
}

/** A free-text setting (boxed field below the label). Commits on each change. */
@Composable
fun SettingsTextRow(
    title: String,
    description: String?,
    value: String,
    placeholder: String,
    codeFont: FontFamily,
    onChange: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RowScopeLabel(title, description)
        Box(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = codeFont),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** An action setting: label/description on the left, a button on the right. */
@Composable
fun SettingsActionRow(title: String, description: String?, buttonLabel: String, destructive: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RowScopeLabel(title, description, Modifier.weight(1f))
        val fill = if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.16f) else MaterialTheme.colorScheme.primaryContainer
        val fg = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Box(
            Modifier.background(fill, RoundedCornerShape(Ca.radius.control)).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(buttonLabel, color = fg, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A thin divider between rows in a card. */
@Composable
fun SettingsDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/** A collapsible "Advanced" group inside a card (closed by default). */
@Composable
fun AdvancedGroup(content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, null) { open = !open },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (open) CaIcons.caretDown else CaIcons.caretRight, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
            Text(stringResource(Res.string.settings_advanced), color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
        AnimatedVisibility(open, enter = expandVertically(tween(Motion.FAST)) + fadeIn(), exit = shrinkVertically(tween(Motion.FAST)) + fadeOut()) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
        }
    }
}

/** A sidebar / drill-in list item naming a settings category. */
@Composable
fun SettingsCategoryItem(title: String, icon: ImageVector, selected: Boolean, showChevron: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, tween(Motion.FAST), label = "catBg")
    Row(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(Ca.radius.control))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, modifier = Modifier.weight(1f))
        if (showChevron) Icon(CaIcons.chevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
    }
}

// ---- Color setting + picker -----------------------------------------------------------------------

/** A color setting: label/description on the left, a swatch on the right that opens the picker. */
@Composable
fun SettingsColorRow(title: String, description: String?, value: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RowScopeLabel(title, description, Modifier.weight(1f))
        Box(
            Modifier.size(36.dp)
                .background(Color(value), RoundedCornerShape(Ca.radius.control))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control))
                .clickable(remember { MutableInteractionSource() }, null) { open = true },
        )
    }
    ColorPickerDialog(open, value, onDismiss = { open = false }) { onChange(it); open = false }
}

/** Quick-pick swatches offered above the fine HSL sliders. */
private val PICKER_PRESETS = listOf(
    0xFF8B5CF6L, 0xFF6750A4L, 0xFF1C9BBDL, 0xFF00A8A0L, 0xFF3DDC84L, 0xFF2E7D32L,
    0xFFF9A825L, 0xFFC16A1CL, 0xFFE0533DL, 0xFFD81B60L, 0xFF7F52FFL, 0xFF3A6FE0L,
)

@Composable
internal fun ColorPickerDialog(visible: Boolean, initial: Long, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    CenteredDialog(visible, onDismiss) {
        val start = remember(initial) { colorToHsl(Color(initial)) }
        var h by remember(initial) { mutableStateOf(start[0]) }
        var s by remember(initial) { mutableStateOf(start[1]) }
        var l by remember(initial) { mutableStateOf(start[2]) }
        val current = hslToColor(h, s, l)
        Column(
            Modifier.widthIn(max = 360.dp).padding(horizontal = 24.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Ca.radius.xl))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.xl))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(Res.string.color_picker_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(56.dp).background(current, RoundedCornerShape(Ca.radius.control)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(Ca.radius.control)))
                Text(hexOf(current), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PICKER_PRESETS.forEach { p ->
                    val pc = Color(p)
                    Box(
                        Modifier.size(30.dp).background(pc, CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable(remember { MutableInteractionSource() }, null) {
                                val t = colorToHsl(pc); h = t[0]; s = t[1]; l = t[2]
                            },
                    )
                }
            }
            PickerSlider(stringResource(Res.string.color_hue), h, 0f, 360f) { h = it }
            PickerSlider(stringResource(Res.string.color_saturation), s, 0f, 1f) { s = it }
            PickerSlider(stringResource(Res.string.color_lightness), l, 0f, 1f) { l = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.cancel)) }
                Button(onClick = { onPick(argbLong(current)) }, modifier = Modifier.weight(1f)) { Text(stringResource(Res.string.save)) }
            }
        }
    }
}

@Composable
private fun PickerSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value, onValueChange = onChange, valueRange = min..max,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )
    }
}

// HSL helpers (h in 0..360, s/l in 0..1). Return a float triple as a 3-element array for simple state.
private fun colorToHsl(c: Color): FloatArray {
    val r = c.red; val g = c.green; val b = c.blue
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val d = max - min
    if (d < 1e-5f) return floatArrayOf(0f, 0f, l)
    val s = d / (1f - abs(2f * l - 1f))
    val h = when (max) {
        r -> 60f * (((g - b) / d) % 6f)
        g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return floatArrayOf((h + 360f) % 360f, s, l)
}

private fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = (((h % 360f) + 360f) % 360f) / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f); hp < 2f -> Triple(x, c, 0f); hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c); hp < 5f -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color((r1 + m).coerceIn(0f, 1f), (g1 + m).coerceIn(0f, 1f), (b1 + m).coerceIn(0f, 1f))
}

private fun argbLong(c: Color): Long {
    val r = (c.red * 255f).roundToInt().toLong()
    val g = (c.green * 255f).roundToInt().toLong()
    val b = (c.blue * 255f).roundToInt().toLong()
    return 0xFF000000L or (r shl 16) or (g shl 8) or b
}

private fun hexOf(c: Color): String {
    val digits = "0123456789ABCDEF"
    fun h2(v: Int) = "${digits[(v shr 4) and 0xF]}${digits[v and 0xF]}"
    val r = (c.red * 255f).roundToInt(); val g = (c.green * 255f).roundToInt(); val b = (c.blue * 255f).roundToInt()
    return "#${h2(r)}${h2(g)}${h2(b)}"
}
