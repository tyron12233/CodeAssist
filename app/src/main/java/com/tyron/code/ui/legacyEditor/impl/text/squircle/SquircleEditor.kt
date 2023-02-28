package com.tyron.code.ui.legacyEditor.impl.text.squircle

import android.content.Context
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import com.tyron.fileeditor.api.FileDocumentManager
import com.tyron.fileeditor.api.FileEditor
import org.apache.commons.vfs2.VFS
import java.io.File

class SquircleEditor(
    val context: Context,
    private val ioFile: File,
    val provider: SquircleEditorProvider
) : FileEditor {

    private val editorView = TextProcessor(context)

    init {
        val fileObject = VFS.getManager().toFileObject(ioFile)
        val content = FileDocumentManager.getInstance().getContent(fileObject)
        val contentPlugin = ContentImpl(content!!)

        editorView.text = contentPlugin
        editorView.installPlugin(contentPlugin)
    }

    override fun getView() = editorView

    override fun getPreferredFocusedView() = view

    override fun getName() = "Squircle Editor"

    override fun isModified(): Boolean {
        return false
    }

    override fun isValid(): Boolean {
        return true
    }

    override fun getFile(): File {
        return ioFile
    }
}