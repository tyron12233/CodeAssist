package com.tyron.layout.appcompat.widget;

import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.processor.ColorResourceProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.ObjectValue;
import com.google.android.material.card.MaterialCardView;
import com.tyron.layout.appcompat.view.ProteusMaterialCardView;

public class MaterialCardViewParser<T extends View> extends ViewTypeParser<T> {
    @NonNull
    @Override
    public String getType() {
        return "com.google.android.material.card.MaterialCardView";
    }

    @Nullable
    @Override
    public String getParentType() {
        return "androidx.cardview.widget.CardView";
    }

    @NonNull
    @Override
    public ProteusView createView(@NonNull ProteusContext context, @NonNull Layout layout, @NonNull ObjectValue data, @Nullable ViewGroup parent, int dataIndex) {
        return new ProteusMaterialCardView(context);
    }

    @Override
    protected void addAttributeProcessors() {
        addAttributeProcessor("app:strokeColor", new ColorResourceProcessor<T>() {
            @Override
            public void setColor(T view, int color) {
                if (view instanceof MaterialCardView) {
                    ((MaterialCardView) view).setStrokeColor(color);
                }
            }

            @Override
            public void setColor(T view, ColorStateList colors) {
                if (view instanceof MaterialCardView) {
                    ((MaterialCardView) view).setStrokeColor(colors);
                }
            }
        });

        addAttributeProcessor("app:strokeWidth", new DimensionAttributeProcessor<T>() {
            @Override
            public void setDimension(T view, float dimension) {
                if (view instanceof MaterialCardView) {
                    ((MaterialCardView) view).setStrokeWidth((int) dimension);
                }
            }
        });
    }
}
