package com.tyron.code.ui.layoutEditor.attributeEditor;

import android.util.Pair;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;

import java.util.List;

public class AttributeEditorViewModel extends ViewModel {

    private MutableLiveData<List<Pair<String, Value>>> mLayout = new MutableLiveData<>();

    public LiveData<List<Pair<String, Value>>> getLayout() {
        return mLayout;
    }

    public void setLayout(List<Pair<String, Value>> layout) {
        mLayout.setValue(layout);
    }
}
