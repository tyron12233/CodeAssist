package com.tyron.layoutpreview;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Value;
import com.tyron.layoutpreview.resource.ResourceDrawableParser;
import com.tyron.layoutpreview.resource.ResourceStringParser;

import java.io.File;
import java.util.Map;

public class ResourceManager {

    private final File mResourceDir;
    private final ProteusContext mContext;
    public ResourceManager(ProteusContext context, File resourceDir) {
        mContext = context;
        mResourceDir = resourceDir;
    }


    public Map<String, Map<String, Value>> getStrings() {
        ResourceStringParser parser = new ResourceStringParser(mResourceDir);
        return parser.getStrings();
    }

    public Map<String, DrawableValue> getDrawables() {
        ResourceDrawableParser parser = new ResourceDrawableParser(mContext, mResourceDir);
        return parser.getDefaultDrawables();
    }

}
