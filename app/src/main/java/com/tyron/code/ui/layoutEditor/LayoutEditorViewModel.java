package com.tyron.code.ui.layoutEditor;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.tyron.code.ui.layoutEditor.model.ViewPalette;

import java.util.ArrayList;
import java.util.List;

public class LayoutEditorViewModel extends ViewModel {

    private MutableLiveData<List<ViewPalette>> mPalettes = new MutableLiveData<>(new ArrayList<>());

    public LiveData<List<ViewPalette>> getPalettes() {
        return mPalettes;
    }

    public void setPalettes(List<ViewPalette> palettes) {
        mPalettes.setValue(palettes);
    }
}
