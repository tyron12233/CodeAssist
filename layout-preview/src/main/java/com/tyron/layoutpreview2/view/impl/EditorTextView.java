package com.tyron.layoutpreview2.view.impl;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.tyron.layoutpreview2.ViewManager;
import com.tyron.layoutpreview2.view.EditorView;

public class EditorTextView extends TextView implements EditorView {

    private ViewManager mViewManger;

    public EditorTextView(Context context) {
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
        return mViewManger;
    }

    @Override
    public void setViewManager(@NonNull ViewManager manager) {
        mViewManger = manager;
    }
}
