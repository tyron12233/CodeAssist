package com.tyron.layout.appcompat.widget;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layout.appcompat.view.ProteusNestedScrollView;

public class NestedScrollViewParser<T extends View> extends ViewTypeParser<T> {

    @NonNull
    @Override
    public String getType() {
        return "androidx.core.widget.NestedScrollView";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.widget.FrameLayout";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusNestedScrollView(context);
    }

    @Override
    protected void addAttributeProcessors() {
        addAttributeProcessor(Attributes.HorizontalScrollView.FillViewPort, new BooleanAttributeProcessor<T>() {
            @Override
            public void setBoolean(T view, boolean value) {
                if (view instanceof NestedScrollView) {
                    ((NestedScrollView) view).setFillViewport(value);
                }
            }
        });
    }
}
