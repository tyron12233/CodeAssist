package dev.ide.lang.jdt.context

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.ContentRole
import dev.ide.model.Library
import dev.ide.model.LibraryDependency
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.Sdk
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency
import dev.ide.model.Workspace
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.security.MessageDigest

/**
 * Binds analysis to the project model: builds a [CompilationContext] for a [Module] from the model
 * alone (no host state). Walking dependencies with `api`/`implementation` export semantics, it
 * gathers the module's own source roots plus the source roots of the modules it depends on (resolved
 * workspace-wide, so another project's module resolves too), library jars, and the boot classpath
 * from the workspace SDK table. Resolving against dependency *sources* is what makes cross-module and
 * cross-project completion work in the editor before anything is built.
 */
object ModuleCompilationContext {

    fun create(workspace: Workspace, module: Module): CompilationContext {
        val sources = LinkedHashSet<VirtualFile>()
        val libraries = LinkedHashSet<ClasspathEntry>()
        val sourceAttachments = LinkedHashSet<VirtualFile>()
        val sdkNames = LinkedHashSet<String>()
        val libIndex = libraryIndex(workspace)

        collect(workspace, module, isRoot = true, sources, libraries, sourceAttachments, sdkNames, libIndex, HashSet())

        val sdk: Sdk? = sdkNames.firstNotNullOfOrNull { workspace.sdkTable.byName(it) }
            ?: workspace.sdkTable.sdks.firstOrNull()
        val boot = ClasspathSnapshotView(
            (sdk?.bootClasspath ?: emptyList()).map { ClasspathEntry(it, ClasspathEntryKind.SDK_BOOTCLASSPATH) },
        )

        return CompilationContextView(
            sourceRoots = sources.toList(),
            classpath = ClasspathSnapshotView(libraries.toList()),
            bootClasspath = boot,
            languageLevel = module.languageLevel,
            outputDir = module.outputDir,
            sourceAttachments = sourceAttachments.toList(),
        )
    }

    private fun collect(
        workspace: Workspace,
        module: Module,
        isRoot: Boolean,
        sources: MutableSet<VirtualFile>,
        libraries: MutableSet<ClasspathEntry>,
        sourceAttachments: MutableSet<VirtualFile>,
        sdkNames: MutableSet<String>,
        libIndex: Map<String, Library>,
        visited: MutableSet<String>,
    ) {
        if (!visited.add(module.id.value)) return

        for (sourceSet in module.sourceSets) {
            for (root in sourceSet.contentRoots) {
                if (ContentRole.SOURCE in root.roles || ContentRole.GENERATED in root.roles) sources.add(root.dir)
            }
        }

        for (entry in module.dependencies) {
            val propagate = isRoot || entry.exported
            when (entry) {
                is SdkDependency -> sdkNames.add(entry.sdk.name)
                is LibraryDependency -> if (propagate) {
                    libIndex[entry.library.name]?.let { lib ->
                        lib.classesRoots.forEach { libraries.add(ClasspathEntry(it, ClasspathEntryKind.LIBRARY)) }
                        sourceAttachments.addAll(lib.sourcesRoots) // -sources.jars: names + javadoc, not compiled
                    }
                }
                is ModuleDependency -> if (propagate) {
                    findModule(workspace, entry.target)?.let {
                        collect(workspace, it, isRoot = false, sources, libraries, sourceAttachments, sdkNames, libIndex, visited)
                    }
                }
                is PlatformDependency -> { /* a BOM contributes no classes to the compile classpath */ }
            }
        }
    }

    private fun findModule(workspace: Workspace, id: ModuleId): Module? =
        workspace.projects.firstNotNullOfOrNull { p -> p.modules.firstOrNull { it.id == id } }

    private fun libraryIndex(workspace: Workspace): Map<String, Library> {
        val map = HashMap<String, Library>()
        workspace.libraryTable.libraries.forEach { map[it.name] = it }
        workspace.projects.forEach { p -> p.libraryTable.libraries.forEach { map.putIfAbsent(it.name, it) } }
        return map
    }
}

private class CompilationContextView(
    override val sourceRoots: List<VirtualFile>,
    override val classpath: ClasspathSnapshot,
    override val bootClasspath: ClasspathSnapshot,
    override val languageLevel: dev.ide.model.LanguageLevel,
    override val outputDir: VirtualFile,
    override val sourceAttachments: List<VirtualFile>,
) : CompilationContext {
    override val processors: List<AnnotationProcessor> = emptyList()
}

private class ClasspathSnapshotView(override val entries: List<ClasspathEntry>) : ClasspathSnapshot {
    override fun fingerprint(): ContentHash {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in entries) {
            md.update(e.kind.name.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(e.root.path.toByteArray(Charsets.UTF_8)); md.update('\n'.code.toByte())
        }
        return ContentHash(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }
}
