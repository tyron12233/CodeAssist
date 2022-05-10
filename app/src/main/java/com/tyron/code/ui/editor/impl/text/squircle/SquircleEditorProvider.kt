package com.tyron.code.ui.editor.impl.text.squircle

import android.content.Context
import com.tyron.fileeditor.api.FileEditor
import com.tyron.fileeditor.api.FileEditorProvider
import java.io.File

class SquircleEditorProvider : FileEditorProvider {

    override fun accept(file: File): Boolean {
        return true
    }

    override fun createEditor(context: Context, file: File): FileEditor {
        return SquircleEditor(context, file, this)
    }

    override fun getEditorTypeId(): String {
        return "squircle-editor"
    }
}