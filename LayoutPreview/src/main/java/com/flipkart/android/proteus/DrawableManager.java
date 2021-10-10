package com.flipkart.android.proteus;

import com.flipkart.android.proteus.value.DrawableValue;

import java.util.Map;

public abstract class DrawableManager {

    protected abstract Map<String, DrawableValue> getDrawables();

    public DrawableValue get(String name) {
        return null != getDrawables() ? getDrawables().get(name) : null;
    }
}
