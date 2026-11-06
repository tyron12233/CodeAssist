package dev.codeassist.dart

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalSourceSet
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens an existing pub package (a cloned repository, an imported zip) as a Dart project: one module, the
 * package, rooted where its `pubspec.yaml` is.
 *
 * Only a package at the folder's root is claimed. A Flutter app keeps Gradle files under `android/`, never
 * at the root, so it is claimed here too, and its Dart code is what gets the analysis server; building it as
 * a Flutter app is not something this plugin does.
 *
 * The IDE owns the model once the package is adopted ([ModelOwnership.IDE]). A pubspec declares a package's
 * dependencies, which pub resolves, not the IDE's modules: there is nothing in it a later sync could change,
 * and treating it as the model's source made every Dart project read as a foreign build "needing a sync".
 */
object PubImporter : ProjectImporter {
    override val id: BuildSystemId = DartModuleType.PUB
    override val displayName = "Dart (pub)"
    override val ownership: ModelOwnership get() = ModelOwnership.IDE

    override fun detect(root: Path): Detection? {
        val pubspec = root.resolve(DartPackages.PUBSPEC)
        if (!Files.isRegularFile(pubspec)) return null
        return Detection(nameOf(root, pubspec), listOf(pubspec), confidence = 10)
    }

    /** Nothing: no edit to the pubspec changes the module (pub get is what reads its dependencies). */
    override fun syncFiles(): List<String> = emptyList()

    override suspend fun resolve(request: SyncRequest): SyncOutcome {
        val pubspec = request.root.resolve(DartPackages.PUBSPEC)
        if (!Files.isRegularFile(pubspec)) return SyncOutcome.failed("No pubspec.yaml at ${request.root}")
        val name = nameOf(request.root, pubspec)
        val module = ExternalModule(
            name = name,
            dirRelPath = "",
            typeId = DartModuleType.ID,
            sourceSets = DartPackages.contentRoots(request.root).map { (set, dirs) ->
                ExternalSourceSet(
                    set,
                    scope = if (set == "test") DependencyScope.TEST_IMPLEMENTATION else DependencyScope.IMPLEMENTATION,
                    roots = dirs.associateWith { setOf(DartModuleType.DART_SOURCE) },
                )
            },
        )
        return SyncOutcome(ExternalProjectModel(name, DartModuleType.PUB, modules = listOf(module)))
    }

    private fun nameOf(root: Path, pubspec: Path): String =
        runCatching { DartPackages.nameOf(String(Files.readAllBytes(pubspec))) }.getOrNull()
            ?: DartPackages.packageName(root.fileName?.toString() ?: "dart")
}
