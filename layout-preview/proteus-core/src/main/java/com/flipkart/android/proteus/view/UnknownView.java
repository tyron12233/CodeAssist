package com.flipkart.android.proteus.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;

public class UnknownView extends TextView implements ProteusView {

    private Manager mManager;

    public UnknownView(Context context) {
        this(context, "");
    }

    public UnknownView(Context context, String message) {
        super(context);
        setGravity(Gravity.CENTER);
        setText(message);
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
