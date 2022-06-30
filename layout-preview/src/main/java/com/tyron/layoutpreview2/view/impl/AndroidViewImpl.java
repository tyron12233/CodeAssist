package com.tyron.layoutpreview2.view.impl;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview2.ViewManager;
import com.tyron.layoutpreview2.view.EditorView;

public class AndroidViewImpl extends View implements EditorView {

    private ViewManager mViewManager;

    public AndroidViewImpl(Context context) {
        super(context);
    }

    @NonNull
    @Override
    public View getAsView() {
        return this;
    }

    @NonNull
    @Override
    public ViewManager getViewManager() {
        return mViewManager;
    }

    @Override
    public void setViewManager(@NonNull ViewManager manager) {
        mViewManager = manager;
    }
}
