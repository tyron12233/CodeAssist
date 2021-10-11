package com.tyron.layout.appcompat.widget;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.processor.DrawableResourceProcessor;
import com.flipkart.android.proteus.processor.GravityAttributeProcessor;
import com.flipkart.android.proteus.processor.NumberAttributeProcessor;
import com.flipkart.android.proteus.processor.StringAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.tyron.layout.appcompat.AppCompatModuleAttributeHelper;
import com.tyron.layout.appcompat.view.ProteusCollapsingToolbarLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * CollapsingToolbarLayoutParser
 *
 * @author adityasharat
 */

public class CollapsingToolbarLayoutParser<V extends View> extends ViewTypeParser<V> {

    @NonNull
    @Override
    public String getType() {
        return "com.google.android.material.appbar.CollapsingToolbarLayout";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.widget.FrameLayout";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusCollapsingToolbarLayout(context);
    }

    @Override
    protected void addAttributeProcessors() {

        addAttributeProcessor("collapsedTitleGravity", new GravityAttributeProcessor<V>() {
            @Override
            public void setGravity(V view, @Gravity int gravity) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setCollapsedTitleGravity(gravity);
                }
            }
        });

        addAttributeProcessor("contentScrim", new DrawableResourceProcessor<V>() {
            @Override
            public void setDrawable(V view, Drawable drawable) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setContentScrim(drawable);
                }
            }
        });

        addAttributeProcessor("expandedTitleGravity", new GravityAttributeProcessor<V>() {
            @Override
            public void setGravity(V view, @Gravity int gravity) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setExpandedTitleGravity(gravity);
                }
            }
        });

        addAttributeProcessor("expandedTitleMargin", new DimensionAttributeProcessor<V>() {
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setExpandedTitleMargin((int) dimension, (int) dimension, (int) dimension, (int) dimension);
                }
            }
        });

        addAttributeProcessor("expandedTitleMarginBottom", new DimensionAttributeProcessor<V>() {
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setExpandedTitleMarginBottom((int) dimension);
                }
            }
        });

        addAttributeProcessor("expandedTitleMarginEnd", new DimensionAttributeProcessor<V>() {
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setExpandedTitleMarginEnd((int) dimension);
                }
            }
        });

        addAttributeProcessor("expandedTitleMarginStart", new DimensionAttributeProcessor<V>() {
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setExpandedTitleMarginStart((int) dimension);
                }
            }
        });

        addAttributeProcessor("expandedTitleMarginTop", new DimensionAttributeProcessor<V>() {
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setExpandedTitleMarginTop((int) dimension);
                }
            }
        });

        addAttributeProcessor("scrimAnimationDuration", new NumberAttributeProcessor<V>() {
            @Override
            public void setNumber(V view, @NonNull Number value) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setScrimAnimationDuration(value.longValue());
                }
            }
        });


        addAttributeProcessor("scrimVisibleHeightTrigger", new DimensionAttributeProcessor<V>() {
            @Override
            public void setDimension(V view, float dimension) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setScrimVisibleHeightTrigger((int) dimension);
                }
            }
        });

        addAttributeProcessor("statusBarScrim", new DrawableResourceProcessor<V>() {
            @Override
            public void setDrawable(V view, Drawable drawable) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setStatusBarScrim(drawable);
                }
            }
        });

        addAttributeProcessor("title", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setTitle(value);
                }
            }
        });

        addAttributeProcessor("titleEnabled", new BooleanAttributeProcessor<V>() {
            @Override
            public void setBoolean(V view, boolean value) {
                if (view instanceof CollapsingToolbarLayout) {
                    ((CollapsingToolbarLayout) view).setTitleEnabled(value);
                }
            }
        });

        addAttributeProcessor("layout_collapseMode", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                if (view.getLayoutParams() instanceof CollapsingToolbarLayout.LayoutParams) {
                    AppCompatModuleAttributeHelper.CollapsingToolbarLayoutParamsHelper.setCollapseMode(view, value);
                }
            }
        });

        addAttributeProcessor("layout_parallaxMultiplier", new StringAttributeProcessor<V>() {
            @Override
            public void setString(V view, String value) {
                if (view.getLayoutParams() instanceof CollapsingToolbarLayout.LayoutParams) {
                    AppCompatModuleAttributeHelper.CollapsingToolbarLayoutParamsHelper.setParallaxMultiplier(view, value);
                }
            }
        });
    }
}
