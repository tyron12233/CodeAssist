package com.tyron.builder.project.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class AndroidModuleImpl extends JavaModuleImpl implements AndroidModule {

    private ManifestData mManifestData;
    private final Map<String, File> mKotlinFiles;
    private Map<String, File> mResourceClasses;

    public AndroidModuleImpl(File root) {
        super(root);

        mKotlinFiles = new HashMap<>();
        mResourceClasses = new HashMap<>(1);
    }

    @Override
    public void open() throws IOException {
        super.open();

        try {
            mManifestData = AndroidManifestParser.parse(getManifestFile());
        } catch (IOException e) {
            throw new IOException("Unable to parse manifest. Fix manifest errors and then refresh the module.");
        }
    }

    @Override
    public void index() {
        super.index();

        Consumer<File> kotlinConsumer = this::addKotlinFile;

        if (getJavaDirectory().exists()) {
            FileUtils.iterateFiles(getJavaDirectory(),
                    FileFilterUtils.suffixFileFilter(".kt"),
                    TrueFileFilter.INSTANCE
            ).forEachRemaining(kotlinConsumer);
        }

        if (getKotlinDirectory().exists()) {
            FileUtils.iterateFiles(getKotlinDirectory(),
                    FileFilterUtils.suffixFileFilter(".kt"),
                    TrueFileFilter.INSTANCE
            ).forEachRemaining(kotlinConsumer);
        }

        // R.java files
//        File gen = new File(getBuildDirectory(), "gen");
//        if (gen.exists()) {
//            FileUtils.iterateFiles(gen,
//                    FileFilterUtils.suffixFileFilter(".java"),
//                    TrueFileFilter.INSTANCE
//            ).forEachRemaining(this::addJavaFile);
//        }
    }

    @Override
    public File getAndroidResourcesDirectory() {
        File custom = getPathSetting("android_resources_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "src/main/res");
    }

    @Override
    public Set<String> getAllClasses() {
        Set<String> classes = super.getAllClasses();
        classes.addAll(mKotlinFiles.keySet());
        return classes;
    }

    @Override
    public File getNativeLibrariesDirectory() {
        File custom = getPathSetting("native_libraries_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "src/main/jniLibs");
    }

    @Override
    public File getAssetsDirectory() {
        File custom = getPathSetting("assets_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "src/main/assets");
    }

    @Override
    public String getPackageName() {
        if (mManifestData == null) {
            return null;
        }
        return mManifestData.getPackage();
    }

    @Override
    public File getManifestFile() {
        File custom = getPathSetting("android_manifest_file");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "src/main/AndroidManifest.xml");
    }

    @Override
    public int getTargetSdk() {
        return getSettings().getInt(ModuleSettings.TARGET_SDK_VERSION, 30);
    }

    @Override
    public int getMinSdk() {
        return getSettings().getInt(ModuleSettings.MIN_SDK_VERSION, 21);
    }

    @Override
    public void addResourceClass(@NonNull File file) {
        if (!file.getName().endsWith(".java")) {
            return;
        }
        String packageName = StringSearch.packageName(file);
        String className;
        if (packageName == null) {
            className = file.getName().replace(".java", "");
        } else {
            className = packageName + "." + file.getName().replace(".java", "");
        }
        mResourceClasses.put(className, file);
    }

    @Override
    public Map<String, File> getResourceClasses() {
        return ImmutableMap.copyOf(mResourceClasses);
    }

    @NonNull
    @Override
    public Map<String, File> getKotlinFiles() {
        return ImmutableMap.copyOf(mKotlinFiles);
    }

    @NonNull
    @Override
    public File getKotlinDirectory() {
        File custom = getPathSetting("kotlin_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "src/main/kotlin");
    }

    @Nullable
    @Override
    public File getKotlinFile(String packageName) {
        return mKotlinFiles.get(packageName);
    }

    @Override
    public void addKotlinFile(File file) {
        String packageName = StringSearch.packageName(file);
        if (packageName == null) {
            packageName = "";
        }
        String fqn = packageName + "." + file.getName().replace(".kt", "");
        mKotlinFiles.put(fqn, file);
    }

    @Override
    public void clear() {
        super.clear();

        try {
            Class<?> clazz = Class.forName("com.tyron.builder.compiler.symbol.MergeSymbolsTask");
            removeCache(ReflectionUtil.getStaticFieldValue(clazz, CacheKey.class, "CACHE_KEY"));
        } catch (Throwable e) {
            throw new Error(e);
        }
    }
}
