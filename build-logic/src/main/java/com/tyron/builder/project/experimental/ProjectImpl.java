package com.tyron.builder.project.experimental;

import static com.tyron.builder.project.CommonProjectKeys.CONFIG_FILE_KEY;

import androidx.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.api.Project;
import com.tyron.builder.project.impl.FileManagerImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AtomicFieldUpdater;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProjectImpl implements Project {

    private final List<Module> mModules;

    /**
     * Concurrent writes to this field are via CASes only, using the {@link #updater}
     */
    @NotNull
    private volatile KeyFMap myUserMap = KeyFMap.EMPTY_MAP;
    private final File mRoot;
    private ProjectSettings myProjectSettings;
    private FileManager myFileManager;

    public ProjectImpl(File root) {
        mRoot = root;
        putUserData(CONFIG_FILE_KEY, new File(root, "app_config.json"));
        mModules = new ArrayList<>();
        myFileManager = new FileManagerImpl();
    }

    @Override
    public void open() throws IOException {

    }

    @Override
    public void clear() {

    }

    @Override
    public File getBuildDirectory() {
        return null;
    }

    public synchronized void addModule(Module module) {

    }
    @Override
    public List<Module> getModules() {
        return ImmutableList.copyOf(mModules);
    }

    @Override
    public ProjectSettings getSettings() {
        return myProjectSettings;
    }

    @Override
    public File getRootFile() {
        return mRoot;
    }

    @Nullable
    @Override
    public <T> T getUserData(@NotNull Key<T> key) {
        return myUserMap.get(key);
    }

    @Override
    public <T> void putUserData(@NotNull Key<T> key, @Nullable T value) {
        while (true) {
            KeyFMap map = myUserMap;
            KeyFMap newMap = value == null ? map.minus(key) : map.plus(key, value);
            if (newMap == map || updater.compareAndSet(this, map, newMap)) {
                break;
            }
        }
    }

    @NotNull
    @Override
    public <T> T putUserDataIfAbsent(@NotNull Key<T> key, @NotNull T value) {
        while (true) {
            KeyFMap map = myUserMap;
            T oldValue = map.get(key);
            if (oldValue != null) {
                return oldValue;
            }
            KeyFMap newMap = map.plus(key, value);
            if (newMap == map || updater.compareAndSet(this, map, newMap)) {
                return value;
            }
        }
    }

    @Override
    public <T> boolean replace(@NotNull Key<T> key, @Nullable T oldValue, @Nullable T newValue) {
        while (true) {
            KeyFMap map = myUserMap;
            if (map.get(key) != oldValue) {
                return false;
            }
            KeyFMap newMap = newValue == null ? map.minus(key) : map.plus(key, newValue);
            if (newMap == map || updater.compareAndSet(this, map, newMap)) {
                return true;
            }
        }
    }

    private static final AtomicFieldUpdater<ProjectImpl, KeyFMap> updater = AtomicFieldUpdater.forFieldOfType(ProjectImpl.class, KeyFMap.class);
}
