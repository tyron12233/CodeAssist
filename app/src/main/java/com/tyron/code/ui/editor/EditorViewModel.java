package com.tyron.code.ui.editor;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class EditorViewModel extends ViewModel {

    private final MutableLiveData<Boolean> mAnalyzeState = new MutableLiveData<>(false);

    public void setAnalyzeState(boolean analyzing) {
        mAnalyzeState.setValue(analyzing);
    }

    public LiveData<Boolean> getAnalyzeState() {
        return mAnalyzeState;
    }
}
