package com.tyron.builder.project.mock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableMap;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.FileManager;
import com.tyron.common.util.StringSearch;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MockAndroidModule extends MockJavaModule implements AndroidModule {

    private final Map<String, File> mKotlinFiles = new HashMap<>();

    private int mTargetSdk = 31;
    private int mMinSdk = 21;

    private ManifestData mManifestData;

    private File mAndroidResourcesDir;

    public MockAndroidModule(File rootDir, FileManager fileManager) {
        super(rootDir, fileManager);
    }

    @Override
    public ModuleSettings getSettings() {
        return new MockModuleSettings();
    }

    @Override
    public void open() throws IOException {
        super.open();

        if (getManifestFile().exists()) {
            mManifestData = AndroidManifestParser.parse(getManifestFile());
        }
    }

    @Override
    public void index() {

    }

    public void setAndroidResourcesDirectory(File dir) {
        mAndroidResourcesDir = dir;
    }

    @Override
    public File getAndroidResourcesDirectory() {
        if (mAndroidResourcesDir != null) {
            return mAndroidResourcesDir;
        }
        return new File(getRootFile(), "src/main/res");
    }

    @Override
    public File getNativeLibrariesDirectory() {
        return new File(getRootFile(), "src/main/jniLibs");
    }

    @Override
    public File getAssetsDirectory() {
        return new File(getRootFile(), "src/main/assets");
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
        return new File(getRootFile(), "src/main/AndroidManifest.xml");
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
        return ImmutableMap.copyOf(mKotlinFiles);
    }

    @NonNull
    @Override
    public File getKotlinDirectory() {
        return new File(getRootFile(), "src/main/kotlin");
    }

    @Nullable
    @Override
    public File getKotlinFile(String packageName) {
        return mKotlinFiles.get(packageName);
    }

    @Override
    public void addKotlinFile(File file) {
        mKotlinFiles.put(StringSearch.packageName(file), file);
    }
}
