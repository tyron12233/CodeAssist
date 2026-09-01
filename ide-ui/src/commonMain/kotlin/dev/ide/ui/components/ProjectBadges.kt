package dev.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ui.generated.resources.Res
import dev.ide.ui.generated.resources.project_kind_android
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import org.jetbrains.compose.resources.stringResource

/**
 * Project badges shared by the sharing/export screens.
 *
 * These outlived the project picker they were written for: the Home redesign describes a project with
 * monospace metadata chips instead of coloured pills, but the export and share flows still use both, so
 * they live here rather than inside a screen.
 */

/** Android green — the project-kind tag on an Android project's card. */
private val AndroidGreen = Color(0xFF3DDC84)

/** A compact green pill (robot glyph + "Android") marking an Android project. */
@Composable
internal fun AndroidTag() {
    Row(
        Modifier
            .background(AndroidGreen.copy(alpha = 0.16f), RoundedCornerShape(Ca.radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(CaIcons.androidLogo, null, Modifier.size(12.dp), tint = AndroidGreen.darken(0.85f))
        Text(
            stringResource(Res.string.project_kind_android),
            color = AndroidGreen.darken(0.85f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * A stable per-project accent, hashed from the project's name.
 *
 * Hashed rather than stored so a project always gets the same colour without anything having to persist
 * it, and two projects opened side by side are very unlikely to collide.
 */
private val PROJECT_PALETTE = listOf(
    Color(0xFF3DDC84), Color(0xFF7F52FF), Color(0xFFF89820),
    Color(0xFFE0533D), Color(0xFF3FBDD9), Color(0xFFB487F7), Color(0xFF00A8A0),
)

internal fun projectColor(name: String): Color =
    PROJECT_PALETTE[(name.hashCode() and 0x7fffffff) % PROJECT_PALETTE.size]
