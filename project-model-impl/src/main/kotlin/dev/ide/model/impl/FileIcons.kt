package dev.ide.model.impl

import dev.ide.model.ContentRole
import dev.ide.model.FileIconExtensionPoint
import dev.ide.model.FileIconProvider
import dev.ide.model.IconTarget
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.PluginId

/**
 * Resolves an [IconTarget] to an icon id against the [FileIconProvider]s plugins contributed to
 * [FileIconExtensionPoint]. Providers are tried highest-[priority][FileIconProvider.priority] first and
 * the first non-null answer wins; [DefaultFileIconProvider] sits at priority 0 as an exhaustive
 * fallback, so [resolve] only returns null if no provider (not even the default) is registered.
 */
class FileIconRegistry(private val extensions: ExtensionRegistry) {
    fun register(provider: FileIconProvider, plugin: PluginId) =
        extensions.register(FileIconExtensionPoint, provider, plugin)

    fun resolve(target: IconTarget): String? =
        extensions.extensions(FileIconExtensionPoint)
            .sortedByDescending { it.priority }
            .firstNotNullOfOrNull { it.iconFor(target) }
}

/**
 * The built-in icon classification (priority 0): extension → language id, content role → source-set id,
 * plus packages/dirs/modules. Returns a non-null id for every [IconTarget], so it is the safety net
 * under any plugin providers. Icon ids match the built-ins registered in the UI's `TreeIcons`.
 */
object DefaultFileIconProvider : FileIconProvider {
    override val priority: Int get() = 0

    override fun iconFor(target: IconTarget): String = when (target) {
        is IconTarget.File -> when {
            target.fileName.endsWith(".java") -> "java"
            target.fileName.endsWith(".kt") || target.fileName.endsWith(".kts") -> "kotlin"
            target.fileName.endsWith(".xml") -> "xml"
            else -> "file"
        }
        is IconTarget.SourceRoot -> when {
            ContentRole.GENERATED in target.roles -> "sourceset.generated"
            ContentRole.RESOURCE in target.roles -> "sourceset.resources"
            ContentRole.SOURCE in target.roles -> "sourceset.java"
            else -> "sourceset.java"
        }
        is IconTarget.PackageDir -> "package"
        is IconTarget.Directory -> "folder"
        is IconTarget.ModuleNode -> "module"
    }
}
