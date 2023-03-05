package com.tyron.builder.project.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.compiler.manifest.xml.ManifestData;
import com.tyron.builder.model.CodeAssistAndroidLibrary;
import com.tyron.builder.model.CodeAssistLibrary;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidContentRoot;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.ContentRoot;
import com.tyron.builder.project.util.PackageTrie;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public class AndroidModuleImpl extends JavaModuleImpl implements AndroidModule {

    private final Map<String, File> mKotlinFiles;
    private final Map<String, File> mResourceClasses;

    private final Set<String> moduleDependencies = new HashSet<>();
    private final Set<ContentRoot> contentRoots = new HashSet<>(3);

    private String packageName;
    private String name;
    private Project project;
    private String namespace;

    private final List<CodeAssistLibrary> libraries = new ArrayList<>();

    public AndroidModuleImpl(File root) {
        super(root);

        mKotlinFiles = new HashMap<>();
        mResourceClasses = new HashMap<>(1);
    }

    @Override
    public void open() throws IOException {
        super.open();
    }

    @Override
    public void index() {
        super.index();

        Consumer<File> kotlinConsumer = this::addKotlinFile;

        for (ContentRoot contentRoot : getContentRoots()) {
            if (contentRoot instanceof AndroidContentRoot) {
                AndroidContentRoot androidContentRoot = ((AndroidContentRoot) contentRoot);
                for (File javaDirectory : androidContentRoot.getJavaDirectories()) {
                    // java source root may contain kotlin files aswell
                    FileUtils.iterateFiles(javaDirectory,
                            FileFilterUtils.suffixFileFilter(".kt"),
                            TrueFileFilter.INSTANCE).forEachRemaining(kotlinConsumer);
                    FileUtils.iterateFiles(javaDirectory,
                            FileFilterUtils.suffixFileFilter(".java"),
                            TrueFileFilter.INSTANCE).forEachRemaining(this::addJavaFile);
                }
            }
        }
    }

    public List<CodeAssistLibrary> getCodeAssistLibraries() {
        return libraries;
    }

    @Override
    public void addLibrary(@NonNull @NotNull CodeAssistLibrary library) {
        libraries.add(library);

        if (library instanceof CodeAssistAndroidLibrary) {
            CodeAssistAndroidLibrary androidLibrary = (CodeAssistAndroidLibrary) library;
            List<File> compileJarFiles = androidLibrary.getCompileJarFiles();
            for (File compileJarFile : compileJarFiles) {
                try {
                    putJar(compileJarFile);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        } else {
            super.addLibrary(library);
        }
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

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    @Override
    public String getPackageName() {
        return packageName;
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

    @Override
    public void setProject(Project project) {
        this.project = project;
    }

    @Override
    public Project getProject() {
        return project;
    }

    public void addModuleDependency(String targetModuleName) {
        moduleDependencies.add(targetModuleName);
    }

    @Override
    public Set<String> getModuleDependencies() {
        return moduleDependencies;
    }

    @Override
    public void addContentRoot(ContentRoot contentRoot) {
        contentRoots.add(contentRoot);
    }

    @Override
    public Set<ContentRoot> getContentRoots() {
        return contentRoots;
    }

    /**
     * Sets the name of this module
     * @param name Typically the gradle path of the module e.g. :app
     */
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    @Override
    public String getNamespace() {
        return namespace;
    }
}
