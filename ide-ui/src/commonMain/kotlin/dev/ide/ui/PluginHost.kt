package dev.ide.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ide.ui.backend.FileActions

/**
 * The host bridges a plugin-contributed surface needs but cannot reach through [dev.ide.ui.backend.IdeBackend]:
 * the platform file/link actions, navigation to another contributed screen, and opening a file in the editor.
 *
 * They are composition locals rather than parameters because a tool window, an overlay, and a screen are all
 * rendered from different places in the tree, and threading three arguments through every one of them would
 * spread host wiring across layers that otherwise know nothing about plugins. The app provides them once, at
 * the root.
 */
internal val LocalHostFileActions = staticCompositionLocalOf<FileActions> { FileActions.None }

/** Navigate to a contributed screen by id. The default is a no-op, so a preview or test needs no host. */
internal val LocalPluginNavigator = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Open a workspace file in the editor at an offset: the one thing a contributed panel that lists code has to
 * ask the host for, since tabs and the caret belong to the editor state, not the backend. Default no-op.
 */
internal val LocalPluginFileOpener = staticCompositionLocalOf<(String, Int) -> Unit> { { _, _ -> } }
