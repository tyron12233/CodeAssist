package com.tyron.code.ui.editor

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tyron.completion.java.JavaCompletionProvider
import com.tyron.completion.model.CompletionList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

class EditorViewModel : ViewModel() {
    private val mAnalyzeState = MutableLiveData(false)

    fun setAnalyzeState(analyzing: Boolean) {
        mAnalyzeState.value = analyzing
    }

    val analyzeState: LiveData<Boolean>
        get() = mAnalyzeState

}