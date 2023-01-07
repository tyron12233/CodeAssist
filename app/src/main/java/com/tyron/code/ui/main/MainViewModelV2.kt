package com.tyron.code.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyron.code.ApplicationLoader
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorFacade
import com.tyron.code.ui.file.tree.TreeUtil
import com.tyron.code.ui.file.tree.model.TreeFile
import com.tyron.completion.java.CompletionModule
import com.tyron.ui.treeview.TreeNode
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.com.intellij.core.JavaCoreProjectEnvironment
import org.jetbrains.kotlin.com.intellij.lang.jvm.facade.JvmElementProvider
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.impl.LoadTextUtil
import org.jetbrains.kotlin.com.intellij.openapi.roots.FileIndexFacade
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.NotNullLazyValue
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFinder
import org.jetbrains.kotlin.com.intellij.psi.PsiPackage
import org.jetbrains.kotlin.com.intellij.psi.impl.BlockSupportImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiTreeChangePreprocessor
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManager
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.FileManagerImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.file.impl.JavaFileManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.text.BlockSupport
import java.io.File


class MainViewModelV2(projectPath: String) : ViewModel() {

    private val _projectState = MutableStateFlow(ProjectState())
    val projectState: StateFlow<ProjectState> = _projectState

    private val _treeNode = MutableStateFlow<TreeNode<TreeFile>?>(null)
    val treeNode = _treeNode.asStateFlow()

    private val _textEditorListState = MutableStateFlow(TextEditorListState())
    val textEditorListState = _textEditorListState.asStateFlow()

    private val _currentTextEditorState = MutableStateFlow<TextEditorState?>(null)
    val currentTextEditorState = _currentTextEditorState.asStateFlow()

    lateinit var projectEnvironment: JavaCoreProjectEnvironment

    init {
        val handler = CoroutineExceptionHandler { _, exception ->
            println("Error: $exception")
        }

        viewModelScope.launch(handler) {
            val disposable = Disposer.newDisposable()
            val appEnvironment = ApplicationLoader.getInstance().coreApplicationEnvironment
            projectEnvironment = JavaCoreProjectEnvironment(disposable, appEnvironment)

            projectEnvironment.project.registerService(
                BlockSupport::class.java,
                BlockSupportImpl()
            )
            projectEnvironment.registerProjectComponent(
                FileManager::class.java, FileManagerImpl(
                    PsiManagerImpl.getInstance(projectEnvironment.project) as PsiManagerImpl,
                    NotNullLazyValue.createValue {
                        FileIndexFacade.getInstance(projectEnvironment.project)
                    })
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
                val root = TreeNode.root(TreeUtil.getNodes(File(projectPath)))
                _treeNode.emit(root)
            }

            projectEnvironment.addSourcesToClasspath(projectDir)
            projectEnvironment.addJarToClassPath(CompletionModule.getAndroidJar())
            projectEnvironment.addJarToClassPath(CompletionModule.getLambdaStubs())

            val previouslyOpenedFiles = ApplicationLoader.getDefaultPreferences()
                .getStringSet(projectDir.path, emptySet())!!

            _projectState.emit(
                ProjectState(
                    initialized = true,
                    projectPath = projectPath,
                    showProgressBar = false
                )
            )

            openFile(projectDir.path.plus("/app/src/main/java/com/tyron/MainActivity.java"))
        }
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


            val loadedText = LoadTextUtil.loadText(vFile)
            val content = Content(loadedText)

            val newEditorState = TextEditorState(
                file = vFile,
                content = content,
                loading = false
            )

            _currentTextEditorState.emit(
                newEditorState
            )
        }
    }
}

data class ProjectState(
    val initialized: Boolean = false,

    val projectPath: String? = null,

    val showProgressBar: Boolean = true
)

data class TextEditorListState(
    val editors: List<TextEditorState> = emptyList()
)

data class TextEditorState(
    val file: VirtualFile, val content: Content,

    val loading: Boolean = true
)