package com.flipkart.android.proteus.processor;

import android.util.Log;
import android.view.View;

import com.flipkart.android.proteus.ProteusConstants;
import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.ProteusView;
import com.flipkart.android.proteus.ViewTypeParser;
import com.flipkart.android.proteus.toolbox.ProteusHelper;
import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

public class StyleResourceProcessor<V extends View> extends AttributeProcessor<V> {
    @Override
    public void handleValue(View view, Value value) {
        if (value.isStyle()) {
            handleStyle(view, value.getAsStyle());
        } else {
            if (value.isPrimitive()) {
                ProteusContext context = (ProteusContext) view.getContext();
                Value value1 = Style.valueOf(value.toString(), context);
                if (value1 != null && value1.isStyle()) {
                    handleStyle(view, value1.getAsStyle());
                }
            }
        }
    }

    @Override
    public void handleResource(View view, Resource resource) {

    }

    @Override
    public void handleAttributeResource(View view, AttributeResource attribute) {
        ProteusView.Manager viewManager = ((ProteusView) view).getViewManager();
        ProteusContext context = viewManager.getContext();
        String name = attribute.getName();
        Style style = context.getStyle();
        if (style != null) {
            Value value = style.getValue(name, null);
            if (value != null) {
                if (value.isStyle()) {
                    handleStyle(view, value.getAsStyle());
                } else if (value.isPrimitive()) {
                    String styleName = value.toString();
                    Style style1 = context.getStyle(styleName);
                    if (style1 != null) {
                        handleStyle(view, style1);
                    }
                }
            }
        }
    }

    @Override
    public void handleStyle(View view, Style style) {
        for (Map.Entry<String, Value> entry : style.getValues().entrySet()) {
            handleAttributeValue(view, entry.getKey(), entry.getValue());
        }
    }

    private void handleAttributeValue(View view, String attributeName, Value value ) {
        ProteusView.Manager viewManager = ((ProteusView) view).getViewManager();
        ProteusContext context = viewManager.getContext();

        // try to resolve the value, this value may be a string and
        // we want to resolve it to its proper type
        Value resolved = AttributeProcessor.staticPreCompile(value, context, context.getFunctionManager());
        if (resolved == null) {
            // the value could not be resolved, fallback to the original value
            resolved = value;
        }

        ViewTypeParser<View> parser = ProteusHelper.getViewTypeParser((ProteusView) view, attributeName);
        if (parser == null) {
            // try to get the parser with app namespace
            parser =ProteusHelper.getViewTypeParser((ProteusView) view, "app:" + attributeName);
        }
        if (parser != null) {
            int id = ProteusHelper.getAttributeId((ProteusView) view, attributeName);
            if (id == -1) {
                id = ProteusHelper.getAttributeId((ProteusView) view, "app:" + attributeName);
            }

            if (!parser.handleAttribute(view, id, resolved)) {
                if (ProteusConstants.isLoggingEnabled()) {
                    Log.e("StyleResourceProcessor", "Unable to handle attribute " + attributeName);
                }
            }
        }
    }
}
