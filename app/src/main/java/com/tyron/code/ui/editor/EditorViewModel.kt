package com.tyron.code.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyron.code.ui.legacyEditor.EditorChangeUtil
import io.github.rosemoe.sora.text.Content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.editor.impl.DocumentImpl
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase

class EditorViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    val disposable = Disposer.newDisposable()

    @Suppress("DEPRECATION")
    val project by lazy { ProjectCoreUtil.theOnlyOpenProject()!! }
    private val psiDocumentManager = PsiDocumentManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()
    private val localFileManager = StandardFileSystems.local()

    private val filePath: String = savedStateHandle["filePath"]!!

    private val _editorState = MutableStateFlow(InternalEditorState())
    val editorState = _editorState.asStateFlow()

    private lateinit var document: Document
    private lateinit var content: Content
    private lateinit var synchronizer: DocumentContentSynchronizer

    init {
        loadFile()
    }

    private fun loadFile() {
        viewModelScope.launch {
            val file = localFileManager.findFileByPath(filePath)
            if (file == null) {
                errorLoading("{$filePath} not found.")
                return@launch
            }

            val document = fileDocumentManager.getDocument(file)
            if (document == null) {
                errorLoading("Document for file $file not found")
                return@launch
            }
            this@EditorViewModel.document = document
            this@EditorViewModel.content = Content(document.text)

            document.addDocumentListener(psiDocumentManager as PsiDocumentManagerBase)
            document.addDocumentListener(psiDocumentManager.PriorityEventCollector())

            synchronizer = DocumentContentSynchronizer(project, document, content)
            synchronizer.start()

            _editorState.emit(
                InternalEditorState(
                    loadingContent = false,
                    loadingErrorMessage = null,
                    editorContent = this@EditorViewModel.content,
                    editorDocument = document
                )
            )
        }
    }
    private suspend fun errorLoading(message: String) {
        _editorState.emit(
            InternalEditorState(
                loadingContent = false,
                loadingErrorMessage = message
            )
        )
    }

    override fun onCleared() {
        super.onCleared()

        Disposer.dispose(disposable)
    }

    data class InternalEditorState(
        val loadingContent: Boolean = false,
        val loadingErrorMessage: String? = null,
        val editorContent: Content = Content(),
        val editorDocument: Document = DocumentImpl("")
    )
}