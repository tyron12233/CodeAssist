package com.tyron.layoutpreview;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;

/**
 * A custom context that replaces resources into a custom one loaded through a compiled file
 */
public class PreviewContext extends ContextWrapper {

    private Resources mResources;
    private Resources.Theme mTheme;
    private int mThemeResource;
    private AssetManager mAssetManager;

    public PreviewContext(Context base) {
        super(base);
    }

    public void setResources(Resources customRes) {
        mResources = customRes;
    }

    /**
     * Returns the custom {@link Resources} object
     */
    @Override
    public Resources getResources() {
        return mResources;
    }

    @Override
    public void setTheme(int resId) {
        if (mThemeResource != resId) {
            mThemeResource = resId;
            initializeTheme();
        }
    }

    public void setAssetManger(AssetManager assetManger) {
        mAssetManager = assetManger;
    }

    @Override
    public ClassLoader getClassLoader() {
        return getBaseContext().getClassLoader();
    }

    @Override
    public AssetManager getAssets() {
        return mAssetManager;
    }

    public void setTheme(Resources.Theme theme) {
        mTheme = theme;
    }

    @Override
    public Resources.Theme getTheme() {
        if (mTheme != null) {
            return mTheme;
        }
        return super.getTheme();
    }

    protected void onApplyThemeResource(Resources.Theme theme, int resId, boolean first) {
        theme.applyStyle(resId, first);
    }

    private void initializeTheme() {
        final boolean first = mTheme == null;
        if (first) {
            mTheme = mResources.newTheme();
        }
        onApplyThemeResource(mTheme, mThemeResource, first);
    }
}
