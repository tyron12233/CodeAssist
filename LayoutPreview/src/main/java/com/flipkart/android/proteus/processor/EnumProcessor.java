package com.flipkart.android.proteus.processor;

import android.view.View;

import com.flipkart.android.proteus.value.AttributeResource;
import com.flipkart.android.proteus.value.Resource;
import com.flipkart.android.proteus.value.StyleResource;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

public abstract class EnumProcessor<V extends View> extends AttributeProcessor<V> {

    private final Map<String, Integer> values;

    public EnumProcessor(Map<String, Integer> values) {
        this.values = values;
    }

    @Override
    public void handleValue(V view, Value value) {
        String name = value.getAsString();
        Integer integer = values.get(name);
        if (integer != null) {
            apply(view, integer);
        } else {
            applyOther(view, name);
        }
    }

    @Override
    public void handleResource(V view, Resource resource) {

    }

    @Override
    public void handleAttributeResource(V view, AttributeResource attribute) {

    }

    @Override
    public void handleStyleResource(V view, StyleResource style) {

    }

    public abstract void apply(V view, int value);

    public abstract void applyOther(V view, String value);
}
