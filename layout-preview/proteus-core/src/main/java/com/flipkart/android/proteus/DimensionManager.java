package com.flipkart.android.proteus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.Dimension;
import com.flipkart.android.proteus.value.Value;

import java.util.Map;

public abstract class DimensionManager {

    @NonNull
    protected abstract Map<String, Value> getDimensions();

    @Nullable
    public Value getDimension(@NonNull String name) {
        return getDimensions().get(name);
    }
}
