package com.tyron.layout.appcompat.view;

import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.google.android.material.appbar.AppBarLayout;

import androidx.annotation.NonNull;

/**
 * ProteusAppBarLayout
 *
 * @author adityasharat
 */

public class ProteusAppBarLayout extends AppBarLayout implements ProteusView {

    private Manager manager;

    public ProteusAppBarLayout(ProteusContext context) {
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