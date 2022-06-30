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
import java.util.List;
import java.util.Map;

public class MockAndroidModule extends MockJavaModule implements AndroidModule {

    private final Map<String, File> mKotlinFiles = new HashMap<>();

    private int mTargetSdk = 31;
    private int mMinSdk = 21;

    private ManifestData mManifestData;
    private String mPackageName;

    private File mAndroidResourcesDir;

    private final ModuleSettings mockSettings = new MockModuleSettings();

    public MockAndroidModule(File rootDir, FileManager fileManager) {
        super(rootDir, fileManager);
    }

    @Override
    public ModuleSettings getSettings() {
        return mockSettings;
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


    public void setPackageName(@NonNull String name) {
        mPackageName = name;
    }

    @Override
    public String getPackageName() {
        if (mPackageName != null) {
            return mPackageName;
        }

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

    @Override
    public Map<String, File> getResourceClasses() {
        return new HashMap<>();
    }

    @Override
    public void addResourceClass(@NonNull File file) {

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

    @Override
    public Map<String, File> getInjectedClasses() {
        return null;
    }

    @Override
    public void addInjectedClass(@NonNull File file) {

    }
}
