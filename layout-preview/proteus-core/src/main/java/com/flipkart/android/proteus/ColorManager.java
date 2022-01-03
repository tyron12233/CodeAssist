package com.flipkart.android.proteus;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.Color;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

public abstract class ColorManager {

    protected abstract Map<String, Value> getColors();

    @Nullable
    public Value getColor(String name) {
        return getColors() != null ? getColors().get(name) : null;
    }
}
