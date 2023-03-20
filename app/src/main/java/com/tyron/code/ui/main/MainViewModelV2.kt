package com.tyron.code.ui.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyron.code.ApplicationLoader
import com.tyron.code.event.SubscriptionReceipt
import com.tyron.code.indexing.ProjectIndexer
import com.tyron.code.module.ModuleManagerImpl
import com.tyron.code.project.CodeAssistJavaCoreProjectEnvironment
import com.tyron.code.ui.file.event.OpenFileEvent
import com.tyron.code.ui.file.event.RefreshRootEvent
import com.tyron.code.ui.file.tree.TreeUtil
import com.tyron.code.ui.file.tree.model.TreeFile
import com.tyron.code.ui.legacyEditor.impl.text.rosemoe.RosemoeEditorFacade
import com.tyron.ui.treeview.TreeNode
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.LoadTextUtil
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager
import org.jetbrains.kotlin.com.intellij.openapi.progress.util.StandardProgressIndicatorBase
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.openapi.vfs.newvfs.persistent.FSRecords
import org.jetbrains.kotlin.com.intellij.psi.stubs.StubIndex
import org.jetbrains.kotlin.com.intellij.sdk.SdkManager
import org.jetbrains.kotlin.com.intellij.util.indexing.*
import java.io.File
import java.util.concurrent.Executors


class MainViewModelV2(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val projectPath by lazy { savedStateHandle.get<String>("project_path")!! }

    private val _projectState = MutableStateFlow(ProjectState())
    val projectState: StateFlow<ProjectState> = _projectState

    private val _treeNode = MutableStateFlow<TreeNode<TreeFile>?>(null)
    val treeNode = _treeNode.asStateFlow()

    private val _textEditorListState = MutableStateFlow(TextEditorListState())
    val textEditorListState = _textEditorListState.asStateFlow()

    private val _currentTextEditorState = MutableStateFlow<TextEditorState?>(null)
    val currentTextEditorState = _currentTextEditorState.asStateFlow()

    private val _indexingState = MutableStateFlow<IndexingState?>(null)
    val indexingState = _indexingState.asStateFlow()

    lateinit var projectEnvironment: CodeAssistJavaCoreProjectEnvironment

    private var fileOpenSubscriptionReceipt: SubscriptionReceipt<OpenFileEvent>

    init {
        fileOpenSubscriptionReceipt =
            ApplicationLoader.getInstance().eventManager.subscribeEvent(OpenFileEvent::class.java) { event, _ ->
                openFile(event.file.absolutePath)
            }

        val handler = CoroutineExceptionHandler { _, exception ->
            println("Error: $exception")
        }
        viewModelScope.launch(handler) {
            val disposable = Disposer.newDisposable()
            val appEnvironment = ApplicationLoader.getInstance().coreApplicationEnvironment
            val fileByPath: VirtualFile =
                appEnvironment.localFileSystem.findFileByPath(projectPath)!!
            projectEnvironment =
                CodeAssistJavaCoreProjectEnvironment(disposable, appEnvironment, fileByPath)
            ProjectCoreUtil.theProject = projectEnvironment.project

            // TODO: remove this
            RosemoeEditorFacade.project = projectEnvironment.project
            RosemoeEditorFacade.projectEnvironment = projectEnvironment

            val localFs = appEnvironment.localFileSystem;
            val projectDir = localFs.findFileByPath(projectPath)!!

            SdkManager.getInstance(projectEnvironment.project).loadDefaultSdk()

            viewModelScope.launch(Dispatchers.IO) {
                ApplicationLoader.getInstance().eventManager.dispatchEvent(
                    RefreshRootEvent(
                        File(
                            projectPath
                        )
                    )
                )
            }

            val parsed =
                (ModuleManagerImpl.getInstance(projectEnvironment.project) as ModuleManagerImpl)
                    .parse()

            val progressIndicator = object : StandardProgressIndicatorBase() {

                override fun setText2(text: String?) {
                    viewModelScope.launch {
                        _indexingState.emit(
                            _indexingState.value!!.copy(
                                text = text ?: ""
                            )
                        )
                    }
                }

                override fun setFraction(fraction: Double) {
                    viewModelScope.launch {
                        _indexingState.emit(
                            _indexingState.value!!.copy(
                                fraction = fraction
                            )
                        )
                    }
                }
            }



            viewModelScope.launch(Dispatchers.IO) {
                _indexingState.emit(IndexingState("Initializing indexing framework", 0.0))

                ProgressManager.getInstance().executeProcessUnderProgress({
                    val fileBasedIndex = FileBasedIndex.getInstance() as CoreFileBasedIndex
                    fileBasedIndex.loadIndexes()

                    val stubIndex = StubIndex.getInstance() as CoreStubIndex

                    stubIndex.initializeStubIndexes()

                    fileBasedIndex.waitUntilIndicesAreInitialized()
                    fileBasedIndex.registeredIndexes.extensionsDataWasLoaded()

                    ProjectIndexer.index(
                        projectEnvironment.project,
                        fileBasedIndex
                    )
                }, progressIndicator)

                println("Saving indices")
                (StubIndex.getInstance() as CoreStubIndex).flush()
                (FileBasedIndex.getInstance() as CoreFileBasedIndex).flush()
                FileIdStorage.saveIds()
                FSRecords.flush()
                IndexingStamp.flushCaches()

                println("Done saving indices")

                _indexingState.emit(null)

                _projectState.emit(
                    ProjectState(
                        initialized = true,
                        projectPath = projectPath,
                        projectName = projectDir.name,
                        showProgressBar = false
                    )
                )
            }


            val previouslyOpenedFiles = ApplicationLoader.getDefaultPreferences()
                .getStringSet(projectDir.path, emptySet())!!
            _projectState.emit(
                ProjectState(
                    initialized = false,
                    projectPath = projectPath,
                    projectName = projectDir.name,
                    showProgressBar = false
                )
            )

            openFile(projectDir.path.plus("/app/src/main/java/com/tyron/MainActivity.java"))
        }
    }

    override fun onCleared() {
        super.onCleared()

        fileOpenSubscriptionReceipt.unsubscribe()
    }

    fun setRoot(rootPath: String) {
        viewModelScope.launch {
            val file = File(rootPath)
            _treeNode.emit(TreeNode.root(TreeUtil.getNodes(file)))
        }
    }

    fun openFile(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val existing = _textEditorListState.value.editors.find { it.file.path == path }
            if (existing != null) {
                _currentTextEditorState.emit(existing)
                return@launch
            }

            val vFile = projectEnvironment.environment.localFileSystem
                .findFileByPath(path) ?: return@launch

            val fileType = FileTypeRegistry.getInstance().getFileTypeByFile(vFile)
            val loadedText = LoadTextUtil.loadText(vFile)
            val content = Content(loadedText)

            val newEditorState = TextEditorState(
                file = vFile,
                fileType = fileType,
                loading = false
            )

            _currentTextEditorState.emit(
                newEditorState
            )

            val list = _textEditorListState.value.editors.toMutableList()
            list.add(newEditorState)

            _textEditorListState.emit(
                TextEditorListState(
                    editors = list
                )
            )
        }
    }

    fun onTabSelected(pos: Int) {
        assert(textEditorListState.value.editors.size >= pos)

        viewModelScope.launch {
            val textEditorState = textEditorListState.value.editors[pos]
            _currentTextEditorState.emit(textEditorState)
        }
    }
}

data class IndexingState(
    val text: String = "",

    val fraction: Double = 0.0
)

data class ProjectState(
    val initialized: Boolean = false,

    val projectPath: String? = null,

    val projectName: String? = null,

    val showProgressBar: Boolean = true,

    val progressBarIndeterminate: Boolean = true,

    val progressBarFraction: Double = 0.0,
)

data class TextEditorListState(
    val editors: List<TextEditorState> = emptyList()
)

data class TextEditorState(
    val file: VirtualFile,
    val loading: Boolean = true,

    val fileType: FileType? = null
) : java.io.Serializable