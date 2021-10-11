package com.tyron.layout.appcompat.view;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import com.flipkart.android.proteus.ProteusView;

public class ProteusNestedScrollView extends NestedScrollView implements ProteusView {

    private Manager manager;

    public ProteusNestedScrollView(@NonNull Context context) {
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

    @Override
    public void addView(View child, int width, int height) {
        if (getChildCount() > 1) {
            return;
        }
        super.addView(child, width, height);
    }

    @Override
    public void addView(View child) {
        if (getChildCount() > 1) {
            return;
        }
        super.addView(child);
    }

    @Override
    public void addView(View child, int index) {
        if (getChildCount() > 1) {
            return;
        }
        super.addView(child, index);
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        if (getChildCount() > 1) {
            return;
        }
        super.addView(child, params);
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (getChildCount() > 1) {
            return;
        }
        super.addView(child, index, params);
    }

    @Override
    protected boolean addViewInLayout(View child, int index, ViewGroup.LayoutParams params) {
        if (getChildCount() > 1) {
            return false;
        }
        return super.addViewInLayout(child, index, params);
    }

    @Override
    protected boolean addViewInLayout(View child, int index, ViewGroup.LayoutParams params, boolean preventRequestLayout) {
        if (getChildCount() > 1) {
            return false;
        }
        return super.addViewInLayout(child, index, params, preventRequestLayout);
    }
}
