package dev.ide.ui.components

import androidx.compose.foundation.layout.padding
import dev.ide.ui.theme.Ide
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca

/**
 * The app's dropdown menu: a softly-rounded, hairline-bordered panel on the elevated surface with a gentle
 * shadow — replacing Material's squared default so every popup matches the bespoke design language. A drop-in
 * for [DropdownMenu] (the [containerColor] is clipped to the rounded [shape], so call sites no longer need the
 * old `Modifier.background(...)` hack). [offset] nudges the menu off its anchor.
 */
@Composable
fun CaDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 6.dp),
    /**
     * Set when the menu's rows are led by icons but not all of them are. The rows without one then keep
     * the slot empty rather than skipping it, so every label in the menu starts at the same inset — a
     * menu that mixes the two otherwise reads as two lists that happen to share a panel.
     */
    iconLed: Boolean = false,
    /**
     * Put each row's icon at the trailing end, label first: the Material arrangement for the ⋮ overflow of a
     * toolbar, where the menu is a list of commands read by their names. A menu of places or objects (a file's
     * context menu) keeps its icons leading.
     */
    iconsTrailing: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(CaMenuDefaults.ContainerRadius),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 14.dp,
        border = BorderStroke(1.dp, Ide.colors.glassEdge),
    ) {
        CompositionLocalProvider(LocalMenuIsIconLed provides iconLed, LocalMenuIconsTrailing provides iconsTrailing) { content() }
    }
}

/** Whether the enclosing [CaDropdownMenu] holds its leading slot open for rows that have no icon. */
internal val LocalMenuIsIconLed = compositionLocalOf { false }

/** Whether the enclosing [CaDropdownMenu] puts its rows' icons after the label. */
internal val LocalMenuIconsTrailing = compositionLocalOf { false }

/**
 * A menu row that opens a native **flyout submenu** beside itself, rather than swapping the parent menu's
 * contents in place. The flyout anchors to the row's top-right corner (flipping toward the left edge of the
 * window automatically when there's no room on the right) and inherits [CaDropdownMenu]'s scale+fade
 * entrance, so it visibly grows out of the parent row the way a desktop/native submenu does.
 *
 * It opens on hover (desktop) or tap (touch). [expanded]/[onExpandedChange] are hoisted so the hosting menu
 * can keep a single submenu open at a time (set its key on open, clear on close) — point each sibling at the
 * same backing state and only one flyout is ever live.
 */
@Composable
fun CaSubmenuItem(
    label: String,
    icon: ImageVector?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    danger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var itemSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // Desktop: hovering the row opens the flyout (the native affordance). Touch has no hover, so the tap
    // handler below is the open path there. We never auto-close on hover-out, so the pointer can travel from
    // the row into the (separate) flyout popup without it collapsing — it closes via dismiss or a sibling.
    LaunchedEffect(hovered) { if (hovered) onExpandedChange(true) }
    Box {
        CaMenuItem(
            label = label,
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .hoverable(interaction)
                .onGloballyPositioned { itemSize = it.size },
            icon = icon,
            danger = danger,
            // The caret points into the flyout, which opens on the trailing side, so it mirrors with it.
            trailing = { Icon(CaIcons.caretRight, null, Modifier.size(CaMenuDefaults.CaretSize)) },
        )
        // Anchor the flyout to the row's top-right corner: shift it right by the row's width (less a hair so
        // it overlaps the row and leaves no dead gap a hovering pointer would fall through) and up by the row
        // height so the panel's top lines up with the row. Material's menu position provider keeps it on
        // screen, flipping horizontally near the window edge.
        val flyoutOffset = with(density) {
            DpOffset(x = itemSize.width.toDp() - 4.dp, y = -itemSize.height.toDp())
        }
        CaDropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            offset = flyoutOffset,
            content = content,
        )
    }
}

/** The metrics every [CaMenuItem] shares, so one place decides how a menu row is proportioned. */
object CaMenuDefaults {
    /**
     * Leading and trailing glyphs in a menu row.
     *
     * Sized against the label rather than as a touch target: the whole row is the target, so the glyph
     * only has to sit at the label's weight. The rows used to range over 14-16 dp per call site, which
     * reads as a ragged column of icons once a menu has more than a few entries.
     */
    val IconSize = 24.dp

    /** The caret that marks a row as opening a submenu: quieter than the row's own icon. */
    val CaretSize = 16.dp

    /** Corner of the menu panel; its rows are inset and rounded to sit inside it. */
    val ContainerRadius = 20.dp

    /** Corner of a row's hover/selected fill. */
    val ItemRadius = 12.dp
}

/**
 * One row of a [CaDropdownMenu].
 *
 * The IDE's menus are its secondary surface: an overflow, a long-press on a file, the picker behind a
 * chip. They are read in a glance and dismissed, so what matters is that a row's parts land where the eye
 * already is. Routing them all through here is what makes that true - one icon size, one label weight,
 * one second line, and the mark for the current entry always at the same end - instead of each menu
 * arriving at its own spacing.
 *
 * The slots are laid out along the reading direction, so the row mirrors under RTL with nothing said
 * here: the icon leads and the mark trails in Arabic exactly as they do in English.
 *
 * [selected] is the entry the menu is currently reporting - the active variant, the sort in force, the
 * panel already open. It carries emphasis only; a menu that also marks its selection passes the mark as
 * [trailing] (see [CaMenuCheck]), because which entries deserve a mark is the menu's decision and not
 * every one of them has a selection to report.
 */
@Composable
fun CaMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** A second, quieter line under [label]: a task's group, a path, whatever qualifies the row. */
    supporting: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    /** A destructive row (Delete). Colours the label and its icon with the error role. */
    danger: Boolean = false,
    /** Custom leading content where an [icon] will not do, drawn at [CaMenuDefaults.IconSize]. */
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val labelColor = when {
        danger -> scheme.error
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    val iconColor = when {
        danger -> scheme.error
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurfaceVariant
    }
    // Material 3's menu row: inset from the panel's edge with its own rounded fill (so hover, press and the
    // selected entry read as pills inside the panel), 48dp tall, a 24dp icon and a 12dp gap, 12dp side padding.
    val iconsTrailing = LocalMenuIconsTrailing.current && leading == null && trailing == null && icon != null
    val leadingSlot: @Composable (() -> Unit)? = if (iconsTrailing) null else leading
        ?: icon?.let { { Icon(it, null, Modifier.size(CaMenuDefaults.IconSize), tint = iconColor) } }
        ?: if (LocalMenuIsIconLed.current) ({ Spacer(Modifier.size(CaMenuDefaults.IconSize)) }) else null
    val shape = RoundedCornerShape(CaMenuDefaults.ItemRadius)
    Row(
        modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, shape)
            .alpha(if (enabled) 1f else 0.38f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingSlot != null) {
            leadingSlot()
            Spacer(Modifier.width(12.dp))
        }
        // Left to wrap rather than ellipsized: a menu row is 280 dp at most, and a translated
        // label routinely runs longer than its English original. Losing its tail is worse than a
        // row that takes two lines.
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.labelSmall, color = scheme.outline)
            }
        }
        if (iconsTrailing) {
            Spacer(Modifier.width(12.dp))
            Icon(icon!!, null, Modifier.size(CaMenuDefaults.IconSize), tint = iconColor)
        } else if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides scheme.onSurfaceVariant) { trailing() }
        }
    }
}

/** The mark a menu puts on the entry currently in force, at the trailing end of its row. */
@Composable
fun CaMenuCheck() {
    Icon(CaIcons.check, null, Modifier.size(CaMenuDefaults.IconSize), tint = MaterialTheme.colorScheme.primary)
}

/**
 * The shortcut in force for [actionId], set quietly at the end of a menu row or a palette row: `Ctrl+W` on
 * Android, `⌘W` on a Mac. Nothing when the command has no shortcut, or when no hardware keyboard is attached
 * (a phone has no use for it). Pass it as a [CaMenuItem]'s `trailing`.
 */
@Composable
fun ShortcutHint(actionId: String, modifier: Modifier = Modifier) {
    if (!dev.ide.ui.platform.hasHardwareKeyboard()) return
    val text = dev.ide.ui.platform.shortcutTextFor(actionId) ?: return
    Text(
        text,
        modifier = modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outline,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
    )
}
