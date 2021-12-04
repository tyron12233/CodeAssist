package com.tyron.layout.appcompat.view;

import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.google.android.material.appbar.CollapsingToolbarLayout;

import androidx.annotation.NonNull;

/**
 * ProteusCollapsingToolbarLayout
 *
 * @author adityasharat
 */

public class ProteusCollapsingToolbarLayout extends CollapsingToolbarLayout implements ProteusView {

    private Manager manager;

    public ProteusCollapsingToolbarLayout(ProteusContext context) {
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
