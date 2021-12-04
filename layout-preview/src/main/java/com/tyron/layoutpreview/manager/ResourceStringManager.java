package com.tyron.layoutpreview.manager;

import androidx.annotation.Nullable;

import com.flipkart.android.proteus.value.Value;
import com.flipkart.android.proteus.StringManager;

import java.util.Map;

public class ResourceStringManager extends StringManager {

    private Map<String, Map<String, Value>> mStrings;

    @Override
    public Map<String, Value> getStrings(@Nullable String tag) {
        if (!mStrings.containsKey(tag)) {
            return mStrings.get(null);
        }

        return mStrings.get(tag);
    }

    public void setStrings(Map<String, Map<String, Value>> strings) {
        mStrings = strings;
    }
}
