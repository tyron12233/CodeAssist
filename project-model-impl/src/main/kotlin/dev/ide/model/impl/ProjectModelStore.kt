package dev.ide.model.impl

import dev.ide.model.Module
import dev.ide.model.ModuleId
import dev.ide.model.WORKSPACE_SERVICE
import dev.ide.model.Workspace
import dev.ide.model.event.ModuleRemoved
import dev.ide.model.event.ProjectModelEvent
import dev.ide.model.event.ProjectModelListener
import dev.ide.model.event.ProjectModelTopics
import dev.ide.platform.ExtensionRegistry
import dev.ide.platform.MessageBus
import dev.ide.platform.MessageBusConnection
import dev.ide.platform.SERVICE_EP
import dev.ide.platform.ServiceContainer
import dev.ide.platform.ServiceScopeLevel
import dev.ide.platform.impl.ApplicationContainer
import dev.ide.platform.impl.ModelReadWriteLock
import dev.ide.platform.impl.PlatformCore
import dev.ide.platform.impl.ServiceContainerImpl
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.Paths

/**
 * Holds the current immutable [WorkspaceData] snapshot and brokers atomic mutation. A commit installs
 * the normalized new snapshot under platform-core's write lock and then publishes its event batch on
 * the message bus, following the ordering: mutate under write lock, publish event, consumers
 * recompute. Readers ([workspace] views) capture the snapshot they observe, so a build/analysis in
 * flight is unaffected by a concurrent commit.
 */
class ProjectModelStore internal constructor(
    val rootPath: Path,
    val vfs: LocalFileSystem,
    val bus: MessageBus,
    val lock: ModelReadWriteLock,
    val moduleTypes: ModuleTypeRegistry,
    val facetCodecs: FacetCodecRegistry,
    /** The per-workspace extension registry; supplies the `platform.service` descriptors. */
    val extensions: ExtensionRegistry,
    /** The process-global application container; the workspace container's parent. */
    val appContainer: ServiceContainer,
    initial: WorkspaceData,
) : AutoCloseable {
    @Volatile
    var data: WorkspaceData = normalize(initial)
        private set

    /** The live read handle. Each access returns views over the current snapshot. */
    val workspace: Workspace = WorkspaceImpl(this)

    /**
     * Workspace-scoped service container (parent = the application container). Built once per open
     * project and disposed when the project closes. The bound [Workspace] is exposed through
     * [WORKSPACE_SERVICE] so a workspace- or module-scoped factory can reach it.
     */
    val workspaceContainer: ServiceContainer =
        ServiceContainerImpl(ServiceScopeLevel.WORKSPACE, appContainer, workspace) { extensions.extensions(SERVICE_EP) }
            .also { it.registerService(WORKSPACE_SERVICE) { workspace } }

    /** Module-scoped containers, created on demand and disposed when their module is removed. */
    private val moduleContainers = ConcurrentHashMap<ModuleId, ServiceContainer>()

    // Dispose a removed module's container synchronously so any OS handles it held (e.g. an analyzer's
    // cached library jars) are released promptly, not at workspace close.
    private val modelConnection: MessageBusConnection = bus.connect().also { conn ->
        conn.subscribe(ProjectModelTopics.CHANGES, ProjectModelListener { events ->
            for (e in events) if (e is ModuleRemoved) moduleContainers.remove(e.module)?.dispose()
        })
    }

    /**
     * This module's MODULE-scoped container. Guarded against a stale id: a module not in the current
     * snapshot has no container (so a removed module never gets a zombie one).
     */
    fun moduleContainer(id: ModuleId): ServiceContainer {
        val moduleView = moduleView(id) ?: error("no module '${id.value}' in the workspace")
        return moduleContainers.computeIfAbsent(id) {
            ServiceContainerImpl(ServiceScopeLevel.MODULE, workspaceContainer, moduleView) { extensions.extensions(SERVICE_EP) }
        }
    }

    /** Dispose and drop [id]'s module container (a no-op if none is live). The next access rebuilds it
     *  against the current snapshot, so callers use this to refresh module services after a model edit. */
    fun disposeModuleContainer(id: ModuleId) {
        moduleContainers.remove(id)?.dispose()
    }

    /** The currently-instantiated module containers (does not create any). For enumerating live module services. */
    fun liveModuleContainers(): Collection<ServiceContainer> = moduleContainers.values.toList()

    /** Dispose every live module container (used when a workspace-wide change, e.g. an SDK swap, invalidates them all). */
    fun disposeAllModuleContainers() {
        val live = moduleContainers.keys.toList()
        for (id in live) moduleContainers.remove(id)?.dispose()
    }

    private fun moduleView(id: ModuleId): Module? =
        workspace.projects.firstNotNullOfOrNull { p -> p.modules.firstOrNull { it.id == id } }

    /** Tear down the workspace + module containers and stop listening for model changes. The application
     *  container (the parent) is owned elsewhere and is left untouched. */
    override fun close() {
        runCatching { modelConnection.dispose() }
        disposeAllModuleContainers()
        runCatching { workspaceContainer.dispose() }
    }

    /** Install [newData] atomically and publish [events]. Reentrant under an outer write action. */
    fun commit(newData: WorkspaceData, events: List<ProjectModelEvent>) {
        lock.write {
            data = normalize(newData)
            if (events.isNotEmpty()) {
                bus.syncPublisher(ProjectModelTopics.CHANGES).onEvents(events)
            }
        }
    }

    /**
     * Replace the workspace SDK table. SDKs are configured/installed outside the project model (there
     * is no public mutation api for them), so this impl-level entry point is how a loader or installer
     * populates them. Goes through the same atomic commit path.
     */
    fun replaceSdks(sdks: List<SdkData>) = commit(data.copy(sdks = sdks), emptyList())

    /** Persist the current snapshot to `<root>/.platform/…` + per-module `module.toml` (crash-safe). */
    fun save() = ModelPersistence.save(this)

    // --- path helpers shared by the view layer ---

    internal fun resolveRel(base: Path, rel: String): Path =
        if (rel.isEmpty() || rel == ".") base else base.resolve(rel).normalize()

    internal fun projectRoot(p: ProjectData): Path = resolveRel(rootPath, p.rootRelPath)

    internal fun fileFromWorkspace(rel: String): VirtualFile = vfs.fileFor(resolveRel(rootPath, rel))

    internal fun relativizeToWorkspace(file: VirtualFile): String {
        val p = Paths.get(file.path).toAbsolutePath().normalize()
        return if (p == rootPath) "" else rootPath.relativize(p).toString().replace(File.separatorChar, '/')
    }
}

/** Entry point: open (loading from disk if present) or create an empty workspace at [workspaceRoot]. */
object ProjectModel {
    fun open(
        workspaceRoot: Path,
        platform: PlatformCore,
        facetCodecs: FacetCodecRegistry = FacetCodecRegistry(),
        /** Process-global application container (the workspace container's parent). Defaults to a fresh,
         *  transient one so standalone callers (tests, one-off bootstraps) work without a ProjectManager. */
        appContainer: ServiceContainer = ApplicationContainer(),
    ): ProjectModelStore {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val vfs = LocalFileSystem(root)
        val moduleTypes = ModuleTypeRegistry(platform.extensions)
        val initial = if (ModelPersistence.exists(root)) ModelPersistence.load(root) else WorkspaceData()
        return ProjectModelStore(
            root, vfs, platform.messageBus, platform.modelLock, moduleTypes, facetCodecs,
            platform.extensions, appContainer, initial,
        )
    }
}
