package com.tyron.builder.project.experimental;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.compiler2.api.Task;
import com.tyron.builder.compiler2.impl.java.JavaCompile;

import org.jetbrains.kotlin.com.intellij.openapi.util.Key;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavaModule extends ModuleImpl {

    public static final Key<File> JAVA_DIR_KEY = Key.create("javaDir");
    public static final Key<File> RESOURCES_DIR_KEY = Key.create("resouresDir");
    public static final Key<File> LIBRARY_DIR_KEY = Key.create("libraryDir");

    private final List<File> mLibraries;
    /**
     * Map of packageName to its corresponding File
     */
    private  volatile Map<String, File> mFilesMap = new HashMap<>();

    public JavaModule() {
        mLibraries = new ArrayList<>();
    }

    public synchronized void addJavaFile(String packageName, File file) {
        mFilesMap.put(packageName, file);
    }

    public List<File> getJavaFiles() {
        return ImmutableList.copyOf(mFilesMap.values());
    }

    public synchronized File getJavaFile(String packageName) {
        return mFilesMap.get(packageName);
    }

    public synchronized void addLibrary(File file) {
        assert file.isFile();
        mLibraries.add(file);
    }

    public synchronized List<File> getLibraries() {
        return ImmutableList.copyOf(mLibraries);
    }

    // TODO: allow customization of tasks
    @Override
    public List<Task> getTasks() {
        List<Task> tasks = new ArrayList<>();
        tasks.add(new JavaCompile(this));
        return tasks;
    }
}