package com.tyron.builder.project.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.BuildModule;
import com.tyron.builder.project.api.JavaProject;
import com.tyron.common.util.StringSearch;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class JavaProjectImpl extends ProjectImpl implements JavaProject {

    private final Map<String, File> mJavaFiles;
    private final Set<File> mLibraries;

    public JavaProjectImpl(File root) {
        super(root);
        mJavaFiles = new HashMap<>();
        mLibraries = new HashSet<>();
    }

    @NonNull
    @Override
    public Map<String, File> getJavaFiles() {
        return mJavaFiles;
    }

    @Nullable
    @Override
    public File getJavaFile(@NonNull String packageName) {
        return mJavaFiles.get(packageName);
    }

    @Override
    public void removeJavaFile(@NonNull String packageName) {
        mJavaFiles.remove(packageName);
    }

    @Override
    public void addJavaFile(@NonNull File javaFile) {
        String packageName = StringSearch.packageName(javaFile);
        if (packageName == null) {
            packageName = "";
        }
        mJavaFiles.put(packageName, javaFile);
    }

    @Override
    public List<File> getLibraries() {
        return ImmutableList.copyOf(mLibraries);
    }

    @NonNull
    @Override
    public File getResourcesDir() {
        File custom = getPathSetting("java_resources_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "app/src/main/resources");
    }

    @NonNull
    @Override
    public File getJavaDirectory() {
        File custom = getPathSetting("java_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "app/src/main/java");
    }

    @Override
    public File getLambdaStubsJarFile() {
        return BuildModule.getLambdaStubs();
    }

    @Override
    public File getBootstrapJarFile() {
        return BuildModule.getAndroidJar();
    }


    @Override
    public void open() throws IOException {
        super.open();
    }

    @Override
    public void index() {
        FileUtils.iterateFiles(getJavaDirectory(),
                FileFilterUtils.suffixFileFilter(".java"),
                TrueFileFilter.INSTANCE
        ).forEachRemaining(this::addJavaFile);

        File[] libraryDirectories = new File(getBuildDirectory(), "libs")
                .listFiles(File::isDirectory);
        if (libraryDirectories != null) {
            for (File directory : libraryDirectories) {
                File check = new File(directory, "classes.jar");
                if (check.exists()) {
                    mLibraries.add(check);
                }
            }
        }
    }

    @Override
    public void clear() {
        mJavaFiles.clear();
        mLibraries.clear();
    }
}