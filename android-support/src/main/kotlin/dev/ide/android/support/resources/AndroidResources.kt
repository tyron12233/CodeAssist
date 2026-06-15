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

/** Assembles a module's merged [ResourceRepository] from the project model (own res + dependency res). */
object AndroidResources {

    /**
     * The `res/` roots that merge into [root]'s resources, in build-merge order: dependency AAR res
     * first, then dependency modules' res (transitively), then [root]'s own last — so the module's own
     * resources overlay everything. AAR res is found as a `res/` dir sitting next to a library jar on the
     * compile classpath (the dependency resolver explodes an `.aar`'s `res/` beside its `classes.jar`).
     */
    fun resourceDirs(root: Module, workspace: Workspace): List<Path> {
        val ordered = ArrayList<Path>()
        ordered.addAll(aarResDirs(root, explodeRoot(root, workspace)))
        val visited = HashSet<String>()
        fun visit(m: Module) {
            if (!visited.add(m.id.value)) return
            for (dep in m.dependencies.filterIsInstance<ModuleDependency>()) {
                workspace.projects.firstNotNullOfOrNull { p -> p.modules.firstOrNull { it.id == dep.target } }?.let(::visit)
            }
            if (m.facets.get(AndroidFacet.KEY) != null) {
                m.sourceSets.flatMap { it.contentRoots }
                    .filter { ContentRole.ANDROID_RES in it.roles }
                    .forEach { runCatching { ordered.add(Path.of(it.dir.path)) } }
            }
        }
        visit(root)
        return ordered.distinct()
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
            .mapNotNull { runCatching { Path.of(it.root.path) }.getOrNull() }
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
            ?.let { runCatching { Path.of(it.rootDir.path).resolve(".platform/caches/aar-res") }.getOrNull() }

    private fun stem(p: Path): String = p.fileName.toString().substringBeforeLast('.')

    fun repository(root: Module, workspace: Workspace, model: ResourceModel = ResourceModel.DEFAULT): ResourceRepository =
        model.parse(resourceDirs(root, workspace))
}
