package com.flipkart.android.proteus.parser.appcompat;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layoutpreview.view.UnknownViewGroup;

public class CoordinatorLayoutParser<T extends View> extends ViewTypeParser<T> {

    @NonNull
    @Override
    public String getType() {
        return "androidx.coordinatorlayout.widget.CoordinatorLayout";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.widget.FrameLayout";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new UnknownViewGroup(context);
    }

    @Override
    protected void addAttributeProcessors() {

    }
}
