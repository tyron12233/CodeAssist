package dev.ide.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiNavKind
import dev.ide.ui.backend.UiNavOption
import dev.ide.ui.backend.UiNavTarget
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.codeaction_quick_fixes
import dev.ide.ui.generated.resources.ctxmenu_intentions
import dev.ide.ui.generated.resources.nav_declaration
import dev.ide.ui.generated.resources.nav_go_to
import dev.ide.ui.generated.resources.nav_implementations
import dev.ide.ui.generated.resources.nav_none
import dev.ide.ui.generated.resources.nav_super
import dev.ide.ui.generated.resources.nav_type_declaration
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * The editor context menu's state: the action [Menu] (only the navigation actions applicable at the caret,
 * shown alongside the caret's quick-fixes / intentions), or the [Results] of one nav action — a target picker,
 * or (empty) a "nothing found" note.
 */
sealed interface NavMenuState {
    data class Menu(val options: List<UiNavOption>) : NavMenuState
    data class Results(val targets: List<UiNavTarget>) : NavMenuState
}

/**
 * The unified editor context menu — a floating glass dropdown (styled like [CodeActionsMenu]) anchored under
 * the caret, hosted in a `Popup` by [CodeEditor]. In [NavMenuState.Menu] it lists, in labeled sections that
 * appear only when non-empty: **Go to** (the applicable navigation actions), **Quick fixes**, and
 * **Intentions** (the caret's [actions]). Picking a nav action navigates (single target) or flips to
 * [NavMenuState.Results] (a target picker); picking a code action applies it via [onAction]. Pure UI over the
 * neutral DTOs.
 */
@Composable
fun NavMenu(
    state: NavMenuState,
    actions: List<UiAction>,
    width: Dp,
    onOption: (UiNavOption) -> Unit,
    onAction: (UiAction) -> Unit,
    onPick: (UiNavTarget) -> Unit,
) {
    Column(
        Modifier.width(width)
            .background(Ca.colors.glassThick, RoundedCornerShape(Ca.radius.md))
            .border(1.dp, Ca.colors.separator, RoundedCornerShape(Ca.radius.md)),
    ) {
        when (state) {
            is NavMenuState.Menu -> {
                val quickFixes = actions.filter { it.kind == UiActionKind.QUICK_FIX }
                val intentions = actions.filter { it.kind != UiActionKind.QUICK_FIX }
                if (state.options.isEmpty() && actions.isEmpty()) {
                    NothingFound()
                } else {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        if (state.options.isNotEmpty()) {
                            SectionHeader(stringResource(Res.string.nav_go_to))
                            state.options.forEach { opt -> MenuRow(navIcon(opt.kind), navLabel(opt.kind)) { onOption(opt) } }
                        }
                        if (quickFixes.isNotEmpty()) {
                            SectionHeader(stringResource(Res.string.codeaction_quick_fixes))
                            quickFixes.forEach { a -> MenuRow(CaIcons.gear, a.title) { onAction(a) } }
                        }
                        if (intentions.isNotEmpty()) {
                            SectionHeader(stringResource(Res.string.ctxmenu_intentions))
                            intentions.forEach { a -> MenuRow(CaIcons.lightbulb, a.title) { onAction(a) } }
                        }
                    }
                }
            }

            is NavMenuState.Results ->
                if (state.targets.isEmpty()) NothingFound()
                else LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(state.targets) { t -> MenuRow(iconForKind(t.kind), t.label) { onPick(t) } }
                }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(), color = Ca.colors.textTertiary, style = Ca.type.caption2, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun NothingFound() {
    Text(
        stringResource(Res.string.nav_none), color = Ca.colors.textTertiary, style = Ca.type.footnote,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().height(40.dp)
            .background(if (pressed) Ca.colors.accentSoft else Color.Transparent)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (pressed) Ca.colors.accent else Ca.colors.textSecondary)
        Text(
            label, color = Ca.colors.textPrimary, style = Ca.type.code,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun navLabel(kind: UiNavKind): String = when (kind) {
    UiNavKind.DECLARATION -> stringResource(Res.string.nav_declaration)
    UiNavKind.IMPLEMENTATION -> stringResource(Res.string.nav_implementations)
    UiNavKind.TYPE_DECLARATION -> stringResource(Res.string.nav_type_declaration)
    UiNavKind.SUPER -> stringResource(Res.string.nav_super)
}

private fun navIcon(kind: UiNavKind): ImageVector = when (kind) {
    UiNavKind.DECLARATION -> CaIcons.code
    UiNavKind.IMPLEMENTATION -> CaIcons.layers
    UiNavKind.TYPE_DECLARATION -> CaIcons.box
    UiNavKind.SUPER -> CaIcons.pin
}

/** An icon for a nav target's [kind] hint (a lowercase symbol/declaration kind from the backend). */
private fun iconForKind(kind: String): ImageVector = when (kind) {
    "class", "interface", "object", "enum_class", "annotation_class" -> CaIcons.layers
    "method", "fun", "function", "constructor" -> CaIcons.code
    "field", "property", "val", "var" -> CaIcons.code
    "resource" -> CaIcons.resources
    "library" -> CaIcons.box // a compiled library class (opens read-only: decompiled / attached source)
    else -> CaIcons.dot
}
