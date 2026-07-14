package dev.ide.ui.icons

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Resolve an action's string icon id (from the action registry, see `IdeBackend.actionsFor`) to a concrete
 * glyph. The toolbar/menu seams render contributed actions, whose icon ids are opaque strings both sides
 * agree on (the same contract as `TreeNode.iconId`). An unknown id falls back to a generic action glyph, so
 * a plugin that names an icon this build does not ship still renders.
 */
fun actionIcon(iconId: String?): ImageVector = when (iconId) {
    "run", "play" -> CaIcons.play
    "stop" -> CaIcons.stop
    "refresh", "reindex" -> CaIcons.refresh
    "build", "hammer" -> CaIcons.hammer
    "save" -> CaIcons.save
    "search", "find" -> CaIcons.search
    "settings", "gear" -> CaIcons.gear
    "terminal", "console" -> CaIcons.terminal
    "copy" -> CaIcons.copy
    "code" -> CaIcons.code
    "braces", "codeStyle", "format" -> CaIcons.braces
    "file", "doc" -> CaIcons.docText
    "folder" -> CaIcons.folder
    "share" -> CaIcons.share
    "command" -> CaIcons.command
    "eye" -> CaIcons.eye
    "image" -> CaIcons.image
    "layers", "modules" -> CaIcons.layers
    "pkg", "sdk" -> CaIcons.pkg
    "key", "keystore", "signing" -> CaIcons.key
    "lightbulb", "inspections", "analysis" -> CaIcons.lightbulb
    "close" -> CaIcons.close
    "plus" -> CaIcons.plus
    else -> CaIcons.lightbulb
}
