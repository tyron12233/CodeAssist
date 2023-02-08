package com.tyron.code.ui.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyron.code.ApplicationLoader
import com.tyron.code.event.SubscriptionReceipt
import com.tyron.code.ui.editor.DummyCodeStyleManager
import org.jetbrains.kotlin.com.intellij.pom.MockPomModel
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorFacade
import com.tyron.code.ui.file.event.OpenFileEvent
import com.tyron.code.ui.file.event.RefreshRootEvent
import com.tyron.code.ui.file.tree.TreeUtil
import com.tyron.code.ui.file.tree.model.TreeFile
import com.tyron.completion.java.CompletionModule
import com.tyron.completion.psi.search.PsiShortNamesCache
import com.tyron.ui.treeview.TreeNode
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.cli.jvm.compiler.MockExternalAnnotationsManager
import org.jetbrains.kotlin.cli.jvm.compiler.MockInferredAnnotationsManager
import org.jetbrains.kotlin.com.intellij.codeInsight.ExternalAnnotationsManager
import org.jetbrains.kotlin.com.intellij.codeInsight.InferredAnnotationsManager
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment
import org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.LoadTextUtil
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileTypeRegistry
import org.jetbrains.kotlin.com.intellij.openapi.project.CodeAssistProject
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.pom.PomModel
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiNameHelperImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManager
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport
import org.jetbrains.kotlin.com.intellij.util.Processor
import org.jetbrains.kotlin.org.picocontainer.PicoContainer
import java.io.File


class MainViewModelV2(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val projectPath by lazy { savedStateHandle.get<String>("project_path")!!     }

    private val _projectState = MutableStateFlow(ProjectState())
    val projectState: StateFlow<ProjectState> = _projectState

    private val _treeNode = MutableStateFlow<TreeNode<TreeFile>?>(null)
    val treeNode = _treeNode.asStateFlow()

    private val _textEditorListState = MutableStateFlow(TextEditorListState())
    val textEditorListState = _textEditorListState.asStateFlow()

    private val _currentTextEditorState = MutableStateFlow<TextEditorState?>(null)
    val currentTextEditorState = _currentTextEditorState.asStateFlow()

    lateinit var projectEnvironment: JavaCoreProjectEnvironment

    private var fileOpenSubscriptionReceipt: SubscriptionReceipt<OpenFileEvent>

    init {

        fileOpenSubscriptionReceipt = ApplicationLoader.getInstance().eventManager.subscribeEvent(OpenFileEvent::class.java) { event, _ ->
            openFile(event.file.absolutePath)
        }

        val handler = CoroutineExceptionHandler { _, exception ->
            println("Error: $exception")
        }
        viewModelScope.launch(handler) {
            val disposable = Disposer.newDisposable()
            val appEnvironment = ApplicationLoader.getInstance().coreApplicationEnvironment
            projectEnvironment = object : JavaCoreProjectEnvironment(disposable, appEnvironment) {
                override fun createProject(
                    parent: PicoContainer,
                    parentDisposable: Disposable
                ): CodeAssistProject {
                    val fileByPath: VirtualFile =
                        appEnvironment.localFileSystem.findFileByPath(projectPath)!!
                    return CodeAssistProject(parent, parentDisposable, fileByPath)
                }
            }

            registerComponentsAndServices()
            registerExtensionPoints()

            projectEnvironment.addProjectExtension(
                PsiElementFinder.EP_NAME,
                object : PsiElementFinder() {

                    private val fileManager =
                        JavaFileManager.getInstance(projectEnvironment.project)

                    override fun findClass(className: String, scope: GlobalSearchScope): PsiClass? {
                        return fileManager.findClass(className, scope)
                    }

                    override fun findClasses(
                        className: String,
                        globalSearchScope: GlobalSearchScope
                    ): Array<PsiClass> {
                        return fileManager.findClasses(className, globalSearchScope)
                    }

                    override fun findPackage(qualifiedName: String): PsiPackage? {
                        return fileManager.findPackage(qualifiedName)
                    }

                    override fun getClassNames(
                        psiPackage: PsiPackage,
                        scope: GlobalSearchScope
                    ): MutableSet<String> {
                        return super.getClassNames(psiPackage, scope)
                    }
                }
            )

            RosemoeEditorFacade.project = projectEnvironment.project
            RosemoeEditorFacade.projectEnvironment = projectEnvironment

            val localFs = appEnvironment.localFileSystem;
            val projectDir = localFs.findFileByPath(projectPath)!!



            viewModelScope.launch(Dispatchers.IO) {
                ApplicationLoader.getInstance().eventManager.dispatchEvent(RefreshRootEvent(File(projectPath)))
            }

            val javaSourceRoot = projectDir.findFileByRelativePath("app/src/main/java")
            javaSourceRoot?.let(projectEnvironment::addSourcesToClasspath)

            projectEnvironment.addSourcesToClasspath(projectDir)
            projectEnvironment.addJarToClassPath(CompletionModule.getAndroidJar())
            projectEnvironment.addJarToClassPath(CompletionModule.getLambdaStubs())

            val previouslyOpenedFiles = ApplicationLoader.getDefaultPreferences()
                .getStringSet(projectDir.path, emptySet())!!

            _projectState.emit(
                ProjectState(
                    initialized = true,
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

    private fun registerComponentsAndServices() {
        val project = projectEnvironment.project

        project.registerService(
            PsiShortNamesCache::class.java,
            object : PsiShortNamesCache() {
                override fun getClassesByName(
                    name: String,
                    scope: GlobalSearchScope
                ): Array<PsiClass> {
                    return emptyArray()
                }

                override fun getAllClassNames(): Array<String> {
                    return emptyArray()
                }

                override fun getMethodsByName(
                    name: String,
                    scope: GlobalSearchScope
                ): Array<PsiMethod> {
                    return emptyArray()
                }

                override fun getMethodsByNameIfNotMoreThan(
                    name: String,
                    scope: GlobalSearchScope,
                    maxCount: Int
                ): Array<PsiMethod> {
                    return emptyArray()
                }

                override fun getFieldsByNameIfNotMoreThan(
                    name: String,
                    scope: GlobalSearchScope,
                    maxCount: Int
                ): Array<PsiField> {
                    return emptyArray()
                }

                override fun processMethodsWithName(
                    name: String,
                    scope: GlobalSearchScope,
                    processor: Processor<in PsiMethod>
                ): Boolean {
                    return false
                }

                override fun getAllMethodNames(): Array<String> {
                    return emptyArray()
                }

                override fun getFieldsByName(
                    name: String,
                    scope: GlobalSearchScope
                ): Array<PsiField> {
                    return emptyArray()
                }

                override fun getAllFieldNames(): Array<String> {
                    return emptyArray()
                }

            }
        )

        project.registerService(
            PomModel::class.java,
            MockPomModel.newInstance(project)
        )

        project.registerService(
            ExternalAnnotationsManager::class.java,
            MockExternalAnnotationsManager()
        )

        project.registerService(
            InferredAnnotationsManager::class.java,
            MockInferredAnnotationsManager()
        )

        project.registerService(
            BlockSupport::class.java,
            BlockSupportImpl()
        )

        project.registerService(
            PsiNameHelper::class.java,
            PsiNameHelperImpl(project)
        )
        
        project.registerService(
            CodeStyleManager::class.java,
            DummyCodeStyleManager(project)
        )
        
        projectEnvironment.registerProjectComponent(
            FileManager::class.java, FileManagerImpl(
                PsiManagerImpl.getInstance(project) as PsiManagerImpl,
                NotNullLazyValue.createValue {
                    FileIndexFacade.getInstance(project)
                })
        )
    }

    private fun registerExtensionPoints() {
        projectEnvironment.project.extensionArea.registerExtensionPoint(
            PsiTreeChangeListener.EP.name,
            PsiTreeChangeListener::class.qualifiedName!!,
            ExtensionPoint.Kind.INTERFACE
        )
        projectEnvironment.registerProjectExtensionPoint(
            PsiShortNamesCache.EP_NAME,
            PsiShortNamesCache::class.java
        )
        projectEnvironment.registerProjectExtensionPoint(
            PsiTreeChangePreprocessor.EP_NAME,
            PsiTreeChangePreprocessor::class.java
        )
        projectEnvironment.registerProjectExtensionPoint(
            JvmElementProvider.EP_NAME,
            JvmElementProvider::class.java
        )
        projectEnvironment.registerProjectExtensionPoint(
            PsiElementFinder.EP_NAME,
            PsiElementFinder::class.java
        )
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
                content = content,
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

data class ProjectState(
    val initialized: Boolean = false,

    val projectPath: String? = null,

    val projectName: String? = null,

    val showProgressBar: Boolean = true
)

data class TextEditorListState(
    val editors: List<TextEditorState> = emptyList()
)

data class TextEditorState(
    val file: VirtualFile, val content: Content,

    val loading: Boolean = true,

    val fileType: FileType? = null
)