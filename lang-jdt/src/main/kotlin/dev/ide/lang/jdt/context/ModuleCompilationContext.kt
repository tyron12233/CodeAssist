package dev.ide.lang.jdt.context

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.MavenClasspath
import dev.ide.model.ContentRole
import dev.ide.model.Library
import dev.ide.model.LibraryDependency
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.Sdk
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency
import dev.ide.model.SdkResolution
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

    /**
     * [variant] is the active build-variant config-name set (e.g. `{main, free, debug, freeDebug}`); a
     * `null` variant includes every dependency (the build-variant-agnostic default), while a non-null set
     * drops any dependency whose [dev.ide.model.OrderEntry.variant] qualifier isn't in it (a shared,
     * unqualified one always stays). Lets the editor analyze against the selected variant's classpath.
     */
    fun create(workspace: Workspace, module: Module, variant: Set<String>? = null): CompilationContext {
        val sources = LinkedHashSet<VirtualFile>()
        val libraries = LinkedHashSet<ClasspathEntry>()
        val sourceAttachments = LinkedHashSet<VirtualFile>()
        val libIndex = libraryIndex(workspace)

        collect(workspace, module, isRoot = true, variant, sources, libraries, sourceAttachments, libIndex, HashSet())

        // The boot classpath is THIS module's platform SDK, resolved by kind (a `java-*`/`kotlin-*` module
        // gets the core-Java SDK, an `android-*` module the Android SDK) — the same resolver the build uses,
        // so the editor never resolves `android.*` that the build would reject. See [SdkResolution].
        val sdk: Sdk? = SdkResolution.sdkFor(workspace, module)
        val boot = ClasspathSnapshotView(
            (sdk?.bootClasspath ?: emptyList()).map { ClasspathEntry(it, ClasspathEntryKind.SDK_BOOTCLASSPATH) },
        )

        return CompilationContextView(
            sourceRoots = sources.toList(),
            // Each declared dependency's closure is assembled independently, so the union can carry two
            // versions of one artifact (e.g. `androidx.collection` 1.1.0 + 1.4.0). Collapse to newest-wins —
            // the same dedup the build classpath uses — so the analyzer sees one version per artifact (no
            // duplicate classes), matching a whole-graph resolve.
            classpath = ClasspathSnapshotView(MavenClasspath.resolveVersionConflicts(libraries.toList())),
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
        variant: Set<String>?,
        sources: MutableSet<VirtualFile>,
        libraries: MutableSet<ClasspathEntry>,
        sourceAttachments: MutableSet<VirtualFile>,
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
            if (!includedInVariant(entry.variant, variant)) continue
            val propagate = isRoot || entry.exported
            when (entry) {
                // The platform SDK is resolved once for the root module by [SdkResolution]; a transitive
                // module dependency doesn't change this module's platform, so SdkDependency is ignored here.
                is SdkDependency -> { /* handled by SdkResolution.sdkFor above */ }
                is LibraryDependency -> if (propagate) {
                    libIndex[entry.library.name]?.let { lib ->
                        lib.classesRoots.forEach { libraries.add(ClasspathEntry(it, ClasspathEntryKind.LIBRARY)) }
                        sourceAttachments.addAll(lib.sourcesRoots) // -sources.jars: names + javadoc, not compiled
                    }
                }
                is ModuleDependency -> if (propagate) {
                    findModule(workspace, entry.target)?.let {
                        collect(workspace, it, isRoot = false, variant, sources, libraries, sourceAttachments, libIndex, visited)
                    }
                }
                is PlatformDependency -> { /* a BOM contributes no classes to the compile classpath */ }
            }
        }
    }

    /** Whether an entry whose qualifier is [entryVariant] belongs to the active config set [active] (null = all). */
    private fun includedInVariant(entryVariant: String?, active: Set<String>?): Boolean =
        active == null || entryVariant == null || entryVariant in active

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
