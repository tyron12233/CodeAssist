package dev.ide.core

import dev.ide.block.BlockEdit
import dev.ide.block.BlockId
import dev.ide.block.BlockNode
import dev.ide.block.BlockPart
import dev.ide.block.BlockRef
import dev.ide.block.BlockTemplate
import dev.ide.block.Delete
import dev.ide.block.InsertTemplate
import dev.ide.block.Move
import dev.ide.block.ReplaceWithText
import dev.ide.block.SetField
import dev.ide.block.SlotCategory
import dev.ide.block.SlotRef
import dev.ide.block.Wrap
import dev.ide.lang.completion.CaretAction
import dev.ide.lang.completion.CompletionItemKind
import dev.ide.lang.hints.InlayHintKind
import dev.ide.ui.backend.UiColorEntry
import dev.ide.ui.backend.UiDrawable
import dev.ide.ui.backend.UiGradient
import dev.ide.ui.backend.UiLayer
import dev.ide.ui.backend.UiStateLayer
import dev.ide.ui.backend.UiVectorPath
import dev.ide.ui.backend.UiInlayHint
import dev.ide.ui.backend.UiInlayKind
import dev.ide.android.support.preview.DrawablePreview
import dev.ide.android.support.preview.GradientSpec
import dev.ide.android.support.preview.Layer
import dev.ide.android.support.preview.StateLayer
import dev.ide.android.support.preview.VectorPath
import dev.ide.ui.backend.UiInlayPart
import dev.ide.ui.backend.UiSnippet
import dev.ide.ui.backend.UiSnippetStop
import dev.ide.ui.backend.UiTextRange
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.DepsResolveState
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiAction
import dev.ide.ui.backend.UiActionKind
import dev.ide.ui.backend.UiAndroidSourcesInfo
import dev.ide.ui.backend.UiJdkInfo
import dev.ide.ui.backend.UiSdkManagerState
import dev.ide.ui.backend.UiSdkPackage
import dev.ide.ui.backend.UiAddResult
import dev.ide.ui.backend.UiArtifactHit
import dev.ide.ui.backend.UiConfigResult
import dev.ide.ui.backend.UiDepModule
import dev.ide.ui.backend.UiModuleConfig
import dev.ide.ui.backend.UiModuleConfigEdit
import dev.ide.ui.backend.UiModuleDeps
import dev.ide.ui.backend.UiModuleRef
import dev.ide.ui.backend.UiPermissionDecision
import dev.ide.ui.backend.UiPermissionRequest
import dev.ide.ui.backend.UiSearchOptions
import dev.ide.ui.backend.UiTextMatch
import dev.ide.ui.backend.UiBlockEdit
import dev.ide.ui.backend.UiBlockNode
import dev.ide.ui.backend.UiBlockPart
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.RunTaskOption
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.UiCaret
import dev.ide.ui.backend.UiCompletionItem
import dev.ide.ui.backend.UiCompletionKind
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDefinition
import dev.ide.ui.backend.UiRenameResult
import dev.ide.ui.backend.UiRenameTarget
import dev.ide.ui.backend.UiDiagnostic
import dev.ide.ui.backend.UiSeverity
import dev.ide.ui.backend.UiTextEdit
import dev.ide.analysis.DiagnosticTag
import dev.ide.lang.dom.Severity
import dev.ide.model.ContentRole
import dev.ide.model.IconTarget
import dev.ide.model.Module
import dev.ide.model.template.ProjectTemplate
import dev.ide.model.template.TemplateParameter
import dev.ide.model.template.TextValidation
import dev.ide.ui.backend.PackageSegment
import dev.ide.ui.backend.UiProjectResult
import dev.ide.ui.backend.UiProjectTemplate
import dev.ide.ui.backend.UiTemplateParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Implements the UI's [IdeBackend] port over the JVM [IdeServices] facade.
 *
 * When a [ProjectManager] is supplied the backend is **project-aware**: `createProject`/`openProject`
 * swap the active [services] and bump [projectEpoch], on which the UI keys its per-project state. The
 * flow getters ([buildState]/[indexStatus]/[depsState]) delegate to the live [services], so a project
 * swap re-points them automatically once the UI subtree recomposes on the new epoch. With no manager the
 * backend is single-project (the pre-existing behaviour; create/open are unsupported).
 */
class IdeServicesBackend(
    initial: IdeServices,
    private val manager: ProjectManager? = null,
) : IdeBackend, dev.ide.preview.LayoutPreviewBackend {

    @Volatile
    private var services: IdeServices = initial

    /**
     * The thread the editor's language work (parse/complete/analyze/hints/actions/rename) runs on.
     *
     * Those calls reach the per-(module,language) [SourceAnalyzer]s, which hold mutable incremental-parser
     * and JDT-environment state and are NOT safe for concurrent use, and `IdeServices.runSync` takes no
     * lock — so they must stay serialized. They used to be serialized only incidentally, by all running on
     * the Compose main thread, which also meant every JDT call (tens to hundreds of ms on ART) blocked
     * typing: the editor stuttered whenever a debounced completion/analysis fired between keystrokes.
     *
     * Confining them to a single background thread keeps the serialization (one worker → never two
     * analyzer calls at once) while freeing the UI thread, so typing stays smooth no matter how slow a
     * given analysis is. `limitedParallelism(1)` borrows one worker from the shared Default pool (no
     * dedicated thread to close).
     */
    private val engineDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val _projectEpoch = MutableStateFlow(0)
    override val projectEpoch: StateFlow<Int> get() = _projectEpoch

    private val _fsEpoch = MutableStateFlow(0)
    override val fileSystemEpoch: StateFlow<Int> get() = _fsEpoch

    override val project: ProjectInfo
        get() = ProjectInfo(
            name = services.projectDisplayName(),
            rootPath = services.workspaceRoot.toString(),
            moduleCount = services.modules().size,
        )

    override fun fileTree(): TreeNode {
        val root = services.workspaceRoot
        val moduleNodes = services.modules().sortedBy { it.name }.map { module ->
            val children = ArrayList<TreeNode>()
            // The AndroidManifest sits above the source roots, so surface it explicitly (first, like Studio).
            services.manifestPath(module)?.takeIf { Files.isRegularFile(it) }?.let { mf -> children.add(fileNode(mf, module)) }
            // Code/resources/assets roots, each with a distinct icon by role — sources first, then res/assets.
            services.treeRootsDetailed(module)
                .filter { Files.isDirectory(it.path) }
                .sortedWith(compareBy({ it.roles.rootRank() }, { it.path.toString() }))
                .forEach { info -> children.add(sourceRootNode(info, module, root)) }
            TreeNode(
                id = "module:${module.name}",
                name = module.name,
                kind = NodeKind.Module,
                filePath = null,
                iconId = services.iconFor(IconTarget.ModuleNode(module)) ?: "module",
                children = children,
            )
        }
        return TreeNode(
            id = "workspace",
            name = root.fileName?.toString() ?: "workspace",
            kind = NodeKind.Workspace,
            filePath = null,
            iconId = "workspace",
            children = moduleNodes,
        )
    }

    /** A surfaced content root. `SOURCE`/`GENERATED` roots are package contexts (compactable, new-class
     *  targets); everything else (`res/`, `assets/`, resources) is a plain folder tree. */
    private fun sourceRootNode(info: TreeRootInfo, module: Module, workspaceRoot: Path): TreeNode {
        val packageRoot = ContentRole.SOURCE in info.roles || ContentRole.GENERATED in info.roles
        val label = runCatching { workspaceRoot.relativize(info.path).toString() }.getOrDefault(info.path.toString())
        val children = if (packageRoot) packageChildren(info.path, info.path, module)
        else resourceChildren(info.path, info.roles, module)
        return TreeNode(
            id = "root:${info.path}",
            name = label,
            kind = NodeKind.SourceRoot,
            filePath = null,
            iconId = services.iconFor(IconTarget.SourceRoot(info.sourceSetName, info.roles, module)) ?: "sourceset.java",
            children = children,
            sourceRootPath = if (packageRoot) info.path.toString() else null,
            // The Android res root is an XML new-file target (the dialog routes into res/<kind>/).
            resDirPath = if (ContentRole.ANDROID_RES in info.roles) info.path.toString() else null,
        )
    }

    /** Direct children of a Java/Kotlin package context: package nodes + any default-package files. */
    private fun packageChildren(dir: Path, sourceRoot: Path, module: Module): List<TreeNode> {
        val (dirs, files) = childPartition(dir)
        return dirs.map { packageNode(it, sourceRoot, module) } + files.map { fileNode(it, module) }
    }

    /**
     * A package node that **compacts middle packages**: starting at [startDir], it follows the chain while
     * each level has exactly one subdirectory and no files, so `com/ → tyron/ → codeassist/` becomes one
     * `com.tyron.codeassist` node. The chain's [PackageSegment]s (one per level, each with its dotted
     * package and directory) are kept so a New-Class action can target any intermediate level.
     */
    private fun packageNode(startDir: Path, sourceRoot: Path, module: Module): TreeNode {
        val segments = ArrayList<PackageSegment>()
        var cur = startDir
        while (true) {
            val pkg = sourceRoot.relativize(cur).toString().replace(File.separatorChar, '.')
            segments.add(PackageSegment(pkg, cur.toString()))
            val (dirs, files) = childPartition(cur)
            if (dirs.size == 1 && files.isEmpty()) cur = dirs[0] else break
        }
        val displayName = (startDir.parent ?: sourceRoot).relativize(cur).toString().replace(File.separatorChar, '.')
        val (dirs, files) = childPartition(cur)
        val children = dirs.map { packageNode(it, sourceRoot, module) } + files.map { fileNode(it, module) }
        return TreeNode(
            id = "pkg:$cur",
            name = displayName,
            kind = NodeKind.Package,
            filePath = null,
            iconId = services.iconFor(IconTarget.PackageDir(segments.last().packageName)) ?: "package",
            children = children,
            sourceRootPath = sourceRoot.toString(),
            packageSegments = segments,
        )
    }

    /** Children of a non-package root (res/assets/resources): plain folders, no compaction. */
    private fun resourceChildren(dir: Path, roles: Set<ContentRole>, module: Module): List<TreeNode> {
        val (dirs, files) = childPartition(dir)
        return dirs.map { resourceDirNode(it, roles, module) } + files.map { fileNode(it, module) }
    }

    private fun resourceDirNode(dir: Path, roles: Set<ContentRole>, module: Module): TreeNode = TreeNode(
        id = "dir:$dir",
        name = dir.fileName.toString(),
        kind = NodeKind.Folder,
        filePath = null,
        iconId = services.iconFor(IconTarget.Directory(dir.fileName.toString(), roles)) ?: "folder",
        children = resourceChildren(dir, roles, module),
        // A folder under an Android res root (layout/values/drawable/menu/…) is an XML new-file target.
        resDirPath = if (ContentRole.ANDROID_RES in roles) dir.toString() else null,
    )

    private fun fileNode(file: Path, module: Module?): TreeNode {
        val name = file.fileName.toString()
        return TreeNode(
            id = "file:$file",
            name = name,
            kind = NodeKind.File,
            filePath = file.toString(),
            iconId = services.iconFor(IconTarget.File(name, module)) ?: "file",
        )
    }

    /** Visible entries of [dir], split into (sorted subdirectories, sorted files); dotfiles dropped. */
    private fun childPartition(dir: Path): Pair<List<Path>, List<Path>> {
        val entries = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
            .filterNot { it.fileName.toString().startsWith(".") }
        val dirs = entries.filter { Files.isDirectory(it) }.sortedBy { it.fileName.toString() }
        val files = entries.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }
        return dirs to files
    }

    /** Sort key so a module's roots list sources first, then resources, android-res, assets. */
    private fun Set<ContentRole>.rootRank(): Int = when {
        ContentRole.SOURCE in this || ContentRole.GENERATED in this -> 0
        ContentRole.RESOURCE in this -> 1
        ContentRole.ANDROID_RES in this -> 2
        ContentRole.ASSETS in this -> 3
        else -> 4
    }

    override fun createFile(dirPath: String, fileName: String, content: String): String? =
        writeNewFile(dirPath, fileName) { it.writeText(content) }

    override fun createFileBytes(dirPath: String, fileName: String, bytes: ByteArray): String? =
        writeNewFile(dirPath, fileName) { Files.write(it, bytes) }

    override fun createDirectory(parentPath: String, name: String): String? = runCatching {
        val target = Paths.get(parentPath).resolve(name)
        if (Files.exists(target)) null
        else {
            Files.createDirectories(target)
            _fsEpoch.value += 1
            target.toString()
        }
    }.getOrNull()

    /** Create `[dirPath]/[fileName]` via [write] (fails if it exists); refresh R on `res/`, bump the fs epoch. */
    private fun writeNewFile(dirPath: String, fileName: String, write: (Path) -> Unit): String? = runCatching {
        val dir = Paths.get(dirPath)
        Files.createDirectories(dir)
        val target = dir.resolve(fileName)
        if (Files.exists(target)) null
        else {
            write(target)
            if (dirPath.replace('\\', '/').contains("/res/")) services.invalidateSyntheticClasses() // new res → refresh R
            _fsEpoch.value += 1
            target.toString()
        }
    }.getOrNull()

    override fun readFile(path: String): String =
        runCatching { (Paths.get(path)).readText() }.getOrDefault("")

    override fun moduleNameForFile(path: String): String? =
        services.moduleForFile(Paths.get(path))?.name

    override suspend fun breadcrumbAt(path: String, text: String, offset: Int): List<String> =
        withContext(engineDispatcher) { services.breadcrumbAt(Paths.get(path), text, offset) }

    override suspend fun definitionAt(path: String, text: String, offset: Int): UiDefinition? =
        withContext(engineDispatcher) { services.definitionAt(Paths.get(path), text, offset) }
            ?.let { (p, o) -> UiDefinition(p.toString(), o) }

    override suspend fun prepareRename(path: String, text: String, offset: Int): UiRenameTarget? =
        withContext(engineDispatcher) { services.prepareRename(Paths.get(path), text, offset)?.let { UiRenameTarget(it.oldName, it.kind) } }

    override suspend fun rename(path: String, text: String, offset: Int, newName: String): UiRenameResult =
        withContext(engineDispatcher) {
            val r = services.rename(Paths.get(path), text, offset, newName)
            if (r.success) _fsEpoch.value += 1 // the multi-file edit / file rename changed the tree + other buffers
            UiRenameResult(r.success, r.message, r.occurrences, r.filesChanged, r.newPath)
        }

    override fun updateDocument(path: String, text: String) =
        services.updateDocument(Paths.get(path), text)

    override fun saveFile(path: String, text: String) =
        services.save(Paths.get(path), text)

    override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult {
        val result = withContext(engineDispatcher) { services.complete(Paths.get(path), text, offset) }
        return UiCompletionResult(
            items = result.items.map { item ->
                UiCompletionItem(
                    label = item.label, insertText = item.insertText, detail = item.detail,
                    documentation = item.documentation, kind = mapKind(item.kind), sortPriority = item.sortPriority,
                    additionalEdits = item.additionalEdits.map { UiTextEdit(it.range.start, it.range.end, it.newText) },
                    caret = mapCaret(item.caret),
                    snippet = mapSnippet(item.caret),
                )
            },
            replaceStart = result.replacementRange.start,
            replaceEnd = result.replacementRange.end,
        )
    }

    override val indexStatus: StateFlow<IndexUiStatus> get() = services.indexStatus
    override fun reindex() = services.reindex()

    override val buildState: StateFlow<BuildState> get() = services.buildState
    override fun runTasks(): List<RunTaskOption> = services.runTasks()
    override fun runTask(id: String) = services.runTask(id)
    override fun runBuild() = services.runBuild()
    override fun stopBuild() = services.stopBuild()

    override val permissionRequest: StateFlow<UiPermissionRequest?> get() = services.permissionRequest
    override fun answerPermission(id: Int, decision: UiPermissionDecision) = services.answerPermission(id, decision)

    // ---- dependency management ----

    override val depsState: StateFlow<DepsResolveState> get() = services.depsState
    override fun dependencyModules(): List<UiDepModule> = services.dependencyModules()

    // Resolution does blocking HTTP — keep it off the caller's (possibly UI) dispatcher.
    override suspend fun moduleDependencies(moduleName: String): UiModuleDeps? =
        withContext(Dispatchers.IO) { services.moduleDependencies(moduleName) }

    override suspend fun searchArtifacts(query: String, moduleName: String): List<UiArtifactHit> =
        withContext(Dispatchers.IO) { services.searchArtifacts(query, moduleName) }

    override suspend fun addDependency(moduleName: String, coordinate: String, scope: String): UiAddResult =
        withContext(Dispatchers.IO) { services.addDependency(moduleName, coordinate, scope) }

    override suspend fun addPlatform(moduleName: String, coordinate: String): UiAddResult =
        withContext(Dispatchers.IO) { services.addPlatform(moduleName, coordinate) }

    override fun removeDependency(moduleName: String, coordinate: String): Boolean =
        services.removeDependency(moduleName, coordinate)

    // ---- module configuration ----

    override fun configurableModules(): List<UiModuleRef> = services.configurableModules()

    override suspend fun getModuleConfig(moduleName: String): UiModuleConfig? =
        withContext(Dispatchers.IO) { services.getModuleConfig(moduleName) }

    override suspend fun updateModuleConfig(moduleName: String, edit: UiModuleConfigEdit): UiConfigResult =
        withContext(Dispatchers.IO) {
            services.updateModuleConfig(moduleName, edit).also { if (it.success) _fsEpoch.value += 1 }
        }

    // ---- project management ----

    override fun projects(): List<ProjectInfo> =
        manager?.list()?.map { ProjectInfo(it.name, it.rootPath, it.moduleCount) } ?: listOf(project)

    override fun projectTemplates(): List<UiProjectTemplate> = services.projectTemplates().map(::toUiTemplate)

    override suspend fun createProject(templateId: String, args: Map<String, String>): UiProjectResult {
        val mgr = manager ?: return UiProjectResult(false, "Project creation not supported by this backend")
        return withContext(Dispatchers.IO) {
            runCatching {
                val next = mgr.create(templateId, args)
                swap(next)
                UiProjectResult(true, "Created ${next.projectDisplayName()}", next.workspaceRoot.toString())
            }.getOrElse { e -> UiProjectResult(false, e.message ?: "Failed to create project") }
        }
    }

    override suspend fun openProject(rootPath: String): Boolean {
        val mgr = manager ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                if (Paths.get(rootPath) == services.workspaceRoot) return@runCatching true
                swap(mgr.open(rootPath)); true
            }.getOrDefault(false)
        }
    }

    override suspend fun deleteProject(rootPath: String): Boolean {
        val mgr = manager ?: return false
        return withContext(Dispatchers.IO) {
            runCatching { mgr.delete(rootPath); true }.getOrDefault(false)
        }
    }

    override fun preference(key: String): String? = manager?.preference(key)

    override fun setPreference(key: String, value: String) {
        manager?.setPreference(key, value)
    }

    override suspend fun backupProjects(): String? {
        val mgr = manager ?: return null
        return withContext(Dispatchers.IO) { runCatching { mgr.exportBackup().toString() }.getOrNull() }
    }

    /** Close the active engine — the host calls this on teardown (window close / activity destroy). */
    fun close() = services.close()

    /** Make [next] the active project: swap it in, bump the epoch (re-keys UI state), and close the old one. */
    private fun swap(next: IdeServices) {
        val prev = services
        services = next
        _projectEpoch.value += 1
        if (prev !== next) runCatching { prev.close() }
    }

    private fun toUiTemplate(t: ProjectTemplate): UiProjectTemplate = UiProjectTemplate(
        id = t.id.value,
        displayName = t.displayName,
        description = t.description,
        category = t.category.displayName,
        iconId = t.iconId,
        parameters = t.parameters().map(::toUiParam),
    )

    private fun toUiParam(p: TemplateParameter): UiTemplateParam = when (p) {
        is TemplateParameter.Text -> UiTemplateParam.Text(p.key, p.label, p.default, p.placeholder, mapValidation(p.validation), p.help)
        is TemplateParameter.Choice -> UiTemplateParam.Choice(
            p.key, p.label, p.options.map { UiTemplateParam.Choice.Option(it.value, it.label) }, p.defaultIndex, p.help,
        )
        is TemplateParameter.Toggle -> UiTemplateParam.Toggle(p.key, p.label, p.default, p.help)
    }

    private fun mapValidation(v: TextValidation): String = when (v) {
        TextValidation.NONE -> "none"
        TextValidation.IDENTIFIER -> "identifier"
        TextValidation.PACKAGE_NAME -> "package"
        TextValidation.PROJECT_NAME -> "project"
    }

    override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> =
        services.searchSymbols(query, limit).map {
            SymbolHit(it.name, it.container ?: it.kind, it.kind, it.filePath, it.offset)
        }

    override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> =
        services.searchMembers(query, limit).map {
            SymbolHit(it.name, it.owner.substringAfterLast('.'), it.kind, null, null)
        }

    // Walks/reads files — keep off the caller's (possibly UI) dispatcher.
    override suspend fun findInFiles(query: String, options: UiSearchOptions, limit: Int): List<UiTextMatch> =
        withContext(Dispatchers.IO) { services.findInFiles(query, options, limit) }

    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> {
        // Routes through the full analysis engine: JDT compiler errors + the Java analyzers, merged,
        // suppression-filtered, and profile-adjusted into one set.
        return withContext(engineDispatcher) { services.analyzeDiagnostics(Paths.get(path), text) }.map { d ->
            val (line, col) = lineColOf(text, d.range.start)
            UiDiagnostic(
                severity = when (d.severity) {
                    Severity.ERROR -> UiSeverity.Error
                    Severity.WARNING -> UiSeverity.Warning
                    Severity.INFO -> UiSeverity.Info
                    Severity.HINT -> UiSeverity.Hint
                },
                line = line,
                col = col,
                message = d.message,
                startOffset = d.range.start,
                endOffset = d.range.end,
                unused = DiagnosticTag.UNUSED in d.tags,
            )
        }
    }

    override fun androidSourcesInfo(): UiAndroidSourcesInfo? =
        services.androidSourcesInfo()?.let { UiAndroidSourcesInfo(it.platform, it.installed, it.downloadable) }

    override suspend fun downloadAndroidSources(): String = services.downloadAndroidSources()

    override val sdkManagerState: StateFlow<UiSdkManagerState> get() = services.sdkManager.state
    override suspend fun sdkPackages(): List<UiSdkPackage> = services.sdkManager.androidPackages()
    override suspend fun installSdkPackage(path: String): String = services.sdkManager.installAndroidPackage(path)
    override fun jdkInfo(): UiJdkInfo = services.sdkManager.jdkInfo()
    override suspend fun downloadJdkSources(feature: Int): String = services.sdkManager.downloadJdkSources(feature)

    override suspend fun hintsAt(path: String, text: String, startOffset: Int, endOffset: Int): List<UiInlayHint> =
        withContext(engineDispatcher) { services.inlayHints(Paths.get(path), text, startOffset, endOffset) }.map { h ->
            UiInlayHint(
                offset = h.offset,
                parts = h.parts.map { UiInlayPart(it.text) },
                kind = when (h.kind) {
                    InlayHintKind.TYPE -> UiInlayKind.Type
                    InlayHintKind.PARAMETER -> UiInlayKind.Parameter
                    InlayHintKind.CHAINING -> UiInlayKind.Chaining
                    InlayHintKind.OTHER -> UiInlayKind.Other
                },
                tooltip = h.tooltip,
                paddingLeft = h.paddingLeft,
                paddingRight = h.paddingRight,
            )
        }

    // ---- code actions (quick-fixes + intentions) ----

    override suspend fun actionsAt(path: String, text: String, selStart: Int, selEnd: Int): List<UiAction> =
        withContext(engineDispatcher) { services.editorActions(Paths.get(path), text, selStart, selEnd) }
            .mapIndexed { i, fix -> UiAction(i, fix.title, mapActionKind(fix.kind)) }

    override suspend fun applyAction(path: String, text: String, selStart: Int, selEnd: Int, actionId: Int): List<UiTextEdit> =
        withContext(engineDispatcher) { services.applyEditorAction(Paths.get(path), text, selStart, selEnd, actionId) }
            .map { UiTextEdit(it.offset, it.offset + it.oldLength, it.newText.toString()) }

    private fun mapActionKind(k: dev.ide.analysis.CodeActionKind): UiActionKind = when (k) {
        dev.ide.analysis.CodeActionKind.QUICK_FIX -> UiActionKind.QUICK_FIX
        dev.ide.analysis.CodeActionKind.INTENTION -> UiActionKind.INTENTION
        dev.ide.analysis.CodeActionKind.REFACTOR -> UiActionKind.REFACTOR
    }

    // ---- block-based editing ----

    override suspend fun projectBlocks(path: String, text: String): UiBlockNode? =
        withContext(engineDispatcher) { services.projectBlocks(Paths.get(path), text) }?.let { toUiBlock(it.root) }

    override suspend fun applyBlockEdit(path: String, text: String, edit: UiBlockEdit): List<UiTextEdit> {
        val blockEdit: BlockEdit = when (edit) {
            is UiBlockEdit.SetField -> SetField(BlockRef(BlockId(edit.blockId)), edit.role, edit.text)
            is UiBlockEdit.ReplaceSlot -> ReplaceWithText(SlotRef(BlockId(edit.blockId), edit.slotIndex), edit.text)
            is UiBlockEdit.DeleteBlock -> Delete(BlockRef(BlockId(edit.blockId)))
            is UiBlockEdit.InsertTemplate -> InsertTemplate(
                SlotRef(BlockId(edit.ownerBlockId), edit.slotIndex, edit.index),
                BlockTemplate(label = "insert", category = SlotCategory.STATEMENT, defaultText = edit.text),
            )
            is UiBlockEdit.WrapInIf -> Wrap(
                BlockRef(BlockId(edit.blockId)),
                BlockTemplate(label = "if", category = SlotCategory.STATEMENT, defaultText = "if (true) {\n${BlockTemplate.PLACEHOLDER}\n}"),
            )
            is UiBlockEdit.MoveBlock -> Move(
                BlockRef(BlockId(edit.blockId)),
                SlotRef(BlockId(edit.toOwnerBlockId), edit.toSlotIndex, edit.toIndex),
            )
        }
        return withContext(engineDispatcher) { services.computeBlockEdit(Paths.get(path), text, blockEdit) }
            .map { UiTextEdit(it.offset, it.offset + it.oldLength, it.newText.toString()) }
    }

    /** Map a framework [BlockNode] subtree onto the UI's neutral [UiBlockNode] DTO. */
    private fun toUiBlock(b: BlockNode): UiBlockNode = UiBlockNode(
        id = b.id.value,
        kind = b.kind.id,
        label = b.label,
        category = b.kind.id,
        start = b.range.start,
        end = b.range.end,
        parts = b.parts.map { part ->
            when (part) {
                is BlockPart.Field -> UiBlockPart.Field(
                    role = part.field.role,
                    text = part.field.text,
                    editable = part.field.editable,
                    start = part.field.range?.start ?: -1,
                    end = part.field.range?.end ?: -1,
                )
                is BlockPart.Slot -> UiBlockPart.Slot(
                    category = part.slot.category.name,
                    multiple = part.slot.multiple,
                    start = part.slot.range.start,
                    end = part.slot.range.end,
                    children = part.slot.children.map(::toUiBlock),
                    valueKind = part.slot.valueKind.name.lowercase(),
                )
            }
        },
        valueKind = b.valueKind.name.lowercase(),
    )

    // ---- resource preview ----

    override suspend fun drawablePreview(path: String, text: String): UiDrawable? =
        services.drawablePreview(Paths.get(path), text)?.let(::toUiDrawable)

    override suspend fun colorResources(path: String, text: String): List<UiColorEntry> =
        services.colorResources(Paths.get(path), text).map { UiColorEntry(it.name, it.rawValue, it.argb) }

    override suspend fun resourceImageBytes(path: String): ByteArray? =
        services.resourceBytes(Paths.get(path))

    override suspend fun layoutPreview(path: String, text: String, request: dev.ide.preview.PreviewRequest): dev.ide.preview.LayoutPreviewResult? =
        services.layoutPreview(Paths.get(path), text, request)

    /** Map the engine's [DrawablePreview] onto the UI's neutral [UiDrawable] DTO. */
    private fun toUiDrawable(d: DrawablePreview): UiDrawable = when (d) {
        is DrawablePreview.SolidColor -> UiDrawable.SolidColor(d.color)
        is DrawablePreview.Shape -> d.spec.let { s ->
            UiDrawable.Shape(
                shape = s.shape.name.lowercase(),
                solidColor = s.solidColor,
                gradient = s.gradient?.let(::toUiGradient),
                strokeColor = s.strokeColor,
                strokeWidthDp = s.strokeWidthDp,
                dashWidthDp = s.dashWidthDp,
                dashGapDp = s.dashGapDp,
                cornerTopLeftDp = s.cornerTopLeftDp,
                cornerTopRightDp = s.cornerTopRightDp,
                cornerBottomRightDp = s.cornerBottomRightDp,
                cornerBottomLeftDp = s.cornerBottomLeftDp,
                intrinsicWidthDp = s.intrinsicWidthDp,
                intrinsicHeightDp = s.intrinsicHeightDp,
                innerRadiusFraction = s.innerRadiusFraction,
                thicknessFraction = s.thicknessFraction,
            )
        }
        is DrawablePreview.Vector -> d.spec.let { v ->
            UiDrawable.Vector(
                widthDp = v.widthDp, heightDp = v.heightDp,
                viewportWidth = v.viewportWidth, viewportHeight = v.viewportHeight,
                rootAlpha = v.rootAlpha,
                paths = v.paths.map(::toUiVectorPath),
            )
        }
        is DrawablePreview.Layers -> UiDrawable.Layers(d.layers.map(::toUiLayer))
        is DrawablePreview.States -> UiDrawable.States(
            states = d.states.map(::toUiStateLayer),
            defaultLayer = d.defaultLayer?.let(::toUiDrawable),
        )
        is DrawablePreview.BitmapRef -> UiDrawable.Bitmap(d.resType, d.resName, d.filePath)
        is DrawablePreview.Unsupported -> UiDrawable.Unsupported(d.rootTag, d.message)
    }

    private fun toUiGradient(g: GradientSpec) = UiGradient(
        kind = g.kind.name.lowercase(),
        startColor = g.startColor, centerColor = g.centerColor, endColor = g.endColor,
        angle = g.angle, centerX = g.centerX, centerY = g.centerY, radiusFraction = g.radiusFraction,
    )

    private fun toUiVectorPath(p: VectorPath) = UiVectorPath(
        pathData = p.pathData, fillColor = p.fillColor, strokeColor = p.strokeColor,
        strokeWidthVp = p.strokeWidthVp, fillAlpha = p.fillAlpha, strokeAlpha = p.strokeAlpha,
    )

    private fun toUiLayer(l: Layer) = UiLayer(
        drawable = toUiDrawable(l.drawable),
        insetLeftDp = l.insetLeftDp, insetTopDp = l.insetTopDp,
        insetRightDp = l.insetRightDp, insetBottomDp = l.insetBottomDp,
    )

    private fun toUiStateLayer(s: StateLayer) = UiStateLayer(s.states, toUiDrawable(s.drawable))

    private fun lineColOf(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var lineStart = 0
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) {
            if (text[i] == '\n') { line++; lineStart = i + 1 }
        }
        return line to (end - lineStart + 1)
    }

    /** Flatten the framework's [CaretAction] to the UI's neutral [UiCaret] (null = caret at end of insert). */
    private fun mapCaret(action: CaretAction): UiCaret? = when (action) {
        CaretAction.AtEnd -> null
        is CaretAction.At -> UiCaret(action.offset)
        is CaretAction.Select -> UiCaret(action.offset, action.length)
        // Full snippet stepping (linked cursors, choice popups) is a follow-up in the editor; until then
        // degrade to selecting the first tab stop (or the final caret) so a snippet item is still usable.
        is CaretAction.ExpandSnippet -> {
            val first = action.expansion.stops
                .filter { it.index != 0 }
                .minByOrNull { it.index }
                ?.ranges?.firstOrNull()
            if (first != null) UiCaret(first.start, first.end - first.start)
            else UiCaret(action.expansion.finalCaretOffset)
        }
    }

    /** The full snippet payload (tab stops) for an [CaretAction.ExpandSnippet] item; null otherwise. */
    private fun mapSnippet(action: CaretAction): UiSnippet? {
        val exp = (action as? CaretAction.ExpandSnippet)?.expansion ?: return null
        return UiSnippet(
            stops = exp.stops.map { s -> UiSnippetStop(s.index, s.ranges.map { UiTextRange(it.start, it.end) }, s.choices) },
            finalCaretOffset = exp.finalCaretOffset,
        )
    }

    private fun mapKind(k: CompletionItemKind): UiCompletionKind = when (k) {
        CompletionItemKind.CLASS -> UiCompletionKind.Class
        CompletionItemKind.INTERFACE -> UiCompletionKind.Interface
        CompletionItemKind.ENUM -> UiCompletionKind.Enum
        CompletionItemKind.ANNOTATION_TYPE -> UiCompletionKind.AnnotationType
        CompletionItemKind.RECORD -> UiCompletionKind.Record
        CompletionItemKind.METHOD -> UiCompletionKind.Method
        CompletionItemKind.CONSTRUCTOR -> UiCompletionKind.Constructor
        CompletionItemKind.FIELD -> UiCompletionKind.Field
        CompletionItemKind.ENUM_CONSTANT -> UiCompletionKind.EnumConstant
        CompletionItemKind.VARIABLE -> UiCompletionKind.Variable
        CompletionItemKind.PARAMETER -> UiCompletionKind.Parameter
        CompletionItemKind.TYPE_PARAMETER -> UiCompletionKind.TypeParameter
        CompletionItemKind.PACKAGE -> UiCompletionKind.Package
        CompletionItemKind.KEYWORD -> UiCompletionKind.Keyword
        CompletionItemKind.SNIPPET -> UiCompletionKind.Snippet
    }
}
