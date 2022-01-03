package com.tyron.layoutpreview;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;
import com.tyron.builder.project.api.FileManager;
import com.tyron.layoutpreview.resource.ResourceDrawableParser;
import com.tyron.layoutpreview.resource.ResourceLayoutParser;
import com.tyron.layoutpreview.resource.ResourceStringParser;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public class ResourceManager {

    private final File mResourceDir;
    private final ProteusContext mContext;
    private final FileManager mFileManager;

    public ResourceManager(ProteusContext context, File resourceDir, FileManager fileManager) {
        mContext = context;
        mResourceDir = resourceDir;
        mFileManager = fileManager;
    }

    /**
     * @deprecated Use {@link com.tyron.layoutpreview.resource.ResourceValueParser} instead
     */
    @Deprecated
    public Map<String, Map<String, Value>> getStrings() {
        return Collections.emptyMap();
    }

    public Map<String, DrawableValue> getDrawables() {
        ResourceDrawableParser parser = new ResourceDrawableParser(mContext, mResourceDir,
                mFileManager);
        return parser.getDefaultDrawables();
    }

    public Map<String, Layout> getLayouts() {
        ResourceLayoutParser parser = new ResourceLayoutParser(mContext, mResourceDir, mFileManager);
        return parser.getLayouts();
    }

}
