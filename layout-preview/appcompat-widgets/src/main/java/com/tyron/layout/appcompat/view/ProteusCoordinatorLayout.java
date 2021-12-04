package com.tyron.layout.appcompat.view;

import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

/**
 * ProteusCoordinatorLayout
 *
 * @author adityasharat
 */

public class ProteusCoordinatorLayout extends CoordinatorLayout implements ProteusView {

    private Manager manager;

    public ProteusCoordinatorLayout(ProteusContext context) {
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