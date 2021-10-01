package com.tyron.layoutpreview.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import com.tyron.layoutpreview.model.CustomView;

@SuppressLint("ViewConstructor")
public class UnknownView extends TextView {

    @SuppressLint("SetTextI18n")
    public UnknownView(Context context, CustomView customView) {
        super(context);

        setText("Unknown view: " + customView.getType());
    }
}
