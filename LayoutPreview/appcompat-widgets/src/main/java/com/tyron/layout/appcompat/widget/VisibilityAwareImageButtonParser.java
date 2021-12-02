package com.tyron.layout.appcompat.widget;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.internal.VisibilityAwareImageButton;
import com.tyron.layout.appcompat.view.ProetusVisibilityAwareImageButton;

public class VisibilityAwareImageButtonParser<V extends View> extends ViewTypeParser<V> {

    @NonNull
    @Override
    public String getType() {
        return VisibilityAwareImageButton.class.getName();
    }

    @Nullable
    @Override
    public String getParentType() {
        return ImageButton.class.getName();
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProetusVisibilityAwareImageButton(context);
    }

    @Override
    protected void addAttributeProcessors() {

    }
}
