package com.tyron.layout.constraintlayout.view;

import static com.flipkart.android.proteus.ProteusView.*;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.flipkart.android.proteus.ProteusView;

public class ProteusConstraintLayout extends ConstraintLayout implements ProteusView {

    private Manager manager;

    public ProteusConstraintLayout(Context context) {
        super(context);
    }

    @Override
    public Manager getViewManager() {
        return manager;
    }

    @NonNull
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
