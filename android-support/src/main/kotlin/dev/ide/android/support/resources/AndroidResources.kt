package dev.ide.android.support.resources

import dev.ide.android.support.AndroidFacet
import dev.ide.android.support.tools.AarExtractor
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.Workspace
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Assembles a module's merged [ResourceRepository] from the project model (own res + dependency res). */
object AndroidResources {

    /**
     * The `res/` roots that merge into [root]'s resources, in build-merge order: dependency AAR res
     * first, then dependency modules' res (transitively), then [root]'s own last, so the module's own
     * resources overlay everything. AAR res is found as a `res/` dir sitting next to a library jar on the
     * compile classpath (the dependency resolver explodes an `.aar`'s `res/` beside its `classes.jar`).
     *
     * Split into [libraryResourceDirs] (immutable, suitable for disk-segment indexing) and
     * [projectResourceDirs] (editable, the resident source side); the repository, which needs the full merged
     * set in order, uses both via this accessor.
     */
    fun resourceDirs(root: Module, workspace: Workspace): List<Path> =
        (libraryResourceDirs(root, workspace) + projectResourceDirs(root, workspace)).distinct()

    /** Immutable dependency/AAR `res/` dirs (content-addressable; the IDE indexes these onto disk segments). */
    fun libraryResourceDirs(root: Module, workspace: Workspace): List<Path> =
        aarResDirs(root, explodeRoot(root, workspace)).distinct()

    /** [root]'s own + dependency-module `res/` dirs (transitive) - the editable, in-memory source side. */
    fun projectResourceDirs(root: Module, workspace: Workspace): List<Path> {
        val out = ArrayList<Path>()
        val visited = HashSet<String>()
        fun visit(m: Module) {
            if (!visited.add(m.id.value)) return
            for (dep in m.dependencies.filterIsInstance<ModuleDependency>()) {
                workspace.projects.firstNotNullOfOrNull { p -> p.modules.firstOrNull { it.id == dep.target } }?.let(::visit)
            }
            if (m.facets.get(AndroidFacet.KEY) != null) {
                m.sourceSets.flatMap { it.contentRoots }
                    .filter { ContentRole.ANDROID_RES in it.roles }
                    .forEach { runCatching { out.add(Paths.get(it.dir.path)) } }
            }
        }
        visit(root)
        return out.distinct()
    }

    /**
     * AAR `res/` dirs for a module's library dependencies. Two forms are handled: a `res/` already sitting
     * next to a library jar (a Maven `.aar` the dependency resolver exploded), and a local `.aar` file
     * on the classpath, which is exploded on demand via [AarExtractor] (idempotent + cached under
     * [explodeRoot]). [explodeRoot] null ⇒ skip the local-`.aar` form.
     */
    private fun aarResDirs(module: Module, explodeRoot: Path?): List<Path> {
        val out = ArrayList<Path>()
        val entries = runCatching { module.classpath(DependencyScope.IMPLEMENTATION).entries }.getOrDefault(emptyList())
            .filter { it.kind == ClasspathEntryKind.LIBRARY }
            .mapNotNull { runCatching { Paths.get(it.root.path) }.getOrNull() }
        for (entry in entries) {
            val sibling = entry.parent?.resolve("res")
            when {
                sibling != null && Files.isDirectory(sibling) -> out.add(sibling)
                explodeRoot != null && entry.toString().endsWith(".aar", ignoreCase = true) && Files.isRegularFile(entry) ->
                    runCatching { AarExtractor.explode(entry, explodeRoot.resolve(stem(entry))).resDir }.getOrNull()?.let { out.add(it) }
            }
        }
        return out.distinct()
    }

    /** Where local `.aar`s are exploded for the IDE: `<projectRoot>/.platform/caches/aar-res`. */
    private fun explodeRoot(module: Module, workspace: Workspace): Path? =
        workspace.projects.firstOrNull { p -> p.modules.any { it.id == module.id } }
            ?.let { runCatching { Paths.get(it.rootDir.path).resolve(".platform/caches/aar-res") }.getOrNull() }

    private fun stem(p: Path): String = p.fileName.toString().substringBeforeLast('.')

    fun repository(
        root: Module,
        workspace: Workspace,
        model: ResourceModel = ResourceModel.DEFAULT,
        /** Live editor-buffer text of an open `res/` file (by absolute path), so an unsaved edit is seen
         *  before save; null = read disk. */
        textOverride: (Path) -> String? = { null },
    ): ResourceRepository =
        model.parse(resourceDirs(root, workspace), textOverride)
}
