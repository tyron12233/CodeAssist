package com.flipkart.android.proteus.view.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;

public class ProteusListView extends ListView implements ProteusView {

    private Manager manager;

    public ProteusListView(Context context) {
        super(context);
    }

    public ProteusListView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ProteusListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ProteusListView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
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
