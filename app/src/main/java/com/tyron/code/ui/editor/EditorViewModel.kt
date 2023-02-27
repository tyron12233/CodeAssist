package com.tyron.code.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyron.code.ui.legacyEditor.EditorChangeUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document
import org.jetbrains.kotlin.com.intellij.openapi.fileEditor.FileDocumentManager
import org.jetbrains.kotlin.com.intellij.openapi.project.ProjectCoreUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.psi.PsiDocumentManager
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiDocumentManagerBase

class EditorViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {

    @Suppress("DEPRECATION")
    private val project by lazy { ProjectCoreUtil.theOnlyOpenProject()!! }
    private val psiDocumentManager = PsiDocumentManager.getInstance(project)
    private val fileDocumentManager = FileDocumentManager.getInstance()
    private val localFileManager = StandardFileSystems.local()

    private val filePath: String = savedStateHandle["filePath"]!!

    private val _editorState = MutableStateFlow(InternalEditorState())
    val editorState = _editorState.asStateFlow()

    private lateinit var document: Document

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

            document.addDocumentListener(psiDocumentManager as PsiDocumentManagerBase)
            document.addDocumentListener(psiDocumentManager.PriorityEventCollector())

            _editorState.emit(
                InternalEditorState(
                    loadingContent = false,
                    loadingErrorMessage = null,
                    editorContent = document.text
                )
            )
        }
    }

    fun commit(action: Int, start: Int, end: Int, text: CharSequence) {
        EditorChangeUtil.doCommit(action, start, end, text, project, document)
    }

    private suspend fun errorLoading(message: String) {
        _editorState.emit(
            InternalEditorState(
                loadingContent = false,
                loadingErrorMessage = message
            )
        )
    }

    data class InternalEditorState(
        val loadingContent: Boolean = false,
        val loadingErrorMessage: String? = null,
        val editorContent: String = ""
    )
}