package com.tyron.builder.project;

import static com.tyron.builder.project.CommonProjectKeys.ASSETS_DIR_KEY;
import static com.tyron.builder.project.CommonProjectKeys.CONFIG_FILE_KEY;
import static com.tyron.builder.project.CommonProjectKeys.MANIFEST_DATA_KEY;
import static com.tyron.builder.project.CommonProjectKeys.MANIFEST_FILE_KEY;
import static com.tyron.builder.project.CommonProjectKeys.NATIVE_LIBS_DIR_KEY;
import static com.tyron.builder.project.CommonProjectKeys.ROOT_DIR_KEY;

import androidx.annotation.Nullable;

import com.tyron.builder.compiler.manifest.xml.AndroidManifestParser;
import com.tyron.builder.model.ProjectSettings;
import com.tyron.common.util.FileUtilsEx;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.util.concurrency.AtomicFieldUpdater;
import org.jetbrains.kotlin.com.intellij.util.keyFMap.KeyFMap;

import java.io.File;
import java.io.IOException;

public class ProjectImpl implements Project {

    /**
     * Concurrent writes to this field are via CASes only, using the {@link #updater}
     */
    @NotNull
    private volatile KeyFMap myUserMap = KeyFMap.EMPTY_MAP;
    private ProjectSettings myProjectSettings;
    private FileManager myFileManager;

    public ProjectImpl(File root) {
        putUserData(ROOT_DIR_KEY, root);
        putUserData(ASSETS_DIR_KEY, new File(root, "app/src/main/assets"));
        putUserData(NATIVE_LIBS_DIR_KEY, new File(root, "app/src/main/jniLibs"));
        putUserData(MANIFEST_FILE_KEY, new File(root, "app/src/main/AndroidManifest.xml"));
        putUserData(CONFIG_FILE_KEY, new File(root, "app_config.json"));

        myFileManager = new FileManagerImpl();
    }

    @SuppressWarnings("ConstantConditions") // keys are guaranteed to be non null here
    @Override
    public void open() throws IOException {
        if (!getUserData(ROOT_DIR_KEY).exists()) {
            throw new IOException("Project root directory does not exist");
        }
        myFileManager.open(this);
        putUserData(MANIFEST_DATA_KEY, AndroidManifestParser.parse(getUserData(MANIFEST_FILE_KEY)));
        File configFile = getUserData(CONFIG_FILE_KEY);
        if (!configFile.exists()) {
            FileUtilsEx.createFile(configFile);
        }
        myProjectSettings = new ProjectSettings(configFile);
    }

    @Override
    public ProjectSettings getSettings() {
        return myProjectSettings;
    }

    @Override
    public FileManager getFileManager() {
        return myFileManager;
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
