package dev.ide.model.impl

import dev.ide.model.ContentRole
import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget

/** Compatibility alias; moved to `project-model-api` (see [dev.ide.model.FileIconRegistry]). */
@Deprecated(
    "Moved to project-model-api",
    ReplaceWith("FileIconRegistry", "dev.ide.model.FileIconRegistry"),
)
typealias FileIconRegistry = dev.ide.model.FileIconRegistry

/**
 * The built-in icon classification (priority 0): extension → language id, content role → source-set id,
 * plus packages/dirs/modules. Returns a non-null id for every [IconTarget], so it is the safety net
 * under any plugin providers. Icon ids match the built-ins registered in the UI's `TreeIcons`.
 */
object DefaultFileIconProvider : FileIconProvider {
    override val priority: Int get() = 0

    override fun iconFor(target: IconTarget): String = when (target) {
        is IconTarget.File -> when {
            // Exact-name matches first (they'd otherwise be caught by an extension rule).
            target.fileName == ".gitignore" || target.fileName == ".gitattributes" ||
                target.fileName == ".gitmodules" || target.fileName == ".gitkeep" -> "git"
            target.fileName == ".editorconfig" -> "editorconfig"
            target.fileName.endsWith(".java") -> "java"
            target.fileName.endsWith(".kt") || target.fileName.endsWith(".kts") -> "kotlin"
            target.fileName.endsWith(".gradle") -> "gradle"
            target.fileName.endsWith(".xml") -> "xml"
            target.fileName.endsWith(".json") -> "json"
            target.fileName.endsWith(".toml") -> "toml"
            target.fileName.endsWith(".yaml") || target.fileName.endsWith(".yml") -> "yaml"
            target.fileName.endsWith(".properties") -> "properties"
            target.fileName.endsWith(".md") || target.fileName.endsWith(".markdown") -> "markdown"
            target.fileName.endsWith(".txt") || target.fileName.endsWith(".log") -> "text"
            target.fileName.endsWith(".png") || target.fileName.endsWith(".jpg") || target.fileName.endsWith(".jpeg") ||
                target.fileName.endsWith(".gif") || target.fileName.endsWith(".webp") || target.fileName.endsWith(".svg") -> "image"
            else -> "file"
        }
        is IconTarget.SourceRoot -> when {
            ContentRole.GENERATED in target.roles -> "sourceset.generated"
            ContentRole.RESOURCE in target.roles -> "sourceset.resources"
            ContentRole.SOURCE in target.roles && target.leafName == "kotlin" -> "sourceset.kotlin"
            ContentRole.SOURCE in target.roles -> "sourceset.java"
            else -> "sourceset.java"
        }
        is IconTarget.PackageDir -> "package"
        is IconTarget.Directory -> "folder"
        is IconTarget.ModuleNode -> "module"
    }
}
