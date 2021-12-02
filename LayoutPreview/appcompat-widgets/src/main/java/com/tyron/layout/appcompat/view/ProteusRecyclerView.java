package com.tyron.layout.appcompat.view;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.flipkart.android.proteus.ProteusView;

public class ProteusRecyclerView extends RecyclerView implements ProteusView {

    private Manager manager;

    public ProteusRecyclerView(@NonNull Context context) {
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
