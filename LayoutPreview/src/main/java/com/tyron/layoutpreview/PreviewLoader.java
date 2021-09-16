package com.tyron.layoutpreview;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class PreviewLoader {

    private static final String TAG = PreviewLoader.class.getSimpleName();

    private final Context mContext;
    private AssetManager mAssetManager;
    private Resources mResources;
    private PreviewContext mPreviewContext;

    public PreviewLoader(Context context) {
        mContext = context;
        try {
            initialize();
        } catch (Exception exception) {
            Log.d("PreviewLoader", exception.getMessage());
        }
    }


    @SuppressLint("PrivateApi")
    private void initialize() throws Exception {
        mAssetManager = AssetManager.class.newInstance();

        Class<?> resourcesImpl = Class.forName("android.content.res.ResourcesImpl");
        Class<?> displayAdjustments = Class.forName("android.view.DisplayAdjustments");

        mResources = new Resources(mAssetManager, Resources.getSystem().getDisplayMetrics(), Resources.getSystem().getConfiguration());

        mPreviewContext = new PreviewContext(mContext);
        mPreviewContext.setResources(mResources);
        mPreviewContext.setAssetManger(mAssetManager);
    }

    public void addAssetPath(String path) {
        try {
            Method assetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            assetPath.invoke(mAssetManager, path);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignore) {
            Log.d(TAG, ignore.getMessage());
        }
    }

    public PreviewContext getPreviewContext() {
        return mPreviewContext;
    }

    public void test() {
        Log.d(TAG, "Test: " + mResources.getIdentifier("activity_main", "layout", "com.tyron.test"));
    }
}
