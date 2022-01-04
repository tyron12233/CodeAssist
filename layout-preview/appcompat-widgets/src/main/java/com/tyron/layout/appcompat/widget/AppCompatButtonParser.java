package com.tyron.layout.appcompat.widget;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.view.ViewCompat;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.tyron.layout.appcompat.view.ProteusAppCompatButton;

public class AppCompatButtonParser<V extends View> extends ViewTypeParser<V> {
    @NonNull
    @Override
    public String getType() {
        return AppCompatButton.class.getName();
    }

    @Nullable
    @Override
    public String getParentType() {
        return Button.class.getName();
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusAppCompatButton(context);
    }

    @Override
    protected void addAttributeProcessors() {

    }
}
