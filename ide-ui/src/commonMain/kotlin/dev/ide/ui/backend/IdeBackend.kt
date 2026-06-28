package dev.ide.ui.backend

/**
 * Thrown by [IdeBackend.analyze] when a higher-priority call (code completion) preempted the analysis pass
 * before it finished — they share one serialized engine thread. The buffer's diagnostics are left unchanged;
 * the caller should retry shortly (once the interactive call frees the engine), so a settled buffer still
 * gets a full pass.
 */
class AnalysisPreempted : RuntimeException("analysis preempted by a higher-priority engine call")

/**
 * The seam between the reusable Compose UI and whatever drives it. The UI never touches IdeServices,
 * the project model, JDT, or `java.nio` directly — it talks only to this port, in platform-neutral
 * terms (paths are opaque strings). The desktop host implements it over IdeServices; an Android host
 * would implement the same contract.
 */
interface IdeBackend {
    /** The active project's identity (name, root, module count) — the one cross-cutting field on the root. */
    val project: ProjectInfo

    /** Files & VFS: the workspace tree and file/directory operations. */
    val files: FileService

    /** Editor language services: completion, analysis, hints, navigation, rename, code actions. */
    val editor: EditorService

    /** The projectional (block) editor. */
    val blocks: BlockService

    /** Resource + Compose preview rendering. */
    val preview: PreviewService

    /** Indexing status + symbol/member/text search. */
    val search: SearchService

    /** Build & run: console state, run tasks, interactive I/O, and the run sandbox prompts. */
    val build: BuildService

    /** Dependency management (Maven, local libraries, repositories). */
    val deps: DependencyService

    /** Module configuration + management (source roots, facets, add/remove modules). */
    val modules: ModuleService

    /** Signing keystores: create/import/validate/manage, and assign a keystore to a build type. */
    val signing: SigningService

    /** Project management (the picker, create/open/delete, templates, open-tab session). */
    val projects: ProjectService

    /** The SDK manager (Android SDK + JDK sources/docs). */
    val sdk: SdkService

    /** IDE settings, settings pages, inspections, and app preferences. */
    val settings: SettingsService

    /** The IntelliJ-style UI action surface (toolbar / menus / command palette). */
    val actions: ActionService

    /** The critical-error dialog, the logs viewer, and opt-in analytics. */
    val diagnostics: DiagnosticsService
}

// ---- logging DTOs ----

/**
 * One in-memory log record for the Logs viewer. [level] is `DEBUG`/`INFO`/`WARN`/`ERROR`; [stackTrace] is
 * present when the record carried an exception (the viewer lets the user expand it). [timestampMs] is
 * wall-clock (epoch millis) for grouping by time; [tag] names the subsystem (`ide.editor`, `ide.backend`…).
 */
data class UiLogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val timestampMs: Long,
    /** Host-formatted local time-of-day label (e.g. `14:03:21.482`) — the UI is platform-neutral. */
    val timeLabel: String,
    val thread: String,
    val stackTrace: String? = null,
)

// ---- editor-session DTOs ----

/** The editor tabs persisted for a project: the open file paths in tab order + the active tab's index (-1 if none). */
data class UiOpenTabs(val paths: List<String> = emptyList(), val activeIndex: Int = -1)

// ---- project-management DTOs ----

/** A project template surfaced in the Create-Project gallery; [parameters] drive the configure form. */
data class UiProjectTemplate(
    val id: String,
    val displayName: String,
    val description: String,
    /** "Android" | "Java" | "Other" — buckets the gallery. */
    val category: String,
    /** Icon id resolved by `TreeIcons` (e.g. `module.android`, `java`). */
    val iconId: String,
    val parameters: List<UiTemplateParam>,
)

/** One configurable input of a template, neutral to the model layer that produced it. */
sealed interface UiTemplateParam {
    val key: String
    val label: String
    val help: String?

    data class Text(
        override val key: String,
        override val label: String,
        val default: String = "",
        val placeholder: String = "",
        /** "none" | "identifier" | "package" | "project" — drives client-side validation. */
        val validation: String = "none",
        override val help: String? = null,
    ) : UiTemplateParam

    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val defaultIndex: Int = 0,
        override val help: String? = null,
    ) : UiTemplateParam {
        data class Option(val value: String, val label: String)
    }

    data class Toggle(
        override val key: String,
        override val label: String,
        val default: Boolean = false,
        override val help: String? = null,
    ) : UiTemplateParam
}

/** Outcome of a create: [success] + a human message (the reason on failure) + the new project's root path. */
data class UiProjectResult(val success: Boolean, val message: String, val rootPath: String? = null)

/**
 * A `@Preview @Composable` target (one variant) in the open editor file. A function with several `@Preview`
 * annotations, a MultiPreview annotation, or `@PreviewParameter` expands to several of these. [variantId]
 * uniquely identifies the variant for selection; [functionName] + [arity] key the render.
 */
data class UiComposePreview(
    val functionName: String,
    val offset: Int,
    val variantId: String = functionName,
    val label: String = functionName,
    val group: String? = null,
    val arity: Int = 0,
    val config: UiPreviewConfig = UiPreviewConfig(),
    /** This preview's parameter is fed by a `@PreviewParameter` provider (rendered for each sample value). */
    val hasParameter: Boolean = false,
)

/**
 * The render-affecting `@Preview` arguments the preview surface honors (a UI-facing projection of the parsed
 * annotation). Null/absent means "keep the surface default". [nightMode] is the derived night bit of `uiMode`.
 */
data class UiPreviewConfig(
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    val showBackground: Boolean = false,
    val backgroundColor: Long? = null,
    val fontScale: Float? = null,
    val nightMode: Boolean? = null,
    val locale: String? = null,
    val apiLevel: Int? = null,
    val showSystemUi: Boolean = false,
    val device: String? = null,
)

/** The outcome of running a Compose preview: [ok] = interpretable/rendered; [message] explains the status. */
data class UiPreviewResult(val ok: Boolean, val message: String)

// ---- dependency-management DTOs ----

/** Live resolve progress, mirrored from the engine (mirrors [BuildState]'s role for the console).
 *  [log] is a bounded, ordered history of resolution activity (POMs walked, artifacts downloaded), surfaced
 *  when the user expands the editor's resolve bar. */
data class DepsResolveState(
    val resolving: Boolean = false,
    val message: String = "",
    val fraction: Double = -1.0,
    val log: List<String> = emptyList(),
    /**
     * Declared dependencies that currently have no resolved artifact on disk — the project's persistent
     * dependency-health error state (kept after [resolving] goes false, unlike [message]/[fraction]). When
     * non-empty the UI surfaces a project-level error banner and builds of the affected modules are blocked
     * until it's resolved. Each carries a best-effort [UiUnresolvedDependency.reason].
     */
    val unresolved: List<UiUnresolvedDependency> = emptyList(),
)

/** A declared dependency the engine couldn't resolve, with the module that declares it and a why. */
data class UiUnresolvedDependency(
    val module: String,
    val coordinate: String,
    val reason: String,
)

/** A dependency-declaring module for the screen's module switcher. */
data class UiDepModule(
    val name: String,
    val buildSystem: String,
    /** True when the module accepts Android archives (`.aar`) — i.e. it's an Android app/library module. */
    val acceptsAar: Boolean,
    val dependencyCount: Int,
)

/** A repository search hit, pre-judged for compatibility with the target module. */
data class UiArtifactHit(
    val coordinate: String,        // "group:name:version"
    val packaging: String,         // "jar" | "aar" | "pom" | …
    val compatible: Boolean,
    val incompatibleReason: String? = null,
)

/** What a node in the dependency graph *is* — drives its icon and the compatibility rules. */
enum class UiDepKind { Jar, Aar, Module, Sdk, Platform }

/**
 * One node in a module's dependency picture — a declared dependency or one pulled in transitively. The
 * graph/tree is reconstructed from [children] (the coordinates this node depends on); the same node list
 * backs the flat List view, the Tree view (expanding [declared] roots through [children]), and the Graph.
 */
data class UiDependencyNode(
    val coordinate: String,        // "group:name:version" (or the module name for a module dependency)
    val group: String,
    val name: String,
    val version: String,
    val kind: UiDepKind,
    /** True for a directly-declared dependency; false when it's only present transitively. */
    val declared: Boolean,
    /** The declared scope (e.g. `implementation`), for declared entries; null for transitives. */
    val scope: String? = null,
    val compatible: Boolean = true,
    val incompatibleReason: String? = null,
    /** Its `group:name` is requested at more than one version somewhere in the graph. */
    val inConflict: Boolean = false,
    /** A file-based local library (a jar/aar with no Maven coordinate) rather than a resolved artifact. */
    val local: Boolean = false,
    val children: List<String> = emptyList(),
    /** Declared transitive exclusions (`group:name`, `*` wildcards allowed). Only set on declared roots. */
    val exclusions: List<String> = emptyList(),
)

/** A resolved version clash: [artifact] (`group:name`) was requested at [requested]; [chosen] won. */
data class UiVersionConflict(val artifact: String, val requested: List<String>, val chosen: String)

/** Everything the Dependencies screen renders for one module. */
data class UiModuleDeps(
    val moduleName: String,
    val buildSystem: String,
    val acceptsAar: Boolean,
    /** Declared dependencies in declaration order (the List/Tree view roots). */
    val declared: List<UiDependencyNode>,
    /** Every node in the resolved graph (declared + transitive), deduplicated by coordinate. */
    val nodes: List<UiDependencyNode>,
    val conflicts: List<UiVersionConflict> = emptyList(),
    /** Each entry is a cycle expressed as a coordinate path (last == first). */
    val cycles: List<List<String>> = emptyList(),
    /** Coordinates that couldn't be resolved from any repository. */
    val unresolved: List<String> = emptyList(),
)

/** Outcome of an add: [success] + a human message (the reason, when blocked) + how many artifacts landed. */
data class UiAddResult(val success: Boolean, val message: String, val added: Int = 0)

/** A runnable/assemblable task offered by the Run picker. [group] buckets them (e.g. `run`, `android`). */
data class RunTaskOption(val id: String, val label: String, val group: String)

enum class RunStatus { Idle, Running, Succeeded, Failed }

enum class StepStatus { Pending, Running, Done, UpToDate, NoSource, Skipped, Failed }

data class BuildStepUi(val name: String, val status: StepStatus)

/**
 * A structured diagnostic streamed from a build tool (compiler / aapt2 / d8 / signer) while the build
 * runs — the build console's parallel to the editor's [UiDiagnostic]. [kind] is an open id (compiler /
 * resource / dex / packaging / …) driving the icon + grouping; [source] is the producing tool; [file]/
 * [line]/[column] point into the project when known (line/column 1-based, -1 when absent); [detail] is
 * extra context (a snippet or the raw tool line); [task] is the emitting build task.
 */
data class BuildDiagnosticUi(
    val severity: UiSeverity,
    val message: String,
    val kind: String = "generic",
    val source: String = "",
    val file: String? = null,
    val line: Int = -1,
    val column: Int = -1,
    val detail: String? = null,
    val task: String? = null,
)

/** The kind of text in a [ConsoleChunk]: program output, user-typed input (echoed back), or a runner notice. */
enum class ConsoleChunkKind { OUTPUT, INPUT, SYSTEM }

/** A run of console text of one [kind]. Adjacent same-kind chunks are coalesced by the producer. */
data class ConsoleChunk(val text: String, val kind: ConsoleChunkKind)

/** Whether an interactive run is compiling, executing, or done. */
enum class RunPhase { Building, Running, Finished }

/**
 * Live state of an interactive console run (the full-screen Run terminal) — distinct from [BuildState]
 * (build steps/diagnostics): this is the program's own stdio + lifecycle. Null until a console run starts.
 * [id] changes per run (the UI keys navigation on it); [transcript] is the program's output interleaved
 * with echoed input; [acceptsInput] is true while the program is executing and can read stdin; [exitCode]
 * is set when it finishes (null if it never started — e.g. a compile failure).
 */
data class RunConsoleUi(
    val id: Int,
    val moduleName: String,
    val mainClass: String,
    val phase: RunPhase = RunPhase.Building,
    val transcript: List<ConsoleChunk> = emptyList(),
    val acceptsInput: Boolean = false,
    val exitCode: Int? = null,
)

/** Severity of a [BuildLogLine] — drives the per-line color and the Log tab's level filter. */
enum class UiLogLevel { Debug, Info, Warn, Error }

/**
 * One line of the build's raw transcript (see [BuildState.log]) — the build console's structured log row.
 * [level] colors it and feeds the level filter; [task] is the build task that produced it (null for
 * engine/general lines) so the console can group output by task; [timeLabel] is the host-formatted local
 * time of day (e.g. `14:03:21.482`), empty when untimed.
 */
data class BuildLogLine(
    val message: String,
    val level: UiLogLevel = UiLogLevel.Info,
    val task: String? = null,
    val timeLabel: String = "",
    val timestampMs: Long = 0,
)

data class BuildState(
    val status: RunStatus = RunStatus.Idle,
    val moduleName: String = "",
    val steps: List<BuildStepUi> = emptyList(),
    /** The raw transcript, oldest first — leveled, time-stamped, and task-attributed (see [BuildLogLine]).
     *  The console groups it by task and filters by level/text; [diagnostics] is the structured layer over it. */
    val log: List<BuildLogLine> = emptyList(),
    /** Structured diagnostics, appended live as tools report them (see [BuildDiagnosticUi]). The raw text
     *  transcript still lives in [log]; this is the typed layer a UI groups, counts, and links from. */
    val diagnostics: List<BuildDiagnosticUi> = emptyList(),
    val elapsedMs: Long = 0,
    /** An informational notice shown above the step graph (e.g. the first-build dex-cache warning). */
    val banner: String? = null,
)

/** A running program asking permission for a guarded operation. [category] is a guard-category id
 *  (network / file_read / file_write / reflection / exec); [detail] is the concrete target (host/path/…). */
data class UiPermissionRequest(val id: Int, val category: String, val detail: String)

/** The user's answer to a [UiPermissionRequest]: deny, or allow for this one call / this run / always (persisted). */
enum class UiPermissionDecision { DENY, ALLOW_ONCE, ALLOW_RUN, ALLOW_ALWAYS }

/**
 * An unexpected error surfaced as a non-fatal dialog (IntelliJ "Internal Error" style). [title] is a short
 * heading (the exception type), [message] a one-line summary, [detail] the full stack trace text for the
 * expandable section (local display only — what we *send* for analytics is separately scrubbed), and
 * [timeLabel] when it happened. [id] round-trips back through [IdeBackend.dismissError].
 */
data class UiError(
    val id: Int,
    val title: String,
    val message: String,
    val detail: String,
    val timeLabel: String,
)

/** State of one unit of indexing work, mirrored from the engine for the index-status dialog. */
enum class IndexWorkState { PENDING, ACTIVE, DONE }

/** One unit of indexing work shown in the index-status dialog: a library/SDK artifact, or the source file
 *  currently being scanned. */
data class IndexWorkItem(val label: String, val state: IndexWorkState = IndexWorkState.PENDING)

data class IndexUiStatus(
    val building: Boolean = false,
    val message: String = "",
    val fraction: Double = -1.0,
    /** The phase currently running ("Libraries & SDK", "Project source"), for the index-status dialog. */
    val phase: String = "",
    /** The worklist for the index-status dialog (library/SDK artifacts + the current source file). Empty when idle. */
    val items: List<IndexWorkItem> = emptyList(),
    /** Units finished / total in the current phase (0 total ⇒ unknown). */
    val processed: Int = 0,
    val total: Int = 0,
)

/** A search hit. For go-to-symbol [filePath]/[offset] are set (navigable); for members they're null. */
data class SymbolHit(
    val name: String,
    val detail: String,
    val kind: String,
    val filePath: String? = null,
    val offset: Int? = null,
)

/** Find-in-files options. Default is a case-insensitive literal substring search. */
data class UiSearchOptions(
    val caseSensitive: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/**
 * One full-text match: [filePath]/[fileName] for grouping, the 1-based [line]/[col], the matched line's
 * [lineText] with the hit at `[matchStart, matchEnd)` (column indices into [lineText]), and the absolute
 * document [offset] the editor navigates to.
 */
data class UiTextMatch(
    val filePath: String,
    val fileName: String,
    val line: Int,
    val col: Int,
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int,
    val offset: Int,
)

// ---- module-configuration DTOs (the Module Settings editor) ----

/** A configurable module for the settings screen's switcher. */
data class UiModuleRef(val name: String, val typeDisplay: String)

/** A Maven repository libraries resolve from. [builtin] repos (Maven Central, Google) can't be removed. */
data class UiRepository(val name: String, val url: String, val builtin: Boolean)

/**
 * A module type a new module can be created as, for the New-Module dialog. [defaultFacets] are the
 * starter facet panels (codec-derived, editable) the dialog prefills; [languageLevels] are the selectable
 * Java levels with [defaultLanguageLevel] preselected.
 */
data class UiModuleTypeOption(
    val id: String,
    val displayName: String,
    val languageLevels: List<String>,
    val defaultLanguageLevel: String,
    val defaultFacets: List<UiFacetConfig>,
)

/**
 * The editable configuration of one module. [facets] is rendered as one collapsible panel each; its
 * [UiConfigField]s are derived from the facet codec's value shape, so the renderer is generic.
 */
data class UiModuleConfig(
    val name: String,
    val typeId: String,
    val typeDisplay: String,
    val languageLevel: String,
    /** The selectable language-level options (enum names). */
    val languageLevels: List<String>,
    val outputDir: String,
    val sourceSets: List<UiSourceSetInfo>,
    val facets: List<UiFacetConfig>,
)

data class UiSourceSetInfo(val name: String, val scope: String, val roots: List<String>)

/** One facet panel (e.g. the `[android]` table) with a generic, codec-derived field list. */
data class UiFacetConfig(val table: String, val title: String, val fields: List<UiConfigField>)

/** A configurable field, typed by the underlying value so the UI picks the right control. */
sealed interface UiConfigField {
    val key: String
    val label: String

    data class Text(override val key: String, override val label: String, val value: String) : UiConfigField
    data class Bool(override val key: String, override val label: String, val value: Boolean) : UiConfigField
    data class Number(override val key: String, override val label: String, val value: Long) : UiConfigField
    data class StringList(override val key: String, override val label: String, val values: List<String>) : UiConfigField
    /** A repeatable inline-table list (Android build types / product flavors): each row is its own field list. */
    data class TableList(override val key: String, override val label: String, val rows: List<List<UiConfigField>>) : UiConfigField
}

/**
 * The changes to persist. [facetValues] maps a facet table (e.g. `"android"`) to the full value map the
 * facet codec decodes — the UI rebuilds it from the (possibly edited) [UiConfigField]s.
 */
data class UiModuleConfigEdit(
    val languageLevel: String? = null,
    val facetValues: Map<String, Map<String, Any?>> = emptyMap(),
)

data class UiConfigResult(val success: Boolean, val message: String)

/**
 * The Android `buildFeatures` of one module — the toggles shown on the Build Features tab. Null when the
 * module is not an Android module (the tab then explains build features apply only to Android modules).
 */
data class UiBuildFeatures(val moduleName: String, val features: List<UiBuildFeature>)

/** One toggleable build feature. [note] is an optional aside (e.g. that enabling it adds dependencies). */
data class UiBuildFeature(
    val id: String,
    val title: String,
    val description: String,
    val enabled: Boolean,
    val note: String? = null,
)

// ---------------------------------------------------------------------------
// Signing keystores
// ---------------------------------------------------------------------------

/** A registered signing keystore, with a best-effort summary of its key certificate (null if unreadable). */
data class UiKeystore(
    val id: String,
    val name: String,
    val fileName: String,
    val keyAlias: String,
    val certSubject: String?,
    val sha256: String?,
    val validUntilEpochMs: Long?,
)

/** One certificate inside a keystore (owner/issuer, validity window, fingerprints). */
data class UiKeystoreCert(
    val alias: String,
    val subject: String,
    val issuer: String,
    val validFromEpochMs: Long,
    val validUntilEpochMs: Long,
    val sha1: String,
    val sha256: String,
)

/** The result of opening a keystore file: its aliases + certs when valid, or the error (e.g. wrong password). */
data class UiKeystoreValidation(
    val valid: Boolean,
    val aliases: List<String>,
    val certs: List<UiKeystoreCert>,
    val error: String?,
)

/**
 * The fields for creating a new keystore (keypair + self-signed cert). Only [commonName] is required.
 * PKCS12 uses one password for both the store and the key, so [storePass] is it.
 */
data class UiKeystoreSpec(
    val name: String,
    val storePass: String,
    val keyAlias: String,
    val commonName: String,
    val organization: String? = null,
    val organizationalUnit: String? = null,
    val locality: String? = null,
    val state: String? = null,
    val country: String? = null,
    val validityYears: Int = 25,
)

data class UiKeystoreResult(val success: Boolean, val message: String, val keystoreId: String? = null)

/** A build type and the keystore assigned to sign it (null keystoreId ⇒ the default debug keystore). */
data class UiSigningAssignment(val buildType: String, val keystoreId: String?, val keystoreName: String?)

/** A module's per-build-type signing assignments plus the keystores available to assign. Null ⇒ not Android. */
data class UiSigningAssignments(
    val moduleName: String,
    val keystores: List<UiKeystore>,
    val assignments: List<UiSigningAssignment>,
)

/**
 * A keep-rule file an Android build type references that is module-relative and missing on disk (so R8
 * skips it). [buildType] is the build type that names it, [entry] the path as written in the model, and
 * [consumer] true when it came from `consumerProguardFiles` (an AAR-export rule) rather than `proguardFiles`.
 */
data class UiMissingProguardFile(val buildType: String, val entry: String, val consumer: Boolean)

data class ProjectInfo(
    val name: String,
    val rootPath: String,
    val moduleCount: Int,
    /** True when the project was imported from Gradle and runs in compatibility mode (may not be fully supported). */
    val compatibility: Boolean = false,
)

/** How the project tree is shaped — a curated module view, or the raw filesystem (see [IdeBackend.fileTree]). */
enum class TreeViewMode { Project, AllFiles }

/** The kind of a content/source root the user can add to a module (maps to a backend `ContentRole`). */
enum class UiSourceRootRole { Source, Resource, AndroidRes, Assets, Aidl }

/**
 * A typed source-file template the file-tree "New" submenu can scaffold. The prefix names the language
 * (Java → `.java`, Kotlin → `.kt`); the backend prepends the package resolved from the target directory.
 */
enum class UiNewFileTemplate {
    JavaClass, JavaInterface, JavaEnum, JavaAbstractClass, JavaAnnotation,
    KotlinFile, KotlinClass, KotlinInterface, KotlinDataClass, KotlinEnum, KotlinObject,
}

/** Structural role of a tree node. The *icon* is chosen separately via [TreeNode.iconId]. */
enum class NodeKind { Workspace, Module, SourceRoot, Package, Folder, File }

enum class GitStatus { Added, Modified, Deleted, Untracked }

data class TreeNode(
    val id: String,
    val name: String,
    val kind: NodeKind,
    /** Absolute path if this node is an openable file; null for grouping nodes. */
    val filePath: String?,
    /** Icon id resolved by the UI's `TreeIcons` registry (e.g. `java`, `sourceset.android-res`, `package`). */
    val iconId: String = "file",
    val children: List<TreeNode> = emptyList(),
    val gitStatus: GitStatus? = null,
    /** The owning Java/Kotlin source root, for `SourceRoot`/`Package` nodes; null elsewhere. New-file targets. */
    val sourceRootPath: String? = null,
    /**
     * For a (possibly compacted) `Package` node, one entry per package level — its dotted package name and
     * directory — so a New-Class action can target any intermediate level (e.g. `com.tyron` inside a
     * collapsed `com.tyron.codeassist`). Empty for non-package nodes.
     */
    val packageSegments: List<PackageSegment> = emptyList(),
    /**
     * For an Android `res/` folder node (or the `res/` root): the directory a new XML resource would be
     * created in. Non-null marks an XML new-file target — the counterpart to [sourceRootPath] for Java.
     */
    val resDirPath: String? = null,
    /**
     * When non-null, opening this node opens the Module Settings editor for the named module instead of a
     * text editor — set on a `module.toml` file (and the module node itself). The host only sets it for a
     * module that actually resolves, so the UI can route on it without re-validating.
     */
    val moduleConfigName: String? = null,
    /**
     * The on-disk directory this node represents (workspace/module/source-root/package/folder), so the UI
     * can create a new file or folder *anywhere* in the tree — into this dir for a directory node, or into a
     * file node's parent. Null only when no directory applies.
     */
    val dirPath: String? = null,
    /**
     * An optional rendering hint that dims/recolors the row, IntelliJ-style. Currently `"excluded"` — used
     * for derived build output (the curated *build outputs* node, the raw `build/` dir and everything under
     * it) so it reads as generated-not-source. Null = the default appearance.
     */
    val styleHint: String? = null,
)

/**
 * One level of a multi-segment tree row: its cumulative label ([packageName] — a dotted package like
 * `com.example`, or a path level like `app/src` for a source-root row) and the [dirPath] backing it.
 */
data class PackageSegment(val packageName: String, val dirPath: String)

/**
 * One entry in the mobile move/copy directory browser: a subdirectory to descend into ([isDirectory] true)
 * or a file shown for context. [iconId] resolves via the UI's `TreeIcons` registry (e.g. `folder`, `java`).
 */
data class UiDirEntry(val name: String, val path: String, val isDirectory: Boolean, val iconId: String = "file")

enum class UiCompletionKind {
    Class, Interface, Enum, AnnotationType, Record,
    Method, Constructor, Field, EnumConstant,
    Variable, Parameter, TypeParameter,
    Package, Keyword, Snippet,
    /** A word lifted from the current buffer (hippie/word completion). */
    Word,
}

data class UiCompletionItem(
    val label: String,
    val insertText: String,
    val detail: String?,
    /** Right-aligned origin shown on the row: a type's package, or a member's declaring class. Null hides it. */
    val container: String? = null,
    /** Documentation (javadoc) for the side doc panel; null when none is known. */
    val documentation: String? = null,
    val kind: UiCompletionKind,
    val sortPriority: Int,
    /** Extra edits to apply on accept beyond the main insertion — e.g. an auto-`import`. */
    val additionalEdits: List<UiTextEdit> = emptyList(),
    /** Where the caret lands after accept, relative to [insertText]; null ⇒ end of the insertion. */
    val caret: UiCaret? = null,
    /**
     * Present for snippet/postfix items: tab stops to step through after the [insertText] is inserted, all
     * offsets relative to it. When set the editor drives a snippet session (Tab/Shift-Tab/Escape) instead of
     * just placing [caret]; [caret] still holds a sensible fallback (the first stop) for callers that don't.
     */
    val snippet: UiSnippet? = null,
)

/** A snippet to drive after accept: ordered tab [stops] (offsets relative to the inserted text) + the final
 *  caret. A stop with more than one range is linked (mirrored) — the same placeholder in several places. */
data class UiSnippet(val stops: List<UiSnippetStop>, val finalCaretOffset: Int)

/** One tab stop: [index] (0 = final caret, otherwise visit order), its [ranges] (>1 ⇒ linked), and any
 *  choice [choices] to offer. Each range is `[start, end)` relative to the inserted text. */
data class UiSnippetStop(val index: Int, val ranges: List<UiTextRange>, val choices: List<String> = emptyList())

/** A half-open `[start, end)` span. */
data class UiTextRange(val start: Int, val end: Int)

/** A text edit: replace [start]..[end] with [newText]. */
data class UiTextEdit(val start: Int, val end: Int, val newText: String)

/**
 * Post-accept caret placement, neutral to the language that produced it: put the caret [offset] chars into
 * the inserted text and, if [selectionLength] > 0, select that many chars from there (a placeholder to
 * overtype). The editor clamps both into the inserted range.
 */
data class UiCaret(val offset: Int, val selectionLength: Int = 0)

/** Items already ranked; [replaceStart]..[replaceEnd] is the partial identifier an accept replaces. */
data class UiCompletionResult(
    val items: List<UiCompletionItem>,
    val replaceStart: Int,
    val replaceEnd: Int,
    /**
     * Whether the editor may narrow this set locally as the user types more of the identifier, instead
     * of waiting for a fresh query. True for ordinary prefix completion (the overwhelming case): the items
     * are the full candidate set for the token at [replaceStart], so the editor can keep the popup live and
     * filter it in-place while a slow provider catches up — no close/reopen flicker. A provider should set
     * this to false when its result is not a stable prefix set (e.g. server-side fuzzy ranking, or a
     * context-sensitive result that changes meaning as the token grows), forcing a re-query on each edit.
     */
    val mayFilterLocally: Boolean = true,
)

/** A go-to-definition target: open [path] and move the caret to [offset]. */
data class UiDefinition(val path: String, val offset: Int)

/** The renameable symbol under the caret: its current [oldName] and a human [kind] label (e.g. "method"). */
data class UiRenameTarget(val oldName: String, val kind: String)

/** Outcome of a project-wide rename. [newPath] is set when the edited file was itself renamed (reopen it). */
data class UiRenameResult(
    val success: Boolean,
    val message: String,
    val occurrences: Int = 0,
    val filesChanged: Int = 0,
    val newPath: String? = null,
)

/** Whether an action fixes a problem ([QUICK_FIX]), is a context action with no diagnostic ([INTENTION]), or refactors ([REFACTOR]). Drives the menu icon. */
enum class UiActionKind { QUICK_FIX, INTENTION, REFACTOR }

/** One entry in the editor's code-action menu. [id] is the stable index used to round-trip via [IdeBackend.applyAction]. */
data class UiAction(val id: Int, val title: String, val kind: UiActionKind)

// ---- UI action DTOs (toolbar / menu / palette; distinct from the editor's code-action [UiAction]) ----

/** The built-in action places, by their string id. A plugin (or a new surface) may use its own id too. */
object UiActionPlaces {
    const val MAIN_TOOLBAR = "mainToolbar"
    const val MAIN_OVERFLOW = "mainToolbar.overflow"
    const val MORE_MENU = "moreMenu"
    const val FILE_CONTEXT = "fileContext"
    const val EDITOR_TAB = "editorTab"
    const val COMMAND_PALETTE = "commandPalette"
}

/**
 * What an action is being resolved/invoked against — a neutral snapshot the UI builds and the host expands.
 * [place] is one of [UiActionPlaces]; [contextPath] is the tree/tab node a context menu opened on.
 */
data class UiActionContext(
    val place: String,
    val activeFilePath: String? = null,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null,
    val contextPath: String? = null,
)

/** A resolved action ready to render. [id] round-trips through [IdeBackend.invokeAction]; [iconId] resolves
 *  via the UI icon registry; [enabled] is pre-evaluated for the context it was resolved in. */
data class UiActionItem(
    val id: String,
    val text: String,
    val iconId: String? = null,
    val enabled: Boolean = true,
)

/** A resolved context menu: a flat, ordered list of [UiMenuNode]s (items, submenus, separators). */
data class UiMenuGroup(val items: List<UiMenuNode> = emptyList())

/** One node in a resolved menu tree. */
sealed interface UiMenuNode {
    /** A leaf action. */
    data class Item(val action: UiActionItem) : UiMenuNode
    /** A nested submenu. */
    data class Submenu(val text: String, val iconId: String?, val items: List<UiMenuNode>) : UiMenuNode
    /** A divider. */
    data object Separator : UiMenuNode
}

/** The outcome of [IdeBackend.invokeAction]: a status [message] to surface, plus [effects] the UI applies. */
data class UiActionResult(
    val message: String? = null,
    val effects: List<UiActionEffect> = emptyList(),
)

/** A neutral instruction an action returns for the UI to carry out; the UI ignores effects it can't honor. */
sealed interface UiActionEffect {
    /** Open [path] in the editor, optionally moving the caret to [offset]. */
    data class OpenFile(val path: String, val offset: Int? = null) : UiActionEffect
    /** Navigate to a named UI destination (a screen / tool-window id). */
    data class Navigate(val target: String) : UiActionEffect
    /** Re-read the file tree. */
    data object RefreshTree : UiActionEffect
    /** Re-read [path] from disk into any open editor showing it. */
    data class ReloadFile(val path: String) : UiActionEffect
}

/**
 * One classified identifier from semantic highlighting: its `[startOffset, endOffset)` span, an open
 * string [kind] id (`class`/`method`/`property`/`parameter`/`localVariable`/… — the UI maps known ids to a
 * base color, an unknown id falls back), and orthogonal [modifiers] layered as color tweaks + font styles.
 */
data class UiSemanticToken(
    val startOffset: Int,
    val endOffset: Int,
    val kind: String,
    val modifiers: Set<UiHighlightModifier> = emptySet(),
)

/** Orthogonal facts a [UiSemanticToken] can carry — the editor layers these over the base kind color. */
enum class UiHighlightModifier {
    Declaration, Static, Abstract, Deprecated, Readonly, Mutable, Extension, Composable, Suspend,
}

/**
 * One foldable region: the `[startOffset, endOffset)` span to collapse, the [placeholder] shown in its place
 * (`...`, `import ...`, `/*...*/`), an open string [kind] id (`imports`/`functionBody`/`classBody`/`comment`/…
 * — the UI treats an unknown kind generically), and whether it [collapsedByDefault] (imports) when the file
 * first opens.
 */
data class UiFoldRegion(
    val startOffset: Int,
    val endOffset: Int,
    val placeholder: String,
    val kind: String,
    val collapsedByDefault: Boolean = false,
)

/** What an inlay hint conveys — drives its tint/affinity. */
enum class UiInlayKind { Type, Parameter, Chaining, Other }

/** One run of an inlay hint's label; [navOffset] (when set) is a document offset to jump to on click. */
data class UiInlayPart(val text: String, val navOffset: Int? = null)

/**
 * An inline annotation rendered between characters at [offset] (it does not change the document): an inferred
 * `var`/lambda type, a call-site parameter name, a fluent-chain type. [paddingLeft]/[paddingRight] add a thin
 * gap so it reads as separate from the code.
 */
data class UiInlayHint(
    val offset: Int,
    val parts: List<UiInlayPart>,
    val kind: UiInlayKind,
    val tooltip: String? = null,
    val paddingLeft: Boolean = false,
    val paddingRight: Boolean = false,
) {
    val text: String get() = parts.joinToString("") { it.text }
}

/** One parameter within a [UiSignature]; [start]/[end] are its `[start, end)` offsets into [UiSignature.label]
 *  (for highlighting), both -1 when not located. */
data class UiSignatureParam(val label: String, val start: Int = -1, val end: Int = -1)

/** One overload's rendered signature: the whole [label] (e.g. `format(String fmt, Object... args)`) plus each
 *  parameter's sub-range, and an optional per-overload [activeParameter] override (varargs). */
data class UiSignature(
    val label: String,
    val parameters: List<UiSignatureParam>,
    val documentation: String? = null,
    val activeParameter: Int? = null,
)

/** Parameter-info for the call at the caret: the applicable overloads, which one is active, and the index of
 *  the argument the caret is in (the parameter to highlight). */
data class UiSignatureHelp(
    val signatures: List<UiSignature>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
)

/** Android platform-sources status: the [platform] (e.g. "android-36"), whether sources are [installed],
 *  and whether they're [downloadable] (an sdkmanager is present). */
data class UiAndroidSourcesInfo(val platform: String, val installed: Boolean, val downloadable: Boolean)

/** Aggregate SDK/JDK download progress. [busy]/[message]/[fraction] summarise the active work (fraction < 0
 *  ⇒ indeterminate); [downloads] is the per-item queue (active + recently finished). */
data class UiSdkManagerState(
    val busy: Boolean = false,
    val message: String = "",
    val fraction: Double = -1.0,
    val downloads: List<UiSdkDownload> = emptyList(),
)

/** One queued/in-flight/finished download. [id] is the package path or `jdk-<feature>`;
 *  [status] ∈ {DOWNLOADING, EXTRACTING, INSTALLING, DONE, FAILED}; [fraction] < 0 ⇒ indeterminate. */
data class UiSdkDownload(
    val id: String,
    val label: String,
    val status: String,
    val fraction: Double = -1.0,
    val detail: String = "",
)

/** An installable Android SDK package. [category] is always SOURCES (the SDK Manager is sources/docs only).
 *  [incomplete] ⇒ a previous install was interrupted; re-installing resumes/repairs it. */
data class UiSdkPackage(
    val path: String,
    val displayName: String,
    val category: String,
    val sizeBytes: Long,
    val installed: Boolean,
    val installable: Boolean,
    val incomplete: Boolean = false,
)

/** The active JDK and whether its sources are available for docs. */
data class UiJdkInfo(val home: String, val version: String, val srcZip: String?)

enum class UiSeverity { Error, Warning, Info, Hint }

// ---- settings DTOs (the Settings screen) ----

/**
 * App-global IDE settings, mirrored to/from the engine's `IdeSettings` — appearance, editor, completion, and
 * analysis *behaviour* (the same for every project). Per-project settings (inspections, conflict policy,
 * repositories) have their own methods. Neutral primitives only; the bounds match what the sliders enforce.
 */
data class UiSettings(
    /** "light" | "dark" | "system". */
    val themeMode: String = "dark",
    val accent: UiAccent = UiAccent.Violet,
    val editorFontScale: Float = 1f,
    /** "jetbrains" (bundled JetBrains Mono) | "monospace" (system monospace). */
    val codeFont: String = "jetbrains",
    /** Render programming ligatures (`->`, `!=`, `>=`, …) when the code font provides them (on by default). */
    val fontLigatures: Boolean = true,
    val inlayHints: Boolean = true,
    val semanticHighlighting: Boolean = true,
    val codeFolding: Boolean = true,
    val completionAutoPopup: Boolean = true,
    val completionDelayMs: Int = 110,
    val completionMaxItems: Int = 200,
    val postfixTemplates: Boolean = true,
    val wordCompletion: Boolean = true,
    val analyzeOnTheFly: Boolean = true,
    val reparseDelayMs: Int = 300,
    /** Soft-wrap long lines at the viewport edge (off = one row per line + horizontal scroll). */
    val wordWrap: Boolean = false,
    /** Indent wrapped continuation rows to the line's own indent (IntelliJ-style); only when [wordWrap]. */
    val wrapIndent: Boolean = true,
    /** Free (two-axis) touch scrolling: a single drag pans both axes (off = orientation-locked). */
    val twoAxisScroll: Boolean = true,
    /** Two-finger pinch zooms the code font. */
    val pinchZoom: Boolean = true,
) {
    companion object {
        const val MIN_FONT_SCALE = 0.7f
        const val MAX_FONT_SCALE = 2.0f
        const val MIN_COMPLETION_DELAY_MS = 0
        const val MAX_COMPLETION_DELAY_MS = 1000
        const val MIN_COMPLETION_MAX_ITEMS = 10
        const val MAX_COMPLETION_MAX_ITEMS = 500
        const val MIN_REPARSE_DELAY_MS = 0
        const val MAX_REPARSE_DELAY_MS = 2000
    }
}

/** The theme accent swaps the design system ships. */
enum class UiAccent { Violet, Teal }

/**
 * One inspection (analyzer) in the Analysis settings list. [enabled] off = the check never runs; [severity]
 * is its effective level (overridable from [defaultSeverity]). [language] is a display tag
 * ("Java"/"Kotlin"/"XML"/"All"); [tier] is "Syntax"/"Semantic"/"Project" for grouping.
 */
data class UiInspection(
    val id: String,
    val displayName: String,
    val language: String,
    val tier: String,
    val enabled: Boolean,
    val severity: UiSeverity,
    val defaultSeverity: UiSeverity,
)

/**
 * One category in the Settings screen, rendered generically from [controls] — built-in and plugin pages are
 * identical here. [scope] is "app" (IDE-wide) or "project". [inspectionsSection] true appends the per-
 * inspection enable/severity list (the Analysis page). [iconId] resolves through the UI icon registry.
 */
data class UiSettingsPage(
    val id: String,
    val title: String,
    val iconId: String,
    val scope: String,
    val controls: List<UiSettingControl>,
    val inspectionsSection: Boolean = false,
)

/**
 * One control on a settings page, carrying its current value. [key] is page-local; the UI writes back via
 * [IdeBackend.setSetting] (`pageId`, `key`). [advanced] tucks it into the page's collapsible Advanced group;
 * [group] is an optional shared sub-heading.
 */
sealed interface UiSettingControl {
    val key: String
    val title: String
    val description: String?
    val advanced: Boolean
    val group: String?

    data class Toggle(
        override val key: String, override val title: String, override val description: String? = null,
        val value: Boolean = false, override val advanced: Boolean = false, override val group: String? = null,
    ) : UiSettingControl

    data class Slider(
        override val key: String, override val title: String, override val description: String? = null,
        val value: Int = 0, val min: Int = 0, val max: Int = 100, val step: Int = 1, val unit: String? = null,
        override val advanced: Boolean = false, override val group: String? = null,
    ) : UiSettingControl

    data class Choice(
        override val key: String, override val title: String, override val description: String? = null,
        val value: String = "", val options: List<Option> = emptyList(),
        override val advanced: Boolean = false, override val group: String? = null,
    ) : UiSettingControl {
        data class Option(val value: String, val label: String)
    }

    data class Text(
        override val key: String, override val title: String, override val description: String? = null,
        val value: String = "", val placeholder: String = "",
        override val advanced: Boolean = false, override val group: String? = null,
    ) : UiSettingControl

    data class Action(
        override val key: String, override val title: String, override val description: String? = null,
        val buttonLabel: String = "Run", val destructive: Boolean = false,
        override val advanced: Boolean = false, override val group: String? = null,
    ) : UiSettingControl
}

data class UiDiagnostic(
    val severity: UiSeverity,
    val line: Int,
    val col: Int,
    val message: String,
    val startOffset: Int,
    val endOffset: Int,
    /** Tagged UNUSED (e.g. an unused import) — the editor renders it muted rather than alarming. */
    val unused: Boolean = false,
)

// ---- block-based editing DTOs (neutral; the host maps its BlockTree onto these) ----

/**
 * A projected block, neutral to the language that produced it. [parts] is the render order — interleaved
 * inline [UiBlockPart.Field]s (chrome + editable tokens) and child [UiBlockPart.Slot]s. [label] is the
 * short header chip (`if`, `method`, `call`; empty = render transparently). [start]/[end] is the source
 * span (for caret correspondence). [id] is stable only within one projection.
 */
data class UiBlockNode(
    val id: String,
    val kind: String,
    val label: String,
    val category: String,
    val start: Int,
    val end: Int,
    val parts: List<UiBlockPart>,
    /**
     * The value kind this block PRODUCES as an expression — drives the typed socket/pill shape.
     * One of "boolean" | "number" | "string" | "object" | "type" | "unknown".
     */
    val valueKind: String = "unknown",
)

sealed interface UiBlockPart {
    /** An inline token. Chrome (punctuation/keywords) is `editable = false`; names/literals are editable. */
    data class Field(val role: String, val text: String, val editable: Boolean, val start: Int, val end: Int) : UiBlockPart
    /**
     * A child position. [multiple] = a list slot (statements/members) vs a single slot (a condition).
     * [valueKind] is the value kind the slot EXPECTS ("boolean" | "number" | "string" | "object" |
     * "type" | "unknown") — drives the empty-socket shape and hint.
     */
    data class Slot(val category: String, val multiple: Boolean, val start: Int, val end: Int, val children: List<UiBlockNode>, val valueKind: String = "unknown") : UiBlockPart
}

/** A structural edit the block view issues; the host compiles it to surgical [UiTextEdit]s. */
sealed interface UiBlockEdit {
    /** Set an editable field's text in place (rename, re-literal, swap an operator). */
    data class SetField(val blockId: String, val role: String, val text: String) : UiBlockEdit
    /** Replace a slot's content with raw text — type-text→blocks; reparses and re-projects. */
    data class ReplaceSlot(val blockId: String, val slotIndex: Int, val text: String) : UiBlockEdit
    /** Delete a block (and the syntax that only held it — a trailing `;`/newline). */
    data class DeleteBlock(val blockId: String) : UiBlockEdit
    /** Insert [text] at position [index] in the list slot [slotIndex] of [ownerBlockId] (palette / `+ block`). */
    data class InsertTemplate(val ownerBlockId: String, val slotIndex: Int, val index: Int, val text: String) : UiBlockEdit
    /** Wrap a statement block in a fresh `if (true) { … }` (the action-bar Wrap-if). */
    data class WrapInIf(val blockId: String) : UiBlockEdit
    /** Relocate [blockId] to position [toIndex] in the list slot [toSlotIndex] of [toOwnerBlockId] (drag). */
    data class MoveBlock(val blockId: String, val toOwnerBlockId: String, val toSlotIndex: Int, val toIndex: Int) : UiBlockEdit
}
