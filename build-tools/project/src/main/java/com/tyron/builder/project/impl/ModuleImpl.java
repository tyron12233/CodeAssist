package com.tyron.builder.project.impl;

import androidx.annotation.Nullable;

import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.api.Module;
import com.tyron.common.util.Cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.KeyWithDefaultValue;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AtomicFieldUpdater;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ModuleImpl implements Module {

    /**
     * Concurrent writes to this field are via CASes only, using the {@link #updater}
     */
    @NotNull
    private volatile KeyFMap myUserMap = KeyFMap.EMPTY_MAP;
    private final File mRoot;
    private ModuleSettings myModuleSettings;
    private FileManager mFileManager;

    public ModuleImpl(File root) {
        mRoot = root;
        mFileManager = new FileManagerImpl(root);
    }

    @Override
    public void open() throws IOException {
        myModuleSettings = new ModuleSettings(new File(getRootFile(), "app_config.json"));
    }

    @Override
    public void clear() {

    }

    @Override
    public void index() {

    }

    @Override
    public File getBuildDirectory() {
        File custom = getPathSetting("build_directory");
        if (custom.exists()) {
            return custom;
        }
        return new File(getRootFile(), "build");
    }

    @Override
    public ModuleSettings getSettings() {
        return myModuleSettings;
    }

    @Override
    public FileManager getFileManager() {
        return mFileManager;
    }

    @Override
    public File getRootFile() {
        return mRoot;
    }

    @Nullable
    @Override
    public <T> T getUserData(@NotNull Key<T> key) {
        T t = myUserMap.get(key);
        if (t == null && key instanceof KeyWithDefaultValue) {
            t = ((KeyWithDefaultValue<T>) key).getDefaultValue();
            putUserData(key, t);
        }
        return t;
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

    protected File getPathSetting(String key) {
        String path = getSettings().getString(key, "");
        return new File(path);
    }

    @Override
    public int hashCode() {
        return mRoot.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModuleImpl)) return false;
        ModuleImpl project = (ModuleImpl) o;
        return mRoot.equals(project.mRoot);
    }

    private static final AtomicFieldUpdater<ModuleImpl, KeyFMap> updater = AtomicFieldUpdater.forFieldOfType(ModuleImpl.class, KeyFMap.class);

    private final Map<CacheKey<?, ?>, Cache<?, ?>> mCacheMap = new HashMap<>();

    @Override
    public <K, V> Cache<K, V> getCache(CacheKey<K, V> key, Cache<K, V> defaultValue) {
        Object o = mCacheMap.get(key);
        if (o == null) {
            put(key, defaultValue);
            return defaultValue;
        }
        //noinspection unchecked
        return (Cache<K, V>) o;
    }

    public <K, V> void removeCache(CacheKey<K, V> key) {
        mCacheMap.remove(key);
    }

    @Override
    public <K, V> void put(CacheKey<K, V> key, Cache<K, V> value) {
        mCacheMap.put(key, value);
    }
}
