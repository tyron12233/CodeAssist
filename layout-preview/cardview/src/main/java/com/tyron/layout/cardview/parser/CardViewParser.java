package com.tyron.layout.cardview.parser;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.BooleanAttributeProcessor;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.tyron.layout.cardview.widget.ProteusCardView;

public class CardViewParser<T extends View> extends ViewTypeParser<T> {

    @NonNull
    @Override
    public String getType() {
        return "androidx.cardview.widget.CardView";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "android.widget.FrameLayout";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusCardView(context);
    }

    @Override
    protected void addAttributeProcessors() {
        addAttributeProcessor("app:cardBackgroundColor", new ColorResourceProcessor<T>() {
            @Override
            public void setColor(T view, int color) {
                if (view instanceof CardView) {
                    ((CardView) view).setCardBackgroundColor(color);
                }
            }

            @Override
            public void setColor(T view, ColorStateList colors) {
                if (view instanceof CardView) {
                    ((CardView) view).setCardBackgroundColor(colors);
                }
            }
        });

        addAttributeProcessor("app:cardCornerRadius", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T view, float dimension) {
                if (view instanceof CardView) {
                    ((CardView) view).setRadius(dimension);
                }
            }
        });

        addAttributeProcessor("app:cardElevation", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T view, float dimension) {
                if (view instanceof CardView) {
                    ((CardView) view).setCardElevation(dimension);
                }
            }
        });

        addAttributeProcessor("app:cardMaxElevation", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T view, float dimension) {
                if (view instanceof CardView) {
                    ((CardView) view).setMaxCardElevation(dimension);
                }
            }
        });

        addAttributeProcessor("app:cardPreventCornerOverlap", new BooleanAttributeProcessor<T>() {
            @Override
            public void setBoolean(T view, boolean value) {
                if (view instanceof CardView) {
                    ((CardView) view).setPreventCornerOverlap(value);
                }
            }
        });

        addAttributeProcessor("app:cardUseCompatPadding", new BooleanAttributeProcessor<T>() {
            @Override
            public void setBoolean(T view, boolean value) {
                if (view instanceof CardView) {
                    ((CardView) view).setUseCompatPadding(value);
                }
            }
        });

        addAttributeProcessor("app:contentPadding", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T view, float d) {
                if (view instanceof CardView) {
                    ((CardView) view).setContentPadding((int) d, (int) d, (int) d, (int) d);
                }
            }
        });

        addAttributeProcessor("app:contentPaddingBottom", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T v, float dimension) {
                if (v instanceof CardView) {
                    CardView view = (CardView) v;
                    int t = view.getContentPaddingTop();
                    int r = view.getContentPaddingRight();
                    int l = view.getContentPaddingLeft();

                    view.setContentPadding(l, t, r, (int) dimension);
                }
            }
        });

        addAttributeProcessor("app:contentPaddingTop", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T v, float dimension) {
                if (v instanceof CardView) {
                    CardView view = (CardView) v;
                    int r = view.getContentPaddingRight();
                    int l = view.getContentPaddingLeft();
                    int b = view.getContentPaddingBottom();

                    view.setContentPadding(l, (int) dimension, r, b);
                }
            }
        });

        addAttributeProcessor("app:contentPaddingLeft", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T v, float dimension) {
                if (v instanceof CardView) {
                    CardView view = (CardView) v;
                    int r = view.getContentPaddingRight();
                    int b = view.getContentPaddingBottom();
                    int t = view.getContentPaddingTop();

                    view.setContentPadding((int) dimension, t, r, b);
                }
            }
        });

        addAttributeProcessor("app:contentPaddingRight", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T v, float dimension) {
                if (v instanceof CardView) {
                    CardView view = (CardView) v;
                    int b = view.getContentPaddingBottom();
                    int t = view.getContentPaddingTop();
                    int l = view.getContentPaddingLeft();

                    view.setContentPadding(l, t, (int) dimension, b);
                }
            }
        });
    }
}
