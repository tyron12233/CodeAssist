package com.tyron.layoutpreview;

import com.flipkart.android.proteus.ProteusContext;
import com.flipkart.android.proteus.value.DrawableValue;
import com.flipkart.android.proteus.value.Layout;
import com.flipkart.android.proteus.value.Value;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.FileManager;
import com.tyron.layoutpreview.resource.ResourceDrawableParser;
import com.tyron.layoutpreview.resource.ResourceLayoutParser;
import com.tyron.layoutpreview.resource.ResourceStringParser;

import java.io.File;
import java.util.Collections;
import java.util.Map;

public class ResourceManager {

    private final AndroidModule mAndroidModule;
    private final ProteusContext mContext;
    private final FileManager mFileManager;

    public ResourceManager(ProteusContext context, AndroidModule module, FileManager fileManager) {
        mAndroidModule = module;
        mContext = context;
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
        ResourceDrawableParser parser = new ResourceDrawableParser(mContext, mAndroidModule.getAndroidResourcesDirectory(),
                mFileManager);
        Map<String, DrawableValue> defaultDrawables = parser.getDefaultDrawables();

        for (File library : mAndroidModule.getLibraries()) {
            File parent = library.getParentFile();
            if (parent == null) {
                continue;
            }

            File resourcesDir = new File(parent, "res");
            if (resourcesDir.exists()) {
                parser = new ResourceDrawableParser(mContext, resourcesDir, mFileManager);
                defaultDrawables.putAll(parser.getDefaultDrawables());
            }
        }

        return defaultDrawables;
    }

    public Map<String, Layout> getLayouts() {
        ResourceLayoutParser parser = new ResourceLayoutParser(mContext, mAndroidModule.getAndroidResourcesDirectory(), mFileManager);
        return parser.getLayouts();
    }

}
