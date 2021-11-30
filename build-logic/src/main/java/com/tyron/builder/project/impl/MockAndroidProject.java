package com.tyron.builder.project.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.project.api.AndroidProject;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class MockAndroidProject extends MockJavaProject implements AndroidProject {

    private int mTargetSdk = 31;
    private int mMinSdk = 21;

    private ManifestData mManifestData;

    public MockAndroidProject(File rootDir) {
        super(rootDir);
    }

    @Override
    public void open() throws IOException {
        super.open();

        mManifestData = AndroidManifestParser.parse(getManifestFile());
    }

    @Override
    public File getAndroidResourcesDirectory() {
        return new File(getRootFile(), "app/src/main/res");
    }

    @Override
    public File getNativeLibrariesDirectory() {
        return new File(getRootFile(), "app/src/main/jniLibs");
    }

    @Override
    public File getAssetsDirectory() {
        return new File(getRootFile(), "app/src/main/assets");
    }

    @Override
    public String getPackageName() {
        if (mManifestData == null) {
            throw new IllegalStateException("Project is not yet opened");
        }
        return mManifestData.getPackage();
    }

    @Override
    public File getManifestFile() {
        return new File(getRootFile(), "app/src/main/AndroidManifest.xml");
    }

    @Override
    public int getTargetSdk() {
        return mTargetSdk;
    }

    public void setTargetSdk(int targetSdk) {
        mTargetSdk = targetSdk;
    }

    @Override
    public int getMinSdk() {
        return mMinSdk;
    }

    public void setMinSdk(int min) {
        mMinSdk = min;
    }

    @NonNull
    @Override
    public Map<String, File> getKotlinFiles() {
        return Collections.emptyMap();
    }

    @Nullable
    @Override
    public File getKotlinFile(String packageName) {
        return null;
    }
}
