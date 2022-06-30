package com.tyron.builder.project.mock;

import androidx.annotation.NonNull;

import com.tyron.builder.model.Library;
import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.impl.ModuleImpl;
import com.tyron.builder.project.util.PackageTrie;
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
import java.util.Set;

/**
 * Used for testing, java files can be added manually and
 * files are specified manually
 */
public class MockJavaModule extends ModuleImpl implements JavaModule {

    private final KeyFMap mDataMap = KeyFMap.EMPTY_MAP;
    private final Map<String, File> mJavaFiles = new HashMap<>();

    private final FileManager mFileManager;
    private final File mRootDir;
    private File mLambdaStubsJarFile;
    private File mBootstrapJarFile;
    public MockJavaModule(File rootDir, FileManager fileManager) {
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
    public void putLibraryHashes(Map<String, Library> hashes) {
        // no op
    }

    @androidx.annotation.Nullable
    @Override
    public Library getLibrary(String hash) {
        return null;
    }

    @Override
    public Set<String> getAllClasses() {
        return Collections.emptySet();
    }

    @NonNull
    @Override
    public PackageTrie getClassIndex() {
        return new PackageTrie();
    }

    @NonNull
    @Override
    public File getResourcesDir() {
        return new File(mRootDir, "src/main/resources");
    }

    @NonNull
    @Override
    public File getJavaDirectory() {
        return new File(mRootDir, "src/main/java");
    }

    @Override
    public File getLibraryDirectory() {
        return new File(mRootDir, "libs");
    }

    @Override
    public File getLibraryFile() {
        return new File(mRootDir, "libraries.json");
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

    @Override
    public Map<String, File> getInjectedClasses() {
        return null;
    }

    @Override
    public void addInjectedClass(@NonNull File file) {

    }

    public void setBootstrapFile(File file) {
        if (!file.exists()) {
            throw new IllegalArgumentException("Bootstrap jar file does not exist");
        }
        mBootstrapJarFile = file;
    }

    @Override
    public ModuleSettings getSettings() {
        return new MockModuleSettings();
    }

    @Override
    public FileManager getFileManager() {
        return mFileManager;
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
        return new File(getRootFile(), "build");
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
