package dev.ide.ui

import dev.ide.ui.backend.ActionService
import dev.ide.ui.backend.BlockService
import dev.ide.ui.backend.BuildService
import dev.ide.ui.backend.BuildState
import dev.ide.ui.backend.DependencyService
import dev.ide.ui.backend.DiagnosticsService
import dev.ide.ui.backend.EditorService
import dev.ide.ui.backend.FileService
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.IndexUiStatus
import dev.ide.ui.backend.SigningService
import dev.ide.ui.backend.ModuleService
import dev.ide.ui.backend.NodeKind
import dev.ide.ui.backend.PreviewService
import dev.ide.ui.backend.ProjectInfo
import dev.ide.ui.backend.ProjectService
import dev.ide.ui.backend.SdkService
import dev.ide.ui.backend.SearchService
import dev.ide.ui.backend.SettingsService
import dev.ide.ui.backend.SymbolHit
import dev.ide.ui.backend.TreeNode
import dev.ide.ui.backend.TreeViewMode
import dev.ide.ui.backend.UiCompletionResult
import dev.ide.ui.backend.UiDiagnostic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A no-op [IdeBackend] for tests: it implements every concern service (via `get() = this`) and stubs the
 * abstract members, so a test fake only overrides what it exercises. All members are `open`.
 */
internal open class StubBackend : IdeBackend,
    FileService, EditorService, BlockService, PreviewService, SearchService, BuildService,
    DependencyService, ModuleService, SigningService, ProjectService, SdkService, SettingsService, ActionService,
    DiagnosticsService {

    override val files: FileService get() = this
    override val editor: EditorService get() = this
    override val blocks: BlockService get() = this
    override val preview: PreviewService get() = this
    override val search: SearchService get() = this
    override val build: BuildService get() = this
    override val deps: DependencyService get() = this
    override val modules: ModuleService get() = this
    override val signing: SigningService get() = this
    override val projects: ProjectService get() = this
    override val sdk: SdkService get() = this
    override val settings: SettingsService get() = this
    override val actions: ActionService get() = this
    override val diagnostics: DiagnosticsService get() = this

    override val project: ProjectInfo = ProjectInfo("stub", "/stub", 0)

    // FileService (abstract)
    override fun fileTree(mode: TreeViewMode): TreeNode = TreeNode("root", "stub", NodeKind.Workspace, null)
    override fun readFile(path: String): String = ""
    override fun moduleNameForFile(path: String): String? = null

    // EditorService (abstract)
    override fun updateDocument(path: String, text: String) {}
    override fun saveFile(path: String, text: String) {}
    override suspend fun complete(path: String, text: String, offset: Int): UiCompletionResult =
        UiCompletionResult(emptyList(), offset, offset)
    override suspend fun analyze(path: String, text: String): List<UiDiagnostic> = emptyList()

    // SearchService (abstract)
    override val indexStatus: StateFlow<IndexUiStatus> = MutableStateFlow(IndexUiStatus())
    override suspend fun searchSymbols(query: String, limit: Int): List<SymbolHit> = emptyList()
    override suspend fun searchMembers(query: String, limit: Int): List<SymbolHit> = emptyList()

    // BuildService (abstract)
    override val buildState: StateFlow<BuildState> = MutableStateFlow(BuildState())
    override fun runBuild() {}
    override fun stopBuild() {}
}
