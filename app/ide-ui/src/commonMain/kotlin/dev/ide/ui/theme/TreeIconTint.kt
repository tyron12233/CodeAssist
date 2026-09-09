package dev.ide.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.ide.ui.icons.IconTint

/**
 * Resolve a tree-node [IconTint] against the live theme (or its fixed brand color). Lives in :ide-ui because
 * it reads the theme tokens ([Ca]); the [IconTint] contract itself is neutral and lives in :ide-ui-api, so
 * icons are registered outside composition and the theme-backed tints are resolved here at render time.
 */
@Composable
fun resolveTint(tint: IconTint): Color = when (tint) {
    IconTint.Accent -> Ca.colors.accent
    IconTint.Primary -> Ca.colors.textPrimary
    IconTint.Secondary -> Ca.colors.textSecondary
    IconTint.Tertiary -> Ca.colors.textTertiary
    IconTint.Success -> Ca.colors.success
    IconTint.Warning -> Ca.colors.warning
    IconTint.Error -> Ca.colors.error
    IconTint.Info -> Ca.colors.info
    is IconTint.Fixed -> tint.color
}
