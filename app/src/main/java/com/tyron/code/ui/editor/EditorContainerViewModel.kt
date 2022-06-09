package com.tyron.code.ui.editor

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.tyron.code.ApplicationLoader
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora2.text.EditorUtil

class EditorContainerViewModel : ViewModel() {

    private val _editorTheme = MutableLiveData<EditorColorScheme>()
    val editorTheme: LiveData<EditorColorScheme>
        get() = _editorTheme

    init {
        _editorTheme.value = EditorUtil.getDefaultColorScheme(ApplicationLoader.applicationContext)
    }
}