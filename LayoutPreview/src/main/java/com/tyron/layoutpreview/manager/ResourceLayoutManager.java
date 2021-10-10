package com.tyron.layoutpreview.manager;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.LayoutManager;
import com.flipkart.android.proteus.value.Layout;

import java.util.HashMap;
import java.util.Map;

public class ResourceLayoutManager extends LayoutManager {

    private final Map<String, Layout> mLayouts = new HashMap<>();

    @Nullable
    @Override
    protected Map<String, Layout> getLayouts() {
        return mLayouts;
    }

    public void setLayouts(Map<String, Layout> map) {
        mLayouts.clear();
        mLayouts.putAll(map);
    }
}
