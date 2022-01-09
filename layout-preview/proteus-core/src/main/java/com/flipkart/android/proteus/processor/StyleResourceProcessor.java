package com.flipkart.android.proteus.processor;

import android.view.View;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

public class StyleResourceProcessor<V extends View> extends AttributeProcessor<V> {
    @Override
    public void handleValue(View parent, View view, Value value) {
        if (value.isStyle()) {
            handleStyle(parent, view, value.getAsStyle());
        } else {
            if (value.isPrimitive()) {
                ProteusContext context = ProteusHelper.getProteusContext(view);
                Value value1 = Style.valueOf(value.toString(), context);
                if (value1 != null && value1.isStyle()) {
                    handleStyle(parent, view, value1.getAsStyle());
                }
            }
        }
    }

    @Override
    public void handleResource(View parent, View view, Resource resource) {

    }

    @Override
    public void handleAttributeResource(View parent, View view, AttributeResource attribute) {
        ProteusContext context = ProteusHelper.getProteusContext(view);
        String name = attribute.getName();

        Value value = context.obtainStyledAttribute(parent, view, name);
        if (value != null) {
            if (value.isStyle()) {
                handleStyle(parent, view, value.getAsStyle());
            } else if (value.isPrimitive()) {
                String styleName = value.toString();
                Style style1 = context.getStyle(styleName);
                if (style1 != null) {
                    handleStyle(parent, view, style1);
                }
            }
        }

    }

    @Override
    public void handleStyle(View parent, View view, Style style) {
        style.applyStyle(parent, (ProteusView) view, false);
    }
}
