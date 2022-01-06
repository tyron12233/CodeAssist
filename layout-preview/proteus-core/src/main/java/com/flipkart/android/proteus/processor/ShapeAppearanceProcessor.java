package com.flipkart.android.proteus.processor;

import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Dimension;
import com.flipkart.android.proteus.value.ObjectValue;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.ShapeAppearanceModel;

public abstract class ShapeAppearanceProcessor<V extends View> extends AttributeProcessor<V> {
    @Override
    public void handleValue(View parent, View view, Value value) {
        if (value.isStyle()) {
            handleStyle(parent, view, value.getAsStyle());
        } else if (value.isAttributeResource()) {
            handleAttributeResource(parent, view, value.getAsAttributeResource());
        } else if (value.isResource()) {
            handleResource(parent, view, value.getAsResource());
        } else if (value.isPrimitive()) {
            ProteusContext context = ProteusHelper.getProteusContext(view);
            value = staticPreCompile(value, context, context.getFunctionManager());
            if (value != null) {
                handleValue(parent, view, value);
            }
        }
    }

    @Override
    public void handleResource(View parent, View view, Resource resource) {
        System.out.println("resource: " + resource);
    }

    @Override
    public void handleAttributeResource(View parent, View view, AttributeResource attribute) {
        ProteusContext context = ProteusHelper.getProteusContext(view);
        String name = attribute.getName();
        Style style = context.getStyle();
        Value value = style.getValue(name, context, null);
        if (value != null) {
            handleValue(parent, view, value);
        }
    }

    @Override
    public void handleStyle(View parent, View view, Style style) {
        ProteusContext context = ProteusHelper.getProteusContext(view);
        ObjectValue values = style.getValues();
        if (values != null) {
            ShapeAppearanceModel.Builder builder = ShapeAppearanceModel.builder();

            int cornerFamilyInt = CornerFamily.ROUNDED;
            Value cornerFamily = values.get("cornerFamily");
            if (cornerFamily != null) {
                String name = cornerFamily.toString();
                if ("cut".equals(name)) {
                    cornerFamilyInt = CornerFamily.CUT;
                }
            }
            Value cornerSize = values.get("cornerSize");
            if (cornerSize != null) {
                cornerSize = DimensionAttributeProcessor.staticCompile(cornerSize, context);
            }

            if (cornerSize != null) {
                if (!cornerSize.isDimension()) {
                    cornerSize = resolveDimension(cornerSize, view, context);
                }
                builder.setAllCorners(cornerFamilyInt, cornerSize.getAsDimension().apply(context));
            }

            setShapeAppearance(view, builder.build());
        }
    }

    private Dimension resolveDimension(Value resource, View view, ProteusContext context) {
        if (resource.isDimension()) {
            return resource.getAsDimension();
        }
        if (resource.isResource()) {
            return resource.getAsResource().getDimension(context);
        }
        if (resource.isAttributeResource()) {
            return resolveDimension(resource.getAsAttributeResource().resolve((View) view.getParent(), view, context), view, context);
        }
        return null;
    }

    public abstract void setShapeAppearance(View view, ShapeAppearanceModel model);
}
