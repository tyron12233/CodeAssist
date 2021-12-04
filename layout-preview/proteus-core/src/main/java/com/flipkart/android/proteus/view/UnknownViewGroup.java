package com.flipkart.android.proteus.view;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;

public class UnknownViewGroup extends FrameLayout implements ProteusView {

    private Manager manager;
    private final UnknownView mView;

    public UnknownViewGroup(@NonNull Context context) {
        this(context, "");
    }

    public UnknownViewGroup(@NonNull Context context, String className) {
        super(context);

        mView = new UnknownView(context, className);
        addView(mView, new LayoutParams(-2, -2, Gravity.CENTER));
    }

    @Override
    public void addView(View child) {
        super.addView(child);
        mView.bringToFront();
    }

    @Override
    public void addView(View child, int index) {
        super.addView(child, index);
        mView.bringToFront();
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        super.addView(child, params);
        mView.bringToFront();
    }

    @Override
    public void addView(View child, int width, int height) {
        super.addView(child, width, height);
        mView.bringToFront();
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        mView.bringToFront();
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
