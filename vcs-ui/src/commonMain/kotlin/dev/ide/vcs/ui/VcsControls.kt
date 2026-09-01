package dev.ide.vcs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.components.CaDropdownMenu
import dev.ide.ui.components.IconButtonCa
import dev.ide.ui.icons.CaIcons

/**
 * The controls the version-control surfaces are built from.
 *
 * Git's vocabulary is unforgiving, so nothing here is an unlabelled glyph: every icon-only control carries a
 * tooltip, and anything whose meaning is not obvious from its shape is a menu row with words instead. What is
 * left as an icon is only the handful of actions used constantly (stage, unstage, pull, push), where a label
 * on every row would crowd out the file names.
 */

/** Wrap [content] with a hover (desktop) / long-press (touch) tooltip showing [text]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WithTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text, style = MaterialTheme.typography.labelSmall) } },
        state = rememberTooltipState(),
    ) { content() }
}

/** An icon button that always says what it does: [label] is both its tooltip and its description. */
@Composable
internal fun VcsIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    iconSize: Int = 17,
    boxSize: Int = 32,
    tint: Color? = null,
) {
    WithTooltip(label) {
        IconButtonCa(icon, label, onClick, modifier, active = active, iconSize = iconSize, boxSize = boxSize, tint = tint)
    }
}

/**
 * A small button that shows its name next to its glyph, with an optional trailing [count]. Used for the
 * actions a newcomer most needs named: pull and push.
 */
@Composable
internal fun VcsLabelledButton(
    icon: ImageVector,
    label: String,
    tooltip: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 0,
    emphasised: Boolean = false,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val content = when {
        !enabled -> scheme.outline
        emphasised -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }
    WithTooltip(tooltip) {
        Row(
            modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (emphasised) scheme.secondaryContainer else scheme.surfaceContainerHigh)
                .clickable(enabled = enabled, onClick = onClick)
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(15.dp), tint = content)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = content)
            if (count > 0) {
                Spacer(Modifier.width(5.dp))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = content,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * An overflow button whose menu rows are worded, not drawn. Everything occasional lives here rather than
 * becoming another glyph in a row: a menu row can afford a verb and an object, an icon cannot.
 */
@Composable
internal fun VcsOverflowMenu(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = CaIcons.ellipsis,
    content: @Composable VcsMenuScope.() -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        VcsIconButton(icon, label, { open = true })
        CaDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val scope = remember(open) { VcsMenuScope { open = false } }
            scope.content()
        }
    }
}

/** Rows a [VcsOverflowMenu] offers. Selecting any of them closes the menu. */
internal class VcsMenuScope(private val dismiss: () -> Unit) {

    /** A worded action. [detail] adds a second line for anything whose name alone is not enough. */
    @Composable
    fun item(
        label: String,
        icon: ImageVector? = null,
        detail: String? = null,
        enabled: Boolean = true,
        danger: Boolean = false,
        onClick: () -> Unit,
    ) {
        val scheme = MaterialTheme.colorScheme
        val color = when {
            !enabled -> scheme.outline
            danger -> scheme.error
            else -> scheme.onSurface
        }
        DropdownMenuItem(
            enabled = enabled,
            text = {
                Column {
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
                    if (detail != null) {
                        Text(detail, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
                    }
                }
            },
            leadingIcon = icon?.let {
                { Icon(it, null, Modifier.size(18.dp), tint = if (enabled) color else scheme.outline) }
            },
            onClick = {
                dismiss()
                onClick()
            },
        )
    }

    @Composable
    fun separator() {
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }

    /** A non-interactive heading, so a long menu still reads in groups. */
    @Composable
    fun heading(text: String) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        )
    }
}

/**
 * A one-line explanation under a section heading. Git's staging model is the single thing newcomers trip on,
 * so the panel says what each group of files is rather than assuming the words carry it.
 */
@Composable
internal fun SectionHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp),
    )
}

/** A checkable row with a worded label, for options that would otherwise be an unlabelled toggle. */
@Composable
internal fun VcsCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 5.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .background(
                    if (checked) scheme.primary else Color.Transparent,
                    RoundedCornerShape(5.dp),
                )
                .then(
                    if (checked) Modifier else Modifier.border(1.dp, scheme.outline, RoundedCornerShape(5.dp)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(CaIcons.check, null, Modifier.size(12.dp), tint = scheme.onPrimary)
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
            }
        }
    }
}
