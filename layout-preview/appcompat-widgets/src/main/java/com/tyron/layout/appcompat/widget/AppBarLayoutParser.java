package com.tyron.layout.appcompat.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.google.android.material.appbar.AppBarLayout;
import com.tyron.layout.appcompat.AppCompatModuleAttributeHelper;
import com.tyron.layout.appcompat.view.ProteusAppBarLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * AppBarLayoutParser
 *
 * @author adityasharat
 */

public class AppBarLayoutParser<V extends View> extends ViewTypeParser<V> {
    @NonNull
    @Override
    public String getType() {
        return "com.google.android.material.appbar.AppBarLayout";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.widget.LinearLayout";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusAppBarLayout(context);
    }

    @Override
    protected void addAttributeProcessors() {

        addAttributeProcessor("targetElevation", new DimensionAttributeProcessor<V>() {
            @SuppressWarnings("deprecation")
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof AppBarLayout) {
                    ((AppBarLayout) view).setTargetElevation(dimension);
                }
            }
        });

        addAttributeProcessor("orientation", new AttributeProcessor<V>() {

            private final Primitive VERTICAL = new Primitive(AppBarLayout.VERTICAL);
            private final Primitive HORIZONTAL = new Primitive(AppBarLayout.HORIZONTAL);

            @Override
            public void handleValue(View parent, V view, Value value) {
                //noinspection WrongConstant
                if (view instanceof AppBarLayout) {
                    ((AppBarLayout) view).setOrientation(value.getAsInt());
                }
            }

            @Override
            public void handleResource(View parent, V view, Resource resource) {
                Integer orientation = resource.getInteger(view.getContext());
                if (orientation != null) {
                    //noinspection WrongConstant
                    if (view instanceof AppBarLayout) {
                        ((AppBarLayout) view).setOrientation(orientation);
                    }
                }
            }

            @Override
            public void handleAttributeResource(View parent, V view, AttributeResource attribute) {
                TypedArray a = attribute.apply(view.getContext());
                int orientation = a.getInt(0, AppBarLayout.VERTICAL);
                //noinspection WrongConstant
                if (view instanceof AppBarLayout) {
                    ((AppBarLayout) view).setOrientation(orientation);
                }
            }

            @Override
            public void handleStyle(View parent, V view, Style style) {
//                TypedArray a = style.apply(view.getContext());
//                int orientation = a.getInt(0, AppBarLayout.VERTICAL);
//                //noinspection WrongConstant
//                if (view instanceof AppBarLayout) {
//                    ((AppBarLayout) view).setOrientation(orientation);
//                }
            }

            public Value compile(@Nullable Value value, Context context) {
                if (null != value && value.isPrimitive()) {
                    String string = value.getAsString();
                    if ("vertical".equals(string)) {
                        return VERTICAL;
                    } else {
                        return HORIZONTAL;
                    }
                } else {
                    return VERTICAL;
                }
            }
        });

        addAttributeProcessor("expanded", new BooleanAttributeProcessor<V>() {
            @Override
            public void setBoolean(V view, boolean value) {
                if (view instanceof AppBarLayout) {
                    ((AppBarLayout) view).setExpanded(value);
                }
            }
        });

        addAttributeProcessor("layout_scrollFlags", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                if (view.getLayoutParams() instanceof AppBarLayout.LayoutParams) {
                    AppCompatModuleAttributeHelper.AppBarLayoutParamsHelper.setLayoutScrollFlags(view, value);
                }
            }
        });
    }
}
