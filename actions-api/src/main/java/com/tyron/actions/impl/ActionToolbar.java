package com.tyron.actions.impl;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;

import com.tyron.actions.DataContext;

/**
 * A toolbar that holds a {@link DataContext}
 */
public class ActionToolbar extends Toolbar {

    public ActionToolbar(@NonNull Context context) {
        this(DataContext.wrap(context), null);
    }

    public ActionToolbar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(DataContext.wrap(context), attrs);
    }

    public ActionToolbar(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(DataContext.wrap(context), attrs, defStyleAttr);
    }
}
