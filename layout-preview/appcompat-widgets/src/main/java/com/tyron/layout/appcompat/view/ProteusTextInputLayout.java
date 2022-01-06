package com.tyron.layout.appcompat.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusView;
import com.google.android.material.textfield.TextInputLayout;

public class ProteusTextInputLayout extends TextInputLayout implements ProteusView {

    private Manager manager;

    public ProteusTextInputLayout(@NonNull Context context) {
        super(context);
    }

    public ProteusTextInputLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ProteusTextInputLayout(@NonNull Context context, @Nullable AttributeSet attrs,
                                  int defStyleAttr) {
        super(context, attrs, defStyleAttr);
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
