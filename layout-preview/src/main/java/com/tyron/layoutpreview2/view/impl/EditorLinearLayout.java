package com.tyron.layoutpreview2.view.impl;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview2.ViewManager;
import com.tyron.layoutpreview2.view.EditorView;

public class EditorLinearLayout extends LinearLayout implements EditorView {

    private ViewManager mViewManager;

    public EditorLinearLayout(Context context) {
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
