package com.tyron.layout.appcompat.view;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;
import com.google.android.material.internal.VisibilityAwareImageButton;

public class ProetusVisibilityAwareImageButton extends VisibilityAwareImageButton
        implements ProteusView {

    public ProetusVisibilityAwareImageButton(Context context) {
        super(context);
    }

    private Manager manager;

    @Override
    public Manager getViewManager() {
        return manager;
    }

    @Override
    public void setViewManager(@NonNull Manager manager) {
        this.manager = manager;
    }

    @NonNull
    @Override
    public View getAsView() {
        return this;
    }
}
