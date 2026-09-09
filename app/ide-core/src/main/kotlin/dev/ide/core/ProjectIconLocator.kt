package dev.ide.core

import dev.ide.android.support.AndroidFacetCodec
import dev.ide.android.support.resources.AndroidLauncherIcon
import dev.ide.android.support.resources.AndroidManifestParser
import dev.ide.android.support.resources.LauncherIcon
import dev.ide.model.ContentRole
import dev.ide.model.impl.ModelPersistence
import java.nio.file.Path

/**
 * Resolves a project's launcher icon for the picker, reading the saved model snapshot cheaply from disk
 * (no open engine). Returns the Android app module's launcher icon (a raster file or a render-ready drawable),
 * or null when the project is not Android / has no resolvable icon (the picker then falls back to the
 * initial-letter tile).
 */
internal object ProjectIconLocator {

    /** The launcher icon for the project rooted at [projectDir], or null. */
    fun locate(projectDir: Path): LauncherIcon? {
        val workspace = runCatching { ModelPersistence.load(projectDir) }.getOrNull() ?: return null
        val project = workspace.projects.firstOrNull() ?: return null
        val projectRoot = projectDir.resolve(project.rootRelPath)

        // The launcher icon belongs to the app; prefer the application module, but a library's icon will do.
        // Facets persist as `(tomlTable, values)`; the android facet lives in the `[android]` table.
        val androidModules = project.modules.mapNotNull { m ->
            m.facets.firstOrNull { it.tomlTable == AndroidFacetCodec.tomlTable }
                ?.let { m to AndroidFacetCodec.decode(it.values) }
        }
        val (module, facet) = androidModules.firstOrNull { it.second.isApplication }
            ?: androidModules.firstOrNull()
            ?: return null

        val moduleDir = projectRoot.resolve(module.dirRelPath)
        val manifest = AndroidManifestParser.parse(moduleDir.resolve(facet.manifest))
        // Only the module's own res; a launcher icon is never declared by a dependency.
        val resRoots = module.sourceSets.flatMap { it.contentRoots }
            .filter { ContentRole.ANDROID_RES in it.roles }
            .map { moduleDir.resolve(it.dirRelPath) }
        return AndroidLauncherIcon.locate(resRoots, manifest?.appIcon, manifest?.appRoundIcon)
    }
}
