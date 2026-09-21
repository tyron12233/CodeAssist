package dev.ide.ios

import dev.ide.model.BuildSystemId
import dev.ide.model.FacetTemplate
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetCodecRegistry
import dev.ide.model.LanguageLevel
import dev.ide.model.LibraryDependency
import dev.ide.model.LibraryRef
import dev.ide.model.Module
import dev.ide.model.ModuleType
import dev.ide.model.ModuleTypeRegistry
import dev.ide.model.Project
import dev.ide.model.ProjectTemplateRegistry
import dev.ide.model.SourceSetTemplate
import dev.ide.model.impl.ModelPersistence
import dev.ide.model.impl.ProjectModel
import dev.ide.model.impl.ProjectModelStore
import dev.ide.model.impl.StoreScaffold
import dev.ide.model.bridge.DependencyModelBridge
import dev.ide.model.bridge.toUi
import dev.ide.model.template.TemplateArgs
import dev.ide.model.template.TemplateId
import dev.ide.platform.ConcurrentMap
import dev.ide.platform.Lock
import dev.ide.platform.PluginId
import dev.ide.platform.impl.PlatformCore
import dev.ide.platform.log.Log
import dev.ide.templates.JavaLibModuleType
import dev.ide.templates.BUILT_IN_KOTLIN_TEMPLATES
import dev.ide.ui.backend.UiProjectTemplate

/**
 * The project model on this host: creating one, opening one, and the open one's live store.
 *
 * Before this the host scaffolded a folder: `src/Main.kt`, a hand-written starter string, and no model at
 * all. Everything downstream then had to guess, "one module named after the directory" was an invention, and
 * a project made here opened on the desktop as an unrecognised folder. The whole model
 * (`ProjectModelStore`, its transactions, `.platform/workspace.json` + `module.toml`) and the built-in
 * Kotlin templates build for this target, so a project created on a phone is the same project the desktop
 * and Android hosts create, byte for byte.
 *
 * **The store is now HELD for the open project, not opened per operation.** Reading a module list off disk
 * was enough while the model was write-once; module settings and dependencies are edits, and an edit needs
 * the transaction surface, the lock and the event bus that only an open store has. One store at a time: it
 * is the open project's, closed when another opens.
 *
 * What it is NOT is a build. A template's job ends at the source tree and the model; compiling it is still
 * something only the JVM hosts do.
 */
internal class IosProjectModel {

    /**
     * The extension container the two registries read, and the one piece of host state here.
     *
     * Per host, not per project: a module type and a template are contributed once, and the registries are
     * views over the extension registry rather than stores of their own. It is also what every opened store
     * is built on, so the message bus and the model lock are shared across the session the way they are on
     * the other hosts.
     */
    private val platform = PlatformCore()

    private val moduleTypes = ModuleTypeRegistry(platform.extensions)
    private val templateRegistry = ProjectTemplateRegistry(platform.extensions)

    init {
        // The built-ins, registered the way `BuiltInPlugins` registers them on the JVM hosts. The Java,
        // Swing and Android templates are deliberately absent: this host has no Java editor and no Android
        // build, and offering a template whose language it cannot analyze is worse than offering nothing.
        moduleTypes.register(JavaLibModuleType, BUILT_IN)
        // The types this host RECOGNISES without being able to create or build one. A module type is
        // persisted as an id, and an id nothing claims resolves to `UnknownModuleType`, so an Android
        // project made on a desktop opened here describing itself as "Unknown module type (android-app)".
        // It is not unknown: this host simply has no Android build (and `:android-support`, which owns the
        // real types, is JVM-only). Registering the identity alone names the project correctly and changes
        // nothing else -- a module's source sets and facets come from its own `module.toml`, never from its
        // type -- and [creatableTypeIds] keeps them out of the New-Module picker, where offering one would
        // scaffold a module with no manifest that nothing here could build.
        RECOGNIZED_TYPES.forEach { moduleTypes.register(it, BUILT_IN) }
        BUILT_IN_KOTLIN_TEMPLATES.forEach { templateRegistry.register(it, BUILT_IN) }
    }

    fun templates(): List<UiProjectTemplate> = templateRegistry.all().map { it.toUi() }

    /** Every module type this host can name, including the ones it only recognises. */
    fun availableModuleTypes(): List<ModuleType> = moduleTypes.all()

    /** The ids a new module may be created as: what this host can actually scaffold. */
    fun creatableTypeIds(): Set<String> = setOf(JavaLibModuleType.id)

    /** The module type [id] names, or null when nothing registered it. */
    fun moduleType(id: String): ModuleType? = moduleTypes.byId(id)

    // ---- the open project's store -------------------------------------------------------------------

    /** The open project's root and its store, or null between projects. */
    private var session: Session? = null

    /**
     * Guards [session] alone, not the model inside it.
     *
     * The store has its own read/write lock for commits; what it does not have is an opinion about being
     * replaced. Opening a project runs from the UI while the analysis thread is still asking the previous
     * one for a classpath, and closing a store out from under that reader is what this prevents.
     */
    private val sessionLock = Lock()

    private class Session(val root: String, val store: ProjectModelStore)

    /**
     * Open [root]'s model and keep it, closing whatever was open before.
     *
     * With [prepare] (the default), two things happen on the way in, both once per project and both quiet.
     * A caller that is about to WRITE the model itself passes false, because a directory with no model yet
     * is not a project to adopt, it is one being created:
     *
     *  * a folder with no model is ADOPTED, so every host below this can assume a module graph. Projects
     *    made by earlier builds of this app are folders, as is anything unpacked from the store, and the
     *    alternative to adopting them is a second, model-less code path through dependencies and settings.
     *  * `.platform/dependencies`, this host's old flat declaration file, is MIGRATED into the module that
     *    adopted it. That file was written when there was no model to put a declaration in.
     *
     * Null when the model cannot be opened at all, which leaves the editor working off files alone rather
     * than refusing to open the project.
     */
    fun open(root: String, prepare: Boolean = true): ProjectModelStore? = sessionLock.withLock {
        session?.let { if (it.root == root) return@withLock it.store else closeLocked() }
        val store = runCatching { ProjectModel.open(root, platform, FacetCodecRegistry()) }
            .onFailure { log.error("could not open the model at $root", it) }
            .getOrNull() ?: return@withLock null
        session = Session(root, store)
        if (prepare) {
            runCatching {
                if (store.workspace.projects.isEmpty()) adopt(store)
                migrateFlatDeclarations(store)
            }.onFailure { log.warn("could not prepare the model at $root: ${it.message}") }
        }
        store
    }

    /**
     * Re-read [root]'s model from disk, discarding whatever snapshot is held.
     *
     * Opening a project is where a hand edit to `module.toml` is picked up. The store is the truth while it
     * is open (every edit goes through it), and the FILE is the truth between opens: `module.toml` is
     * deliberately hand-editable and sits in the editor like any other file, so reusing an already-open
     * snapshot for the same root would quietly ignore what the user typed into it.
     */
    fun reopen(root: String): ProjectModelStore? {
        close()
        return open(root)
    }

    /** The open project's store, or null. Never opens one: a caller with a root calls [open]. */
    fun store(): ProjectModelStore? = sessionLock.withLock { session?.store }

    /** The open store, if it is [root]'s. Guards against answering a stale project's model mid-switch. */
    fun storeFor(root: String): ProjectModelStore? =
        sessionLock.withLock { session?.takeIf { it.root == root }?.store }

    fun close() = sessionLock.withLock { closeLocked() }

    private fun closeLocked() {
        session?.let { runCatching { it.store.close() } }
        session = null
    }

    /** Every module of the open project, in model order. Empty with no project open. */
    fun modules(): List<Module> = store()?.workspace?.projects.orEmpty().flatMap { it.modules }

    /** The open project's module named [name], or null. */
    fun module(name: String): Module? = modules().firstOrNull { it.name == name }

    /** The project that owns [module] in the open workspace, or null. */
    fun projectOf(module: Module): Project? =
        store()?.workspace?.projects?.firstOrNull { p -> p.modules.any { it.id == module.id } }

    /** Persist the open model: `.platform/workspace.json`, `libraries.json`, and every `module.toml`. */
    fun save() {
        runCatching { store()?.save() }.onFailure { log.error("could not save the model", it) }
    }

    // ---- creating ------------------------------------------------------------------------------------

    /**
     * Scaffold [templateId] into [root], which must already exist and be empty.
     *
     * The store is opened as the SESSION store and left open: creating a project is immediately followed by
     * opening it, and opening what was just written by closing and re-reading it would only be a chance for
     * the two to disagree.
     */
    fun create(root: String, templateId: String, args: Map<String, String>): Result<Unit> = runCatching {
        val template = templateRegistry.byId(TemplateId(templateId))
            ?: error("No such template: $templateId")
        // Not prepared: an empty directory is what a template is about to fill, and adopting it first would
        // leave the project carrying a module nobody asked for beside the one the template writes.
        val store = open(root, prepare = false) ?: error("Could not open a model at $root")
        // JAVA_17 as on the desktop. Nothing here compiles against it: the level is what the module records
        // for whichever host DOES build the project later, and inventing a lower one because this host
        // cannot build would silently degrade the project on the host that can.
        template.generate(StoreScaffold(store, LanguageLevel.JAVA_17), TemplateArgs(args))
        store.save()
    }

    /**
     * Give a folder with no model the smallest honest one: one project, one module rooted at the folder.
     *
     * The module's directory is the project root (`dirRelPath = ""`), so its `module.toml` sits beside
     * `.platform/` rather than in a subdirectory that does not exist. Its source root is whichever
     * conventional directory is actually there, because a declared root that does not exist tells the next
     * host to look somewhere empty.
     *
     * This is deliberately not the JVM host's `adoptPlainFolderAt`, which adds a project and NO module and
     * marks it unrecognised. That answer suits a host that can import the folder's real build system later;
     * here there is nothing to import from and nothing to re-detect, and a project with no module has no
     * dependencies and no settings, which is the whole of what this host is being asked for.
     */
    private fun adopt(store: ProjectModelStore) {
        val name = IosFiles.nameOf(store.root).ifBlank { "project" }
        store.workspace.beginModification().apply {
            addProject(name, BuildSystemId.NATIVE, store.vfs.root())
            commit()
        }
        val project = store.workspace.projects.firstOrNull() ?: return
        project.beginModification().apply {
            addModule(IosFiles.sanitize(name), JavaLibModuleType).apply {
                dirRelPath = ""
                languageLevel = LanguageLevel.JAVA_17
                addSourceSet(
                    SourceSetTemplate(
                        "main",
                        DependencyScope.IMPLEMENTATION,
                        mapOf(adoptedSourceDir(store.root) to setOf(ContentRole.SOURCE)),
                    ),
                )
            }
            commit()
        }
        store.save()
        log.info("adopted ${store.root} as a single-module project")
    }

    /** The first conventional source directory that exists under [root], or the convention itself. */
    private fun adoptedSourceDir(root: String): String =
        SOURCE_DIR_CANDIDATES.firstOrNull { IosFiles.isDirectory(IosFiles.join(root, it)) }
            ?: SOURCE_DIR_CANDIDATES.first()

    /**
     * Move `.platform/dependencies` into the model, once.
     *
     * That file was this host's entire dependency story while there was no model: one
     * `scope group:name:version` per line, read and written by `IosDependencies` alone. Its declarations now
     * belong in `module.toml`, where every other host reads them, so a project carrying one is migrated into
     * its first module and the file is renamed rather than deleted. Renamed because it is the only copy of
     * something a user typed, and because `.platform` is hidden from the tree, so the leftover is invisible.
     *
     * Resolution is not attempted here: the declarations are what has to survive, and the jars they name
     * are already in the resolver's cache or will be fetched the next time the classpath is assembled.
     */
    private fun migrateFlatDeclarations(store: ProjectModelStore) {
        val file = IosFiles.join(store.root, LEGACY_DECLARATIONS)
        if (!IosFiles.exists(file)) return
        val module = store.workspace.projects.firstOrNull()?.modules?.firstOrNull()
        val project = module?.let { m -> store.workspace.projects.first { p -> p.modules.any { it.id == m.id } } }
        if (module == null || project == null) return
        val declared = module.dependencies.filterIsInstance<LibraryDependency>().map { it.library.name }.toSet()
        val lines = IosFiles.readText(file).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val scope = line.substringBefore(' ', "").ifBlank { DependencyScope.IMPLEMENTATION.name }
                val coordinate = line.substringAfter(' ', line).trim()
                coordinate.takeIf { it.count { c -> c == ':' } >= 2 && it !in declared }?.let { scope to it }
            }
            .toList()
        if (lines.isNotEmpty()) {
            project.beginModification().apply {
                val m = module(module.id)
                for ((scope, coordinate) in lines) {
                    m.addDependency(LibraryDependency(LibraryRef(coordinate), DependencyModelBridge.scopeOf(scope)))
                }
                commit()
            }
            store.save()
        }
        IosFiles.move(file, "$file.migrated")
        log.info("migrated ${lines.size} declaration(s) from $LEGACY_DECLARATIONS into ${module.name}")
    }

    // ---- the picker ----------------------------------------------------------------------------------

    /**
     * The module NAMES recorded at [root], read straight off `.platform/workspace.json`.
     *
     * A JSON read rather than an opened store, because this answers a LIST screen: the picker asks it of
     * every project it shows, and opening a model (with its lock, bus and service container) per row to
     * count modules would be paying for the whole model to render a subtitle. The open project answers from
     * its live store instead, so an edit made this session is not hidden behind a file the picker cached.
     *
     * Even a JSON read is too much per row per frame, so the answer is memoized against the model file's
     * modification time: a project list re-reads nothing until the project it describes actually changes,
     * and a change is picked up without anything having to invalidate this.
     *
     * Empty for a folder with no model, which is what a project made by an older build of this app is until
     * it is opened and adopted.
     */
    fun moduleNames(root: String): List<String> {
        storeFor(root)?.let { store -> return store.workspace.projects.flatMap { p -> p.modules.map { it.name } } }
        val model = IosFiles.join(root, MODEL_FILE)
        if (!IosFiles.exists(model)) return emptyList()
        val stamp = IosFiles.modifiedMs(model)
        cached[root]?.let { if (it.first == stamp) return it.second }
        val names = runCatching {
            ModelPersistence.load(root).projects.flatMap { p -> p.modules.map { it.name } }
        }.getOrDefault(emptyList())
        cached[root] = stamp to names
        return names
    }

    /**
     * [moduleNames] by root: the model file's modification time, and what it said.
     *
     * Concurrent because the readers are not one thread: the picker asks from the UI, and the backend asks
     * again while opening a project. A plain map here is the kind of thing that works until it does not.
     */
    private val cached = ConcurrentMap<String, Pair<Long, List<String>>>()

    /**
     * A module type this host knows the NAME of and nothing more.
     *
     * No default source sets and no default facets, because it contributes neither: it exists so a module
     * persisted under this id reads as what it is. No supported build system either, which is the honest
     * answer here and the reason it is not offered for creation.
     */
    private class RecognizedModuleType(
        override val id: String,
        override val displayName: String,
    ) : ModuleType {
        override fun defaultSourceSets(): List<SourceSetTemplate> = emptyList()
        override fun defaultFacets(): List<FacetTemplate> = emptyList()
        override fun supportedBuildSystems(): Set<BuildSystemId> = emptySet()
    }

    private companion object {
        private val log = Log.logger("ios-model")

        /**
         * Ids and names held in lockstep with `:android-support`'s `AndroidAppModuleType` /
         * `AndroidLibModuleType`, which this host cannot depend on (it is JVM: aapt2, d8, the manifest
         * merger). Two strings duplicated on purpose; the alternative is a project opening as unknown.
         */
        val RECOGNIZED_TYPES = listOf(
            RecognizedModuleType("android-app", "Android Application"),
            RecognizedModuleType("android-lib", "Android Library"),
        )

        /** Who the built-ins are contributed as. The JVM hosts use a plugin id here for the same reason. */
        val BUILT_IN = PluginId("dev.ide.ios.builtin")

        /** The model file a project is recognised by, relative to its root. */
        const val MODEL_FILE = ".platform/workspace.json"

        /** This host's pre-model declaration file, read once and migrated. */
        const val LEGACY_DECLARATIONS = ".platform/dependencies"

        /** Source directories an adopted folder is searched for, most conventional first. */
        val SOURCE_DIR_CANDIDATES = listOf("src/main/kotlin", "src/main/java", "src")
    }
}
