package dev.ide.model.impl

import dev.ide.model.Workspace
import dev.ide.model.event.ProjectModelEvent
import dev.ide.model.event.ProjectModelTopics
import dev.ide.platform.MessageBus
import dev.ide.platform.ServiceKey
import dev.ide.platform.impl.ModelReadWriteLock
import dev.ide.platform.impl.PlatformCore
import dev.ide.vfs.VirtualFile
import dev.ide.vfs.local.LocalFileSystem
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

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
    initial: WorkspaceData,
) {
    @Volatile
    var data: WorkspaceData = normalize(initial)
        private set

    private val services = ConcurrentHashMap<String, Any>()

    /** The live read handle. Each access returns views over the current snapshot. */
    val workspace: Workspace = WorkspaceImpl(this)

    /** Install [newData] atomically and publish [events]. Reentrant under an outer write action. */
    fun commit(newData: WorkspaceData, events: List<ProjectModelEvent>) {
        lock.write {
            data = normalize(newData)
            if (events.isNotEmpty()) {
                bus.syncPublisher(ProjectModelTopics.CHANGES).onEvents(events)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> service(key: ServiceKey<T>): T =
        (services[key.id] as T?) ?: error("no service registered for '${key.id}'")

    fun <T : Any> putService(key: ServiceKey<T>, impl: T) {
        services[key.id] = impl
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
        val p = Path.of(file.path).toAbsolutePath().normalize()
        return if (p == rootPath) "" else rootPath.relativize(p).toString().replace(File.separatorChar, '/')
    }
}

/** Entry point: open (loading from disk if present) or create an empty workspace at [workspaceRoot]. */
object ProjectModel {
    fun open(
        workspaceRoot: Path,
        platform: PlatformCore,
        facetCodecs: FacetCodecRegistry = FacetCodecRegistry(),
    ): ProjectModelStore {
        val root = workspaceRoot.toAbsolutePath().normalize()
        val vfs = LocalFileSystem(root)
        val moduleTypes = ModuleTypeRegistry(platform.extensions)
        val initial = if (ModelPersistence.exists(root)) ModelPersistence.load(root) else WorkspaceData()
        return ProjectModelStore(root, vfs, platform.messageBus, platform.modelLock, moduleTypes, facetCodecs, initial)
    }
}
