package com.tyron.builder.project.mock;

import androidx.annotation.NonNull;

import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.JavaProject;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.ProjectImpl;
import com.tyron.common.util.StringSearch;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.KeyWithDefaultValue;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Used for testing, java files can be added manually and
 * files are specified manually
 */
public class MockJavaProject extends ProjectImpl implements JavaProject {

    private final KeyFMap mDataMap = KeyFMap.EMPTY_MAP;
    private final Map<String, File> mJavaFiles = new HashMap<>();

    private final FileManager mFileManager;
    private final File mRootDir;
    private File mLambdaStubsJarFile;
    private File mBootstrapJarFile;
    public MockJavaProject(File rootDir, FileManager fileManager) {
        super(rootDir);
        mRootDir = rootDir;
        mFileManager = fileManager;
    }

    @NonNull
    @Override
    public Map<String, File> getJavaFiles() {
        return mJavaFiles;
    }

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
    public List<File> getLibraries() {
        return Collections.emptyList();
    }

    @Override
    public void addLibrary(@NonNull File jar) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> getAllClasses() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public File getResourcesDir() {
        return null;
    }

    @NonNull
    @Override
    public File getJavaDirectory() {
        return new File(mRootDir, "app/src/main/java");
    }

    @Override
    public File getLibraryDirectory() {
        return new File(mRootDir, "app/libs");
    }

    @Override
    public File getLambdaStubsJarFile() {
        return mLambdaStubsJarFile;
    }

    public void setLambdaStubsJarFile(File file) {
        if (!file.exists()) {
            throw new IllegalArgumentException("Lambda stubs jar file does not exist");
        }
        mLambdaStubsJarFile = file;
    }

    @Override
    public File getBootstrapJarFile() {
        return mBootstrapJarFile;
    }

    public void setBootstrapFile(File file) {
        if (!file.exists()) {
            throw new IllegalArgumentException("Bootstrap jar file does not exist");
        }
        mBootstrapJarFile = file;
    }

    @Override
    public ProjectSettings getSettings() {
        return null;
    }

    @Override
    public FileManager getFileManager() {
        return mFileManager;
    }

    @Override
    public List<Module> getModules() {
        return Collections.emptyList();
    }

    @Override
    public File getRootFile() {
        return mRootDir;
    }

    @Override
    public void open() throws IOException {

    }

    @Override
    public void clear() {
        mJavaFiles.clear();
    }

    @Override
    public void index() {

    }

    @Override
    public File getBuildDirectory() {
        return new File(getRootFile(), "app/build");
    }

    @NonNull
    @Override
    public <T> T putUserDataIfAbsent(@NotNull Key<T> key, @NotNull T t) {
        if (mDataMap.get(key) == null) {
            mDataMap.plus(key, t);
        }
        return t;
    }

    @Override
    public <T> boolean replace(@NotNull Key<T> key, @Nullable T t, @Nullable T t1) {
        return false;
    }

    @Override
    public <T> T getUserData(@NotNull Key<T> key) {
        T value = mDataMap.get(key);
        if (value == null && key instanceof KeyWithDefaultValue) {
            return ((KeyWithDefaultValue<T>) key).getDefaultValue();
        }
        return value;
    }

    @Override
    public <T> void putUserData(@NotNull Key<T> key, @Nullable T t) {
        mDataMap.plus(key, t);
    }
}
