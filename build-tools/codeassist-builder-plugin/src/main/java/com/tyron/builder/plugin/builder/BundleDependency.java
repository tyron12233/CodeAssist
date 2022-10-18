package com.tyron.builder.plugin.builder;

import com.android.SdkConstants;

import java.io.File;

/**
 * Default implementation of the AndroidDependency interface that handles a default bundle project
 * structure.
 */
public abstract class BundleDependency implements AndroidDependency {
    private final File mBundleFolder;

    protected BundleDependency(File bundleFolder) {
        mBundleFolder = bundleFolder;
    }

    @Override
    public File getFolder() {
        return mBundleFolder;
    }

    @Override
    public File getJarFile() {
        return new File(mBundleFolder, SdkConstants.FN_CLASSES_JAR);
    }

    @Override
    public File getManifest() {
        return new File(mBundleFolder, SdkConstants.FN_ANDROID_MANIFEST_XML);
    }

    @Override
    public File getResFolder() {
        return new File(mBundleFolder, SdkConstants.FD_RES);
    }

    @Override
    public File getAssetsFolder() {
        return new File(mBundleFolder, SdkConstants.FD_ASSETS);
    }

    @Override
    public File getJniFolder() {
        return new File(mBundleFolder, "jni");
    }

    @Override
    public File getAidlFolder() {
        return new File(mBundleFolder, SdkConstants.FD_AIDL);
    }

    @Override
    public File getProguardRules() {
        return new File(mBundleFolder, "proguard.txt");
    }

    @Override
    public File getLintJar() {
        return new File(mBundleFolder, "lint.jar");
    }


}