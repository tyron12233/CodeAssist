package com.tyron.layout.appcompat.widget;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.toolbox.Attributes;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layout.appcompat.AppCompatModuleAttributeHelper;
import com.tyron.layout.appcompat.view.ProteusCoordinatorLayout;

/**
 * CoordinatorLayoutParser
 *
 * @author adityasharat
 */

public class CoordinatorLayoutParser<V extends View> extends ViewTypeParser<V> {

    @NonNull
    @Override
    public String getType() {
        return "androidx.coordinatorlayout.widget.CoordinatorLayout";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.view.ViewGroup";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusCoordinatorLayout(context);
    }

    @Override
    protected void addAttributeProcessors() {

        addAttributeProcessor("statusBarBackground", new DrawableResourceProcessor<V>() {
            @Override
            public void setDrawable(V view, Drawable drawable) {
                if (view instanceof CoordinatorLayout) {
                    ((CoordinatorLayout) view).setStatusBarBackground(drawable);
                }
            }
        });

        addAttributeProcessor("fitSystemWindows", new BooleanAttributeProcessor<V>() {
            @Override
            public void setBoolean(V view, boolean value) {
                if (view instanceof CoordinatorLayout) {
                    view.setFitsSystemWindows(value);
                }
            }
        });

        addAttributeProcessor("app:layout_behavior", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                if (view.getLayoutParams() instanceof CoordinatorLayout.LayoutParams) {
                    AppCompatModuleAttributeHelper.CoordinatorLayoutParamsHelper.setLayoutBehavior(view, value);
                }
            }
        });

        addAttributeProcessor("app:layout_anchor", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                if (view.getLayoutParams() instanceof CoordinatorLayout.LayoutParams) {
                    ((CoordinatorLayout.LayoutParams) view.getLayoutParams()).setAnchorId(
                            ProteusHelper.getProteusContext(view).getInflater().getUniqueViewId(value));
                }
            }
        });

        addAttributeProcessor("app:layout_anchorGravity", new GravityAttributeProcessor<V>() {
            @Override
            public void setGravity(V view, int gravity) {
                if (view.getLayoutParams() instanceof CoordinatorLayout.LayoutParams) {
                    ((CoordinatorLayout.LayoutParams) view.getLayoutParams()).anchorGravity =
                            gravity;
                }
            }
        });

        addAttributeProcessor(Attributes.View.LayoutGravity, new GravityAttributeProcessor<V>() {
            @Override
            public void setGravity(V view, int gravity) {

            }
        });
    }
}
