package dev.ide.model.sync

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.Coordinate
import dev.ide.model.DependencyScope
import dev.ide.model.Exclusion
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.SdkRef
import dev.ide.platform.ExtensionPoint
import dev.ide.platform.ProgressReporter
import dev.ide.vfs.VirtualFile
import java.nio.file.Path

/**
 * project-model-api / sync: the seam a foreign build system (Gradle, Maven, Bazel, a house format) plugs
 * its own project model into.
 *
 * The split is deliberate: a [ProjectImporter] only *reads* the build files and returns a declarative
 * [ExternalProjectModel] snapshot; the host applies that snapshot to the live model in one transaction
 * (see `ExternalModelApplier` in project-model-impl). An importer therefore never touches
 * [dev.ide.model.ProjectModelTransaction], needs no knowledge of persistence or the message bus, is
 * testable as a pure function, and its output can be cached and diffed between syncs.
 *
 * Building is a separate concern ([dev.ide.build.BuildSystem]): the native project model has no importer,
 * and an importer for a build system we can't execute still gets a usable project.
 */

// ---------------------------------------------------------------------------
// Sync messages (shared with build-api's SyncResult)
// ---------------------------------------------------------------------------

enum class SyncSeverity { INFO, WARNING, ERROR }

/** One thing worth telling the user about a sync: what couldn't be read, what was assumed, what failed. */
data class SyncMessage(val severity: SyncSeverity, val text: String, val file: VirtualFile? = null)

// ---------------------------------------------------------------------------
// The importer SPI
// ---------------------------------------------------------------------------

/** Who owns the project model: the IDE's own `module.toml`, or the foreign build system's build files. */
enum class ModelOwnership {
    /** The IDE model is the source of truth (the native project format). Edits are written to it directly. */
    IDE,

    /** The build files are the source of truth; the IDE model is a projection re-derived on every sync.
     *  Declaration edits must go through a [BuildFileWriter] to survive the next sync. */
    EXTERNAL,
}

/** Why a sync is running: importers use it to pick between a full read and a cheaper refresh. */
enum class SyncReason {
    /** First import of a folder the IDE has never opened. */
    IMPORT,

    /** The user asked for it (the Sync action / banner). */
    MANUAL,

    /** A watched build file changed since the last sync. */
    BUILD_FILE_CHANGED,

    /** The project was opened and its recorded snapshot is stale or missing. */
    OPEN,
}

/** What [ProjectImporter.detect] found: a display [name] for the project and the files that identify it. */
data class Detection(
    val name: String,
    /** The build files that made this a match, ABSOLUTE. Their timestamps drive staleness detection. */
    val markers: List<Path>,
    /** Tie-break when several importers claim the same folder; the highest wins. */
    val confidence: Int = 0,
)

/** The inputs of one sync. [previous] is the snapshot the last sync produced, or null on a first import. */
class SyncRequest(
    val root: Path,
    val progress: ProgressReporter,
    val reason: SyncReason,
    val previous: ExternalProjectModel? = null,
)

/** A sync's result: the new snapshot (null == the sync failed) plus everything worth reporting. */
data class SyncOutcome(
    val model: ExternalProjectModel?,
    val messages: List<SyncMessage> = emptyList(),
) {
    val ok: Boolean get() = model != null

    companion object {
        fun failed(text: String): SyncOutcome =
            SyncOutcome(null, listOf(SyncMessage(SyncSeverity.ERROR, text)))
    }
}

/**
 * Reads a foreign build system's files into an [ExternalProjectModel]. Contributed through
 * [PROJECT_IMPORTER_EP]; the host picks one per project root by [detect] (highest [Detection.confidence]),
 * records its [id] as the project's [dev.ide.model.Project.buildSystemId], and re-runs [resolve] whenever a
 * [syncFiles] match changes.
 */
interface ProjectImporter {
    /** The build system this importer speaks for; becomes the project's `buildSystemId`. */
    val id: BuildSystemId

    /** Human name for the UI ("Gradle", "Maven"). */
    val displayName: String

    /** Does [root] look like one of my projects? Null == not mine. Must not throw and must not write. */
    fun detect(root: Path): Detection?

    /** Who owns the model once imported. [ModelOwnership.EXTERNAL] for a real foreign build system. */
    val ownership: ModelOwnership get() = ModelOwnership.EXTERNAL

    /**
     * Project-relative glob patterns whose change makes the model stale: a single star stays inside one path
     * segment, a double star crosses separators. A Gradle importer returns the settings script, every
     * `build.gradle` at any depth, `gradle.properties`, and the version catalog. The host watches them and
     * offers a Sync.
     */
    fun syncFiles(): List<String>

    /** Read the build files at [SyncRequest.root] into a snapshot. Pure with respect to the model: it may
     *  read the file system and the network, but must not mutate the project model. */
    suspend fun resolve(request: SyncRequest): SyncOutcome
}

/** Plugins contribute project importers here; the host selects one per project root by [ProjectImporter.detect]. */
val PROJECT_IMPORTER_EP = ExtensionPoint<ProjectImporter>("platform.projectImporter")

// ---------------------------------------------------------------------------
// The snapshot: a declarative project model, free of live model/persistence types
// ---------------------------------------------------------------------------

/**
 * A whole project as an importer sees it. Plain, comparable data: two syncs that read the same build files
 * produce equal snapshots, which is what lets the host skip work and diff for removals.
 */
data class ExternalProjectModel(
    val name: String,
    val buildSystemId: BuildSystemId,
    val modules: List<ExternalModule> = emptyList(),
    /** Extra Maven repositories the build files declare (the defaults need no entry). */
    val repositories: List<ExternalRepository> = emptyList(),
)

/**
 * One module. [typeId] is resolved against [dev.ide.model.ModuleTypeExtensionPoint] (`android-app`,
 * `java-lib`), so an importer names a type without depending on the plugin that provides it. [facets] work
 * the same way: table name plus values, decoded by the registered facet codecs.
 */
data class ExternalModule(
    val name: String,
    /** Module directory, relative to the project root (`"app"`, `"features/home"`). */
    val dirRelPath: String,
    val typeId: String,
    /** Null keeps the host default (the project's language level). */
    val languageLevel: LanguageLevel? = null,
    /** Explicit platform-SDK override; null resolves by module type. */
    val sdk: SdkRef? = null,
    /** Source sets to declare. Empty leaves the module type's defaults in place. */
    val sourceSets: List<ExternalSourceSet> = emptyList(),
    val dependencies: List<ExternalDependency> = emptyList(),
    val facets: List<ExternalFacet> = emptyList(),
)

data class ExternalSourceSet(
    val name: String,
    val scope: DependencyScope = DependencyScope.IMPLEMENTATION,
    /** Content roots relative to the module dir, each with its roles. */
    val roots: Map<String, Set<ContentRole>> = emptyMap(),
)

/** A dependency declaration. Mirrors [dev.ide.model.OrderEntry] without binding to the live model. */
sealed interface ExternalDependency {
    val scope: DependencyScope

    /** Build-variant config qualifier (`debug`, `free`), or null for "every variant". */
    val variant: String?
}

/** An external artifact, as the coordinate string the build file declared (`group:name:version`). */
data class ExternalLibrary(
    val coordinate: String,
    override val scope: DependencyScope = DependencyScope.IMPLEMENTATION,
    override val variant: String? = null,
    val exclusions: List<Exclusion> = emptyList(),
) : ExternalDependency

/** A dependency on another module of the same project, by [ExternalModule.name]. */
data class ExternalModuleRef(
    val moduleName: String,
    override val scope: DependencyScope = DependencyScope.IMPLEMENTATION,
    override val variant: String? = null,
) : ExternalDependency

/** An imported BOM (Gradle's `platform(...)`): a version source, never a classpath entry. */
data class ExternalPlatform(
    val bom: Coordinate,
    override val scope: DependencyScope = DependencyScope.IMPLEMENTATION,
    override val variant: String? = null,
) : ExternalDependency

/** A facet as `module.toml` would hold it: the table name plus TOML-representable values. Decoded by the
 *  facet codec registered for [table] (`platform.facetCodec`), so an importer needs no facet class. */
data class ExternalFacet(val table: String, val values: Map<String, Any?>)

data class ExternalRepository(val name: String, val url: String)

// ---------------------------------------------------------------------------
// Writing declarations back to the build files
// ---------------------------------------------------------------------------

/** Outcome of a build-file edit. [message] is user-facing; [file] is what changed, for the editor to reload. */
data class WriteOutcome(val ok: Boolean, val message: String = "", val file: Path? = null) {
    companion object {
        fun ok(file: Path?, message: String = ""): WriteOutcome = WriteOutcome(true, message, file)
        fun failed(message: String): WriteOutcome = WriteOutcome(false, message)
    }
}

/**
 * Edits a foreign build system's files so a declaration the user makes in the IDE survives the next sync.
 * Required for an [ModelOwnership.EXTERNAL] project to be editable: without one, the host still applies the
 * change to the model (so the classpath works now) but warns that the next sync re-derives from the build
 * files and will drop it.
 */
interface BuildFileWriter {
    /** The build system this writer edits; matched against the project's `buildSystemId`. */
    val id: BuildSystemId

    /** Declare [coordinate] on [module] at [scope]. */
    fun addDependency(module: Module, coordinate: Coordinate, scope: DependencyScope): WriteOutcome

    /** Drop [module]'s declaration of [coordinate] (matched on `group:name`, any version). */
    fun removeDependency(module: Module, coordinate: Coordinate): WriteOutcome
}

/** Plugins contribute build-file writers here; the host picks the one matching a project's build system. */
val BUILD_FILE_WRITER_EP = ExtensionPoint<BuildFileWriter>("platform.buildFileWriter")
