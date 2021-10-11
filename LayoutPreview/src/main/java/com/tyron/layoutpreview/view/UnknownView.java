package com.tyron.layoutpreview.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;
import com.tyron.layoutpreview.model.CustomView;

public class UnknownView extends TextView implements ProteusView {

    private Manager mManager;

    public UnknownView(Context context) {
        this(context, "");
    }

    public UnknownView(Context context, CustomView customView) {
        this(context, customView.getType());
    }

    @SuppressLint("SetTextI18n")
    public UnknownView(Context context, String type) {
        super(context);

        setText("Unknown view: " + type);
    }


    @Override
    public Manager getViewManager() {
        return mManager;
    }

    @Override
    public void setViewManager(@NonNull Manager manager) {
        mManager = manager;
    }

    @NonNull
    @Override
    public View getAsView() {
        return this;
    }
}
