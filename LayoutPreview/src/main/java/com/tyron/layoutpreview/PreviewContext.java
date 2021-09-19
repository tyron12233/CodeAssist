package com.tyron.layoutpreview;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A custom context that replaces resources into a custom one loaded through a compiled file
 */
public class PreviewContext extends ContextWrapper {

    private Resources mResources;
    private Resources.Theme mTheme;
    private int mThemeResource;
    private AssetManager mAssetManager;
    private ClassLoader mClassLoader;



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

    public void setClassLoader(ClassLoader loader) {
        mClassLoader = loader;
    }

    @Override
    public ClassLoader getClassLoader() {
        if (mClassLoader == null) {
            return getBaseContext().getClassLoader();
        } else {
            return mClassLoader;
        }
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
