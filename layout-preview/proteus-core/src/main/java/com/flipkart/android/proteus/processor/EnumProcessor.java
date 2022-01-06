package com.flipkart.android.proteus.processor;

import android.view.View;

import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.Style;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

public abstract class EnumProcessor<V extends View> extends AttributeProcessor<V> {

    private final Map<String, Integer> values;

    public EnumProcessor(Map<String, Integer> values) {
        this.values = values;
    }

    @Override
    public void handleValue(View parent, V view, Value value) {
        String name = value.getAsString();
        Integer integer = values.get(name);
        if (integer != null) {
            apply(view, integer);
        } else {
            applyOther(view, name);
        }
    }

    @Override
    public void handleResource(View parent, V view, Resource resource) {

    }

    @Override
    public void handleAttributeResource(View parent, V view, AttributeResource attribute) {

    }

    @Override
    public void handleStyle(View parent, V view, Style style) {

    }

    public abstract void apply(V view, int value);

    public abstract void applyOther(V view, String value);
}
