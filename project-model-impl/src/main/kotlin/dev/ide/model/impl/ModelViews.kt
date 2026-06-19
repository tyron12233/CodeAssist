package dev.ide.model.impl

import dev.ide.model.BuildSystemId
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.ContentRoot
import dev.ide.model.DependencyScope
import dev.ide.model.Facet
import dev.ide.model.FacetContainer
import dev.ide.model.FacetKey
import dev.ide.model.Library
import dev.ide.model.MavenClasspath
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryKind
import dev.ide.model.LibraryRef
import dev.ide.model.LibraryTable
import dev.ide.model.ModifiableLibrary
import dev.ide.model.Module
import dev.ide.model.ModuleDependency
import dev.ide.model.ModuleId
import dev.ide.model.ModuleType
import dev.ide.model.OrderEntry
import dev.ide.model.Project
import dev.ide.model.ProjectId
import dev.ide.model.ProjectModelTransaction
import dev.ide.model.ProjectSettings
import dev.ide.model.Sdk
import dev.ide.model.PlatformDependency
import dev.ide.model.SdkDependency
import dev.ide.model.SdkRef
import dev.ide.model.SdkTable
import dev.ide.model.SourceSet
import dev.ide.model.Variant
import dev.ide.model.VariantId
import dev.ide.model.Workspace
import dev.ide.model.WorkspaceTransaction
import dev.ide.model.event.LibrariesChanged
import dev.ide.platform.ContentHash
import dev.ide.platform.ServiceKey
import dev.ide.vfs.VirtualFile
import java.security.MessageDigest

/**
 * The live read views over a [ProjectModelStore] snapshot. Each view captures the immutable
 * [WorkspaceData]/[ProjectData]/[ModuleData] it was built from, so holding a [Project]/[Module]
 * reference gives a stable, consistent picture even across a concurrent commit (snapshot isolation).
 * Rich shapes the api exposes ([VirtualFile], [ModuleType], [Facet]) are resolved here from the
 * stored paths/ids/values.
 */
internal class WorkspaceImpl(private val store: ProjectModelStore) : Workspace {
    override val projects: List<Project> get() = store.data.projects.map { ProjectImpl(it, store) }
    override val libraryTable: LibraryTable get() = LibraryTableImpl(store, projectId = null)
    override val sdkTable: SdkTable get() = SdkTableImpl(store.data.sdks, store)
    override fun <T : Any> service(key: ServiceKey<T>): T = store.service(key)
    override fun beginModification(): WorkspaceTransaction = WorkspaceTransactionImpl(store)
}

internal class ProjectImpl(val data: ProjectData, private val store: ProjectModelStore) : Project {
    override val id = ProjectId(data.id)
    override val name = data.name
    override val rootDir: VirtualFile get() = store.fileFromWorkspace(data.rootRelPath)
    override val buildSystemId = BuildSystemId(data.buildSystemId)
    override val modules: List<Module> get() = data.modules.map { ModuleImpl(it, data, store) }
    override val settings: ProjectSettings = ProjectSettingsImpl(data.settings)
    override val libraryTable: LibraryTable get() = LibraryTableImpl(store, projectId = data.id)

    /** Trivial single variant for now; Android build-type/flavor variants come with android-support. */
    override val variants: List<Variant>
        get() {
            val mainSets = modules.flatMap { m -> m.sourceSets.filter { it.name == "main" } }
            return listOf(
                VariantImpl(
                    VariantId("$name:default"),
                    "default",
                    mainSets,
                    setOf(
                        DependencyScope.API,
                        DependencyScope.IMPLEMENTATION,
                        DependencyScope.COMPILE_ONLY,
                        DependencyScope.RUNTIME_ONLY,
                    ),
                ),
            )
        }

    override fun beginModification(): ProjectModelTransaction = ProjectModelTransactionImpl(store, id)
}

internal class ModuleImpl(
    val data: ModuleData,
    private val projectData: ProjectData,
    private val store: ProjectModelStore,
) : Module {
    override val id = ModuleId(data.id)
    override val name = data.name
    override val type: ModuleType get() = store.moduleTypes.resolve(data.typeId)
    override val languageLevel = data.languageLevel
    override val dependencies: List<OrderEntry> = data.dependencies
    override val facets: FacetContainer = FacetContainerImpl(data.facets, store.facetCodecs)

    private val moduleDir get() = store.resolveRel(store.projectRoot(projectData), data.dirRelPath)

    override val sourceSets: List<SourceSet> get() = data.sourceSets.map { SourceSetImpl(it, store, moduleDir) }
    override val outputDir: VirtualFile get() = store.vfs.fileFor(store.resolveRel(moduleDir, data.outputRelPath))

    /**
     * Classpath assembly with `api`/`implementation` export rules. [scope] selects the phase
     * (compile for API/IMPLEMENTATION/COMPILE_ONLY, runtime for RUNTIME_ONLY, test for
     * TEST_IMPLEMENTATION); it decides which of this module's direct entries are included. When
     * [transitive], dependency modules' entries propagate per phase: a compile classpath inherits
     * only the dependency's `exported` (api) entries (an `implementation` dependency-of-a-dependency
     * is intentionally absent), while a runtime classpath inherits the full runtime closure.
     * Module dependencies are resolved workspace-wide (cross-project). The result is ordered,
     * deduplicated, and content-hashed (the fingerprint keys both the build cache and analyzer caches).
     */
    override fun classpath(scope: DependencyScope, transitive: Boolean): ClasspathSnapshot {
        val items = ArrayList<ClasspathEntry>()
        val visitedModules = hashSetOf(data.id)
        for (entry in data.dependencies) {
            if (directInPhase(scope, entry.scope)) addEntry(entry, scope, transitive, items, visitedModules)
        }
        return ClasspathSnapshotImpl(MavenClasspath.resolveVersionConflicts(items))
    }

    private fun addEntry(
        entry: OrderEntry,
        scope: DependencyScope,
        transitive: Boolean,
        out: MutableList<ClasspathEntry>,
        visited: MutableSet<String>,
    ) {
        when (entry) {
            is LibraryDependency -> resolveLibrary(entry.library)?.classesRoots?.forEach { out.add(ClasspathEntry(it, ClasspathEntryKind.LIBRARY)) }
            is PlatformDependency -> { /* a BOM is a version source only — no classpath artifact */ }
            is SdkDependency -> resolveSdk(entry.sdk)?.bootClasspath?.forEach { out.add(ClasspathEntry(it, ClasspathEntryKind.SDK_BOOTCLASSPATH)) }
            is ModuleDependency -> {
                val target = findModule(entry.target) ?: return
                if (!visited.add(target.module.id)) return
                out.add(ClasspathEntry(moduleOutput(target.project, target.module), ClasspathEntryKind.MODULE_OUTPUT))
                if (transitive) {
                    for (te in target.module.dependencies) {
                        if (propagates(scope, te)) addEntry(te, scope, transitive, out, visited)
                    }
                }
            }
        }
    }

    /** Which of this module's own direct entries belong on the requested classpath. */
    private fun directInPhase(requested: DependencyScope, entry: DependencyScope): Boolean = when (requested) {
        DependencyScope.RUNTIME_ONLY -> entry.onRuntime
        DependencyScope.TEST_IMPLEMENTATION -> entry.onTest
        else -> entry.onCompile // API / IMPLEMENTATION / COMPILE_ONLY -> the compile classpath
    }

    /** Which of a dependency's entries propagate transitively for the requested classpath. */
    private fun propagates(requested: DependencyScope, entry: OrderEntry): Boolean = when (requested) {
        DependencyScope.RUNTIME_ONLY -> entry.scope.onRuntime // full runtime closure
        else -> entry.exported // compile/test: only `api` (exported) propagates
    }

    /**
     * Coarse "is [a] a newer version than [b]" — segment-wise numeric compare, a numeric segment outranking a
     * qualifier so a release beats a pre-release with the same prefix (`1.8.0` > `1.8.0-alpha`). The dependency
     * resolver applies precise Maven ordering when it picks a version; this only has to break a cross-module
     * tie between two versions that were each already deemed valid, so an approximation is sufficient.
     */
    private fun findModule(id: ModuleId): ResolvedModule? =
        store.data.projects.firstNotNullOfOrNull { p ->
            p.modules.firstOrNull { it.id == id.value }?.let { ResolvedModule(p, it) }
        }

    private fun moduleOutput(project: ProjectData, module: ModuleData): VirtualFile {
        val dir = store.resolveRel(store.projectRoot(project), module.dirRelPath)
        return store.vfs.fileFor(store.resolveRel(dir, module.outputRelPath))
    }

    private fun resolveLibrary(ref: LibraryRef): Library? {
        val libData = store.data.projects.firstNotNullOfOrNull { p -> p.libraries.firstOrNull { it.name == ref.name } }
            ?: store.data.libraries.firstOrNull { it.name == ref.name }
        return libData?.let { LibraryImpl(it, store) }
    }

    private fun resolveSdk(ref: SdkRef): Sdk? =
        store.data.sdks.firstOrNull { it.name == ref.name }?.let { SdkImpl(it, store) }

    private class ResolvedModule(val project: ProjectData, val module: ModuleData)
}

internal class SourceSetImpl(
    val data: SourceSetData,
    private val store: ProjectModelStore,
    private val moduleDir: java.nio.file.Path,
) : SourceSet {
    override val name = data.name
    override val scope = data.scope
    override val contentRoots: List<ContentRoot> get() = data.contentRoots.map { ContentRootImpl(it, store, moduleDir) }
}

internal class ContentRootImpl(
    private val data: ContentRootData,
    private val store: ProjectModelStore,
    private val moduleDir: java.nio.file.Path,
) : ContentRoot {
    override val dir: VirtualFile get() = store.vfs.fileFor(store.resolveRel(moduleDir, data.dirRelPath))
    override val roles = data.roles
}

internal class VariantImpl(
    override val id: VariantId,
    override val name: String,
    override val activeSourceSets: List<SourceSet>,
    private val scopes: Set<DependencyScope>,
) : Variant {
    override fun resolvedScopes(): Set<DependencyScope> = scopes
}

internal class ProjectSettingsImpl(override val all: Map<String, String>) : ProjectSettings {
    override fun get(key: String): String? = all[key]
}

internal class FacetContainerImpl(
    private val facets: List<FacetData>,
    private val codecs: FacetCodecRegistry,
) : FacetContainer {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Facet> get(key: FacetKey<T>): T? {
        val codec = codecs.codecFor(key) ?: return null
        val fd = facets.firstOrNull { it.tomlTable == codec.tomlTable } ?: return null
        return codec.decode(fd.values) as T
    }

    override val all: List<Facet> get() = facets.mapNotNull { codecs.decode(it) }
}

internal class ClasspathSnapshotImpl(override val entries: List<ClasspathEntry>) : ClasspathSnapshot {
    override fun fingerprint(): ContentHash {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in entries) {
            md.update(e.kind.name.toByteArray(Charsets.UTF_8))
            md.update(0)
            md.update(e.root.path.toByteArray(Charsets.UTF_8))
            md.update('\n'.code.toByte())
        }
        return ContentHash(md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }
}

// --- library & sdk tables ---

internal class LibraryTableImpl(
    private val store: ProjectModelStore,
    private val projectId: String?,
) : LibraryTable {

    private fun current(): List<LibraryData> =
        if (projectId == null) store.data.libraries
        else store.data.projects.first { it.id == projectId }.libraries

    override val libraries: List<Library> get() = current().map { LibraryImpl(it, store) }

    override fun byName(name: String): Library? = current().firstOrNull { it.name == name }?.let { LibraryImpl(it, store) }

    override fun create(name: String): ModifiableLibrary = ModifiableLibraryImpl(name, store) { libData ->
        val ws = store.data
        val newWs = if (projectId == null) {
            ws.copy(libraries = ws.libraries.filter { it.name != name } + libData)
        } else {
            ws.copy(projects = ws.projects.map { p ->
                if (p.id == projectId) p.copy(libraries = p.libraries.filter { it.name != name } + libData) else p
            })
        }
        store.commit(newWs, listOf(LibrariesChanged(projectId?.let { ProjectId(it) })))
        LibraryImpl(libData, store)
    }
}

internal class ModifiableLibraryImpl(
    private val name: String,
    private val store: ProjectModelStore,
    private val onCommit: (LibraryData) -> Library,
) : ModifiableLibrary {
    override var kind: LibraryKind = LibraryKind.JAR
    private val classes = ArrayList<String>()
    private val sources = ArrayList<String>()

    override fun addClassesRoot(root: VirtualFile) { classes.add(store.relativizeToWorkspace(root)) }
    override fun addSourcesRoot(root: VirtualFile) { sources.add(store.relativizeToWorkspace(root)) }
    override fun commit(): Library = onCommit(LibraryData(name, kind, classes.toList(), sources.toList()))
}

internal class LibraryImpl(private val data: LibraryData, private val store: ProjectModelStore) : Library {
    override val name = data.name
    override val kind = data.kind
    override val classesRoots: List<VirtualFile> get() = data.classes.map { store.fileFromWorkspace(it) }
    override val sourcesRoots: List<VirtualFile> get() = data.sources.map { store.fileFromWorkspace(it) }
}

internal class SdkTableImpl(private val sdkData: List<SdkData>, private val store: ProjectModelStore) : SdkTable {
    override val sdks: List<Sdk> get() = sdkData.map { SdkImpl(it, store) }
    override fun byName(name: String): Sdk? = sdkData.firstOrNull { it.name == name }?.let { SdkImpl(it, store) }
}

internal class SdkImpl(private val data: SdkData, private val store: ProjectModelStore) : Sdk {
    override val name = data.name
    override val bootClasspath: List<VirtualFile> get() = data.bootClasspath.map { store.vfs.fileFor(it) }
    override val buildToolsPath: VirtualFile? get() = data.buildToolsPath?.let { store.vfs.fileFor(it) }
}
