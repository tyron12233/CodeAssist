package dev.ide.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.backend.FileActions

/**
 * The two host bridges a plugin-contributed surface needs but cannot reach through [dev.ide.ui.backend.IdeBackend]:
 * the platform file/link actions, and navigation to another contributed screen.
 *
 * They are composition locals rather than parameters because a tool window, an overlay, and a screen are all
 * rendered from different places in the tree, and threading two arguments through every one of them would
 * spread host wiring across layers that otherwise know nothing about plugins. The app provides both once, at
 * the root.
 */
internal val LocalHostFileActions = staticCompositionLocalOf<FileActions> { FileActions.None }

/** Navigate to a contributed screen by id. The default is a no-op, so a preview or test needs no host. */
internal val LocalPluginNavigator = staticCompositionLocalOf<(String) -> Unit> { {} }
