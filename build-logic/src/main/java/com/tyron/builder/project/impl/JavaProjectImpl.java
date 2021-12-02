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
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class JavaProjectImpl extends ProjectImpl implements JavaProject {

    // Map of fully qualified names and the jar they are contained in
    private final Map<String, File> mClassFiles;
    private final Map<String, File> mJavaFiles;
    private final Set<File> mLibraries;

    public JavaProjectImpl(File root) {
        super(root);
        mJavaFiles = new HashMap<>();
        mClassFiles = new HashMap<>();
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
        String className;
        if (packageName == null) {
            className = javaFile.getName().replace(".java", "");
        } else {
            className = packageName + "." + javaFile.getName().replace(".java", "");
        }
        mJavaFiles.put(className, javaFile);
    }

    @Override
    public List<String> getAllClasses() {
        List<String> classes = new ArrayList<>();
        classes.addAll(mJavaFiles.keySet());
        classes.addAll(mClassFiles.keySet());
        return classes;
    }

    @Override
    public List<File> getLibraries() {
        return ImmutableList.copyOf(mLibraries);
    }

    @Override
    public void addLibrary(@NonNull File jar) {
        mLibraries.add(jar);
        try {
            putJar(jar);
        } catch (IOException e) {
            // ignored
        }
    }

    private void putJar(File file) throws IOException {
        if (file == null) {
            return;
        }
        try (JarFile jar = new JarFile(file)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (!entry.getName().endsWith(".class")) {
                    continue;
                }

                // We only want top level classes, if it contains $ then
                // its an inner class, we ignore it
                if (entry.getName().contains("$")) {
                    continue;
                }

                String packageName = entry.getName().replace("/", ".")
                        .substring(0, entry.getName().length() - ".class".length());

                mClassFiles.put(packageName, file);
            }
        }
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
    public File getLibraryDirectory() {
        File custom = getPathSetting("library_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "app/libs");
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
        if (getJavaDirectory().exists()) {
            FileUtils.iterateFiles(getJavaDirectory(),
                    FileFilterUtils.suffixFileFilter(".java"),
                    TrueFileFilter.INSTANCE
            ).forEachRemaining(this::addJavaFile);
        }

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

        try {
            putJar(BuildModule.getAndroidJar());
        } catch (IOException e) {
            // ignored
        }
    }

    @Override
    public void clear() {
        mJavaFiles.clear();
        mLibraries.clear();
    }
}