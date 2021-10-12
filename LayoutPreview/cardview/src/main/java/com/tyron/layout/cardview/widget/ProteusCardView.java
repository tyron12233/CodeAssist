package com.tyron.layout.cardview.widget;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;

import com.flipkart.android.proteus.ProteusView;

public class ProteusCardView extends CardView implements ProteusView {

    private Manager manager;

    public ProteusCardView(@NonNull Context context) {
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
