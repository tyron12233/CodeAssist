package com.tyron.layoutpreview.view;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;

public class CustomViewGroupWrapper extends ViewGroup implements ProteusView {

    private final ViewGroup mView;
    private Manager manager;

    public CustomViewGroupWrapper(Context context, ViewGroup wrapped) {
        super(context);

        mView = wrapped;
    }

    public View getView() {
        return mView;
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
        return mView;
    }

    @Override
    protected void onLayout(boolean b, int i, int i1, int i2, int i3) {

    }
}
