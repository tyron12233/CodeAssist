package dev.ide.model

import dev.ide.platform.ExtensionPoint

/**
 * The `platform.fileIcon` extension point. Plugins classify a project-tree node into an icon id, an
 * opaque string the UI's icon registry resolves to a concrete glyph. This is the contract that
 * crosses the UI/backend seam for icons: the backend (and its plugins) decide which icon a node gets;
 * the UI decides how to draw that id. A plugin like `android-support` can give `res/` its own icon
 * without ever depending on Compose.
 *
 * Providers are consulted in [priority] order (highest first); the first non-null [iconFor] wins. The
 * built-in [IconTarget]-exhaustive fallback provider sits at priority 0, so a plugin returns `null` for
 * targets it doesn't care about and lets the default handle them.
 */
interface FileIconProvider {
    /** Higher wins. The built-in fallback is 0; plugins that want to override it use a positive value. */
    val priority: Int get() = 0

    /** The icon id for [target], or null to defer to a lower-priority provider. */
    fun iconFor(target: IconTarget): String?
}

/**
 * What the tree is asking an icon for. Backend-neutral — it carries model types ([Module]/[ContentRole])
 * but no UI types, so providers can classify on extension, content role, source-set name, or facet.
 */
sealed interface IconTarget {
    /** A leaf file (e.g. `Main.java`, `AndroidManifest.xml`). [module] is its owning module if known. */
    data class File(val fileName: String, val module: Module?) : IconTarget

    /** A source/content root surfaced in the tree, with the [roles] it carries and its source-set name. */
    data class SourceRoot(val sourceSetName: String, val roles: Set<ContentRole>, val module: Module?) : IconTarget

    /** A Java/Kotlin package directory (possibly a compacted chain); [packageName] is its dotted fqn. */
    data class PackageDir(val packageName: String) : IconTarget

    /** A plain directory under a non-package root (e.g. `res/values`, an assets sub-folder). */
    data class Directory(val name: String, val roles: Set<ContentRole>) : IconTarget

    /** A module node. Providers may key off [Module.type] or its facets (e.g. an `AndroidFacet`). */
    data class ModuleNode(val module: Module) : IconTarget
}

/**
 * The `platform.fileIcon` extension point. Contribute [FileIconProvider]s here;
 * `project-model-impl`'s `FileIconRegistry` resolves an [IconTarget] against the registered providers.
 */
val FileIconExtensionPoint: ExtensionPoint<FileIconProvider> = ExtensionPoint("platform.fileIcon")
