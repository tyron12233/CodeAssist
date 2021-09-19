package com.tyron.layoutpreview;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.appcompat.content.res.AppCompatResources;

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

    }


    @SuppressLint("PrivateApi")
    public void initialize() throws Exception {
        mAssetManager = AssetManager.class.newInstance();

        mResources = new Resources(mAssetManager, Resources.getSystem().getDisplayMetrics(), Resources.getSystem().getConfiguration());

        mPreviewContext = new PreviewContext(mContext);
        mPreviewContext.setResources(mResources);
        mPreviewContext.setAssetManger(mAssetManager);

    }


    public void addAssetPath(String path) {
        try {
            Method assetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            assetPath.invoke(mAssetManager, path);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            Log.d(TAG, e.getMessage());
        }
    }

    public PreviewContext getPreviewContext() {
        return mPreviewContext;
    }

    public Resources getResources() {
        return mResources;
    }

    public AssetManager getAssetManager() {
        return mAssetManager;
    }

    public void test() {
        Log.d(TAG, "Test: " + mResources.getIdentifier("activity_main", "layout", "com.tyron.test"));
    }
}
