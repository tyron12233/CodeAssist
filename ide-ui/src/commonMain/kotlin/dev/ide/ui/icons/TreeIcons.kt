package dev.ide.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.ide.ui.theme.Ca

/** How a tree node is drawn, once its icon id is resolved by [TreeIcons]. */
sealed interface TreeIcon {
    /** A single stroked/filled glyph, tinted by [tint]. */
    data class Glyph(val image: ImageVector, val tint: IconTint = IconTint.Secondary) : TreeIcon
    /** A folder that swaps its glyph open/closed, tinted by [tint]. */
    data class Folder(val closed: ImageVector, val open: ImageVector, val tint: IconTint = IconTint.Secondary) : TreeIcon
    /** A letter-in-rounded-square badge in a fixed [color] (e.g. "J" for Java). */
    data class Badge(val text: String, val color: Color) : TreeIcon
}

/**
 * A tint resolved against the live theme at render time, or a [Fixed] brand color (e.g. Android green).
 * Icons are registered outside composition, so the theme-backed tints can't be baked in eagerly.
 */
sealed interface IconTint {
    object Accent : IconTint
    object Primary : IconTint
    object Secondary : IconTint
    object Tertiary : IconTint
    object Success : IconTint
    object Warning : IconTint
    object Error : IconTint
    object Info : IconTint
    data class Fixed(val color: Color) : IconTint
}

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

/**
 * The file-tree icon registry: maps an icon id (the string a backend `FileIconProvider` returns) to a
 * renderable [TreeIcon]. Built-ins are registered at init; plugins/launchers may [register] more or
 * override existing ids — the backing map is observable so a late registration recomposes the tree.
 * Unknown ids fall back to a muted file glyph.
 */
object TreeIcons {
    private val fallback = TreeIcon.Glyph(CaIcons.file, IconTint.Tertiary)
    private val registry = mutableStateMapOf<String, TreeIcon>()

    /** Register (or override) the icon for [iconId]. */
    fun register(iconId: String, icon: TreeIcon) { registry[iconId] = icon }

    /** The icon for [iconId], or a muted file glyph if none is registered. */
    fun resolve(iconId: String): TreeIcon = registry[iconId] ?: fallback

    /** Android brand green — android modules, `res/`, and the manifest. */
    private val androidGreen = Color(0xFF3DDC84)

    init {
        register("workspace", TreeIcon.Glyph(CaIcons.layers, IconTint.Accent))
        register("module", TreeIcon.Glyph(CaIcons.layers, IconTint.Accent))
        register("module.android", TreeIcon.Glyph(CaIcons.androidLogo, IconTint.Fixed(androidGreen)))
        register("sourceset.java", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Accent))
        register("sourceset.kotlin", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Fixed(Color(0xFFCD7EE0))))
        register("sourceset.resources", TreeIcon.Glyph(CaIcons.resources, IconTint.Info))
        register("sourceset.android-res", TreeIcon.Glyph(CaIcons.image, IconTint.Fixed(androidGreen)))
        register("sourceset.assets", TreeIcon.Glyph(CaIcons.box, IconTint.Warning))
        register("sourceset.generated", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Tertiary))
        // Derived build output (the curated "build outputs" node + the raw `build/` dir) — IntelliJ marks
        // excluded/output dirs with a warm tint; the row text is additionally muted via `styleHint`.
        register("build-output", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Warning))
        register("package", TreeIcon.Glyph(CaIcons.pkg, IconTint.Secondary))
        register("folder", TreeIcon.Folder(CaIcons.folder, CaIcons.folderOpen, IconTint.Secondary))
        register("manifest", TreeIcon.Glyph(CaIcons.file, IconTint.Fixed(androidGreen)))
        register("file", fallback)
        register("java", TreeIcon.Badge("J", Color(0xFFD9A066)))
        register("kotlin", TreeIcon.Badge("K", Color(0xFFCD7EE0)))
        register("xml", TreeIcon.Badge("‹›", Color(0xFF61AFEF)))
    }
}
