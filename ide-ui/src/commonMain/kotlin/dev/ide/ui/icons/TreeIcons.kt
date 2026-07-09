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
        // ProGuard/R8 keep-rule files (`proguard-rules.pro`, `consumer-rules.pro`) — the shrinker config.
        register("proguard", TreeIcon.Badge("R8", Color(0xFF56B6C2)))
        // Data / config formats — colored letter badges, JSON as the braces glyph (it fits perfectly).
        register("json", TreeIcon.Glyph(CaIcons.braces, IconTint.Fixed(Color(0xFFC9A227))))
        register("toml", TreeIcon.Badge("T", Color(0xFFB0703A)))
        register("yaml", TreeIcon.Badge("Y", Color(0xFFCB4B34)))
        register("properties", TreeIcon.Badge("=", Color(0xFF8B8D96)))
        register("editorconfig", TreeIcon.Badge("EC", Color(0xFF8B8D96)))
        // Docs / text.
        register("markdown", TreeIcon.Badge("M", Color(0xFF6C9BD1)))
        register("text", TreeIcon.Glyph(CaIcons.docText, IconTint.Tertiary))
        // Raster/vector images — the image glyph, theme-blue (a plain image, not the android-green res set).
        register("image", TreeIcon.Glyph(CaIcons.image, IconTint.Info))
        // Groovy Gradle scripts (a `.gradle.kts` shows as Kotlin) + VCS metadata.
        register("gradle", TreeIcon.Badge("G", Color(0xFF6BA84F)))
        register("git", TreeIcon.Glyph(CaIcons.gitBranch, IconTint.Fixed(Color(0xFFDE6E43))))
    }
}

/**
 * The icon id for a file BY NAME — a pure-UI mirror of the engine's file-icon providers (the built-in
 * `DefaultFileIconProvider` + Android's `AndroidFileIconProvider`, file targets only), so a tab or
 * breadcrumb can show the SAME icon the file tree does without needing a `TreeNode` or a backend round-trip
 * (a file opens from many origins — tree, go-to-symbol, console, session restore). The Android rules
 * (manifest, ProGuard) are checked first, matching the providers' priority order. Resolve the returned id
 * through [TreeIcons.resolve].
 */
fun fileIconId(fileName: String): String = when {
    // Exact-name matches first (they'd otherwise be caught by an extension rule, e.g. AndroidManifest → xml).
    fileName == "AndroidManifest.xml" -> "manifest"
    fileName == ".gitignore" || fileName == ".gitattributes" || fileName == ".gitmodules" || fileName == ".gitkeep" -> "git"
    fileName == ".editorconfig" -> "editorconfig"
    fileName.endsWith(".pro") -> "proguard"
    fileName.endsWith(".java") -> "java"
    fileName.endsWith(".kt") || fileName.endsWith(".kts") -> "kotlin"
    fileName.endsWith(".gradle") -> "gradle"
    fileName.endsWith(".xml") -> "xml"
    fileName.endsWith(".json") -> "json"
    fileName.endsWith(".toml") -> "toml"
    fileName.endsWith(".yaml") || fileName.endsWith(".yml") -> "yaml"
    fileName.endsWith(".properties") -> "properties"
    fileName.endsWith(".md") || fileName.endsWith(".markdown") -> "markdown"
    fileName.endsWith(".txt") || fileName.endsWith(".log") -> "text"
    fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") ||
        fileName.endsWith(".gif") || fileName.endsWith(".webp") || fileName.endsWith(".svg") -> "image"
    else -> "file"
}
