package com.tyron.builder.project.api;

import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.cache.CacheHolder;

import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderEx;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public interface Module extends UserDataHolderEx, CacheHolder {

    ModuleSettings getSettings();

    FileManager getFileManager();

    File getRootFile();

    default String getName() {
        return getRootFile().getName();
    }

    /**
     * Start parsing the project contents such as manifest data, project settings, etc.
     *
     * Implementations may throw an IOException if something went wrong during parsing
     */
    void open() throws IOException;

    /**
     * Remove all the indexed files
     */
    void clear();

    void index();

    /**
     * @return The directory that this project can use to compile files
     */
    File getBuildDirectory();

    default void addChildModule(Module module) {

    }

    default boolean containsFile(File file) {
        try {
            File rootFile = getRootFile().getCanonicalFile();
            File absoluteFile = file.getCanonicalFile();

            return absoluteFile.exists() && absoluteFile.getAbsolutePath().startsWith(rootFile.getAbsolutePath());
        } catch (IOException e) {
            return false;
        }
    }
}
