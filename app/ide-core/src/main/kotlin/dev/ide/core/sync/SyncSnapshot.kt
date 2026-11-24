package dev.ide.core.sync

import dev.ide.model.BuildSystemId
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalPlatform
import dev.ide.model.sync.ExternalProjectModel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * What the last sync of a project declared: its modules and their dependencies, kept so the next sync knows
 * what it may take away ([dev.ide.model.impl.ExternalModelApplier]). An IDE-owned project removes only what a
 * sync put there, so a module or dependency the user added in the IDE survives a sync that knows nothing of it.
 *
 * Only what removal needs is kept, one line per module and per dependency, tab-separated:
 * `M <module>` and `D <module> <lib|module|bom> <target> <scope> <variant>`. A file that cannot be read is
 * treated as no previous sync, which removes nothing.
 */
internal object SyncSnapshot {

    fun file(root: Path, importerId: String): Path = root.resolve(".platform").resolve("sync").resolve("$importerId.snapshot")

    fun write(root: Path, importerId: String, model: ExternalProjectModel) {
        val text = buildString {
            for (module in model.modules) {
                append("M\t").append(module.name).append('\n')
                for (dependency in module.dependencies) {
                    val (kind, target) = when (dependency) {
                        is ExternalLibrary -> "lib" to dependency.coordinate
                        is ExternalModuleRef -> "module" to dependency.moduleName
                        is ExternalPlatform -> "bom" to dependency.bom.toString()
                    }
                    append("D\t").append(module.name).append('\t').append(kind).append('\t').append(target)
                        .append('\t').append(dependency.scope.name).append('\t').append(dependency.variant.orEmpty())
                        .append('\n')
                }
            }
        }
        runCatching {
            val file = file(root, importerId)
            Files.createDirectories(file.parent)
            file.writeText(text)
        }
    }

    fun read(root: Path, importerId: String): ExternalProjectModel? {
        val text = runCatching { file(root, importerId).readText() }.getOrNull() ?: return null
        val names = ArrayList<String>()
        val dependencies = LinkedHashMap<String, MutableList<ExternalDependency>>()
        for (line in text.lineSequence()) {
            val parts = line.split('\t')
            when {
                parts.size == 2 && parts[0] == "M" -> names += parts[1]
                parts.size == 6 && parts[0] == "D" -> {
                    val scope = DependencyScope.valueOf(parts[4])
                    val variant = parts[5].ifEmpty { null }
                    val dependency: ExternalDependency = when (parts[2]) {
                        "lib" -> ExternalLibrary(parts[3], scope, variant)
                        "module" -> ExternalModuleRef(parts[3], scope, variant)
                        "bom" -> ExternalPlatform(Coordinate.parseOrNull(parts[3]) ?: continue, scope, variant)
                        else -> continue
                    }
                    dependencies.getOrPut(parts[1]) { ArrayList() } += dependency
                }
            }
        }
        return ExternalProjectModel(
            name = "",
            buildSystemId = BuildSystemId(importerId),
            modules = names.map { ExternalModule(it, dirRelPath = "", typeId = "", dependencies = dependencies[it].orEmpty()) },
        )
    }
}
