package com.tyron.builder.project.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.CommonProjectKeys;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Project;
import com.tyron.builder.project.indexer.Indexer;
import com.tyron.builder.project.indexer.JavaFilesIndexer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AtomicFieldUpdater;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;

import java.io.File;
import java.util.List;

public class FileManagerImpl implements FileManager {

    /**
     * Concurrent writes to this field are via CASes only, using the {@link #updater}
     */
    @NotNull
    private volatile KeyFMap myUserMap = KeyFMap.EMPTY_MAP;

    private Project myProject;

    @Override
    public void open(Project project) {
        myProject = project;
    }

    @Override
    public void index() {
        putUserData(CommonProjectKeys.JAVA_FILES_KEY, index(new JavaFilesIndexer()));
    }

    private List<File> index(Indexer indexer) {
        return indexer.index(myProject);
    }

    @Override
    public void delete(File file) {

    }

    @NonNull
    @Override
    public <T> T putUserDataIfAbsent(@NonNull Key<T> key, @NonNull T value) {
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
    public <T> boolean replace(@NonNull Key<T> key, @Nullable T oldValue, @Nullable T newValue) {
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

    @Nullable
    @Override
    public <T> T getUserData(@NonNull Key<T> key) {
        return myUserMap.get(key);
    }

    @Override
    public <T> void putUserData(@NonNull Key<T> key, @Nullable T value) {
        while (true) {
            KeyFMap map = myUserMap;
            KeyFMap newMap = value == null ? map.minus(key) : map.plus(key, value);
            if (newMap == map || updater.compareAndSet(this, map, newMap)) {
                break;
            }
        }
    }

    private static final AtomicFieldUpdater<FileManagerImpl, KeyFMap> updater = AtomicFieldUpdater.forFieldOfType(FileManagerImpl.class, KeyFMap.class);
}
