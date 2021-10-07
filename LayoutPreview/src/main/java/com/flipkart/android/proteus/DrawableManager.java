package com.flipkart.android.proteus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.DrawableValue;

import java.util.Map;

public abstract class DrawableManager {

    protected abstract Map<String, DrawableValue> getDrawables();

    @Nullable
    public DrawableValue get(@NonNull String name) {
        return null != getDrawables() ? getDrawables().get(name) : null;
    }
}
