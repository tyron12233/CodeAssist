package com.tyron.layoutpreview.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.view.UnknownView;
import com.tyron.layoutpreview.model.CustomView;

import java.lang.reflect.InvocationTargetException;

@SuppressLint("ViewConstructor")
public class CustomViewWrapper extends View implements ProteusView {

    private Manager manager;
    private CustomView mCustomView;
    private View mView;

    public CustomViewWrapper(Context context, CustomView customView) {
        super(context);
        mCustomView = customView;

        try {
            Class<? extends View> viewClass = mCustomView.getViewClass(context.getClassLoader());
            mView = viewClass.getConstructor(Context.class)
                    .newInstance(context);
        } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | InvocationTargetException | IllegalAccessException e) {
            mView = new UnknownView(context, "Unknown view: " + mCustomView.getType());
        }
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

    public View getView() {
        return mView;
    }

}
