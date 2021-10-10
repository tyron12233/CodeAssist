package com.tyron.layoutpreview.manager;

import androidx.annotation.NonNull;

import com.flipkart.android.proteus.DrawableManager;
import com.flipkart.android.proteus.value.DrawableValue;

import java.util.HashMap;
import java.util.Map;

public class ResourceDrawableManager extends DrawableManager {

    private final Map<String, DrawableValue> mDrawables = new HashMap<>();

    public void setDrawables(@NonNull Map<String, DrawableValue> map) {
        mDrawables.clear();
        mDrawables.putAll(map);
    }
    @Override
    protected Map<String, DrawableValue> getDrawables() {
        return mDrawables;
    }
}
