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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
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
import dev.ide.ui.generated.resources.settings_advanced
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import dev.ide.ui.theme.Motion
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
