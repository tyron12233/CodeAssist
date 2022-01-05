package com.flipkart.android.proteus.processor;

import android.view.View;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.processor.AttributeProcessor;
import com.flipkart.android.proteus.processor.DimensionAttributeProcessor;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Primitive;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.util.Map;

public abstract class ShapeAppearanceProcessor<V extends View> extends AttributeProcessor<V> {
    @Override
    public void handleValue(View view, Value value) {
        if (value.isStyle()) {
            handleStyle(view, value.getAsStyle());
        } else if (value.isAttributeResource()) {
            handleAttributeResource(view, value.getAsAttributeResource());
        } else if (value.isResource()) {
            handleResource(view, value.getAsResource());
        } else if (value.isPrimitive()) {
            ProteusContext context = (ProteusContext) view.getContext();
            value = staticPreCompile(value, context, context.getFunctionManager());
            if (value != null) {
                handleValue(view, value);
            }
        }
    }

    @Override
    public void handleResource(View view, Resource resource) {

    }

    @Override
    public void handleAttributeResource(View view, AttributeResource attribute) {
        ProteusContext context = (ProteusContext) view.getContext();
        String name = attribute.getName();
        Style style = context.getStyle();
        Value value = style.getValue(name, context, null);
        if (value != null) {
            handleValue(view, value);
        }
    }

    @Override
    public void handleStyle(View view, Style style) {
        ProteusContext context = (ProteusContext) view.getContext();
        ObjectValue values = style.getValues();
        if (values != null) {
            ShapeAppearanceModel.Builder builder = ShapeAppearanceModel.builder();

            int cornerFamilyInt = CornerFamily.ROUNDED;
            Value cornerFamily = values.get("cornerFamily");
            if (cornerFamily != null) {
                String name = cornerFamily.toString();
                if ("cut".equals(name)) {
                    cornerFamilyInt = CornerFamily.ROUNDED;
                }
            }
            Value cornerSize = values.get("cornerSize");
            if (cornerSize != null) {
                cornerSize = DimensionAttributeProcessor.staticCompile(cornerSize, context);
            }

            if (cornerSize != null) {
                builder.setAllCorners(cornerFamilyInt, cornerSize.getAsDimension().apply(context));
            }

            setShapeAppearance(view, builder.build());
        }
    }

    public abstract void setShapeAppearance(View view, ShapeAppearanceModel model);
}
