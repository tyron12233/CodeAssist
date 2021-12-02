package com.tyron.layout.appcompat.view;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class ProteusFloatingActionButton extends FloatingActionButton implements ProteusView {

    private Manager manager;

    public ProteusFloatingActionButton(@NonNull Context context) {
        super(context);
    }

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
