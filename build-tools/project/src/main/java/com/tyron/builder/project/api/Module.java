package com.tyron.builder.project.api;

import com.tyron.builder.model.ModuleSettings;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.cache.CacheHolder;

import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderEx;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Set;

public interface Module extends UserDataHolderEx, CacheHolder {

    @Deprecated
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
    @Deprecated
    File getBuildDirectory();





    // NEW API

    default void addChildModule(Module module) {

    }


    default Set<String> getModuleDependencies() {
        return Collections.emptySet();
    }

    default void addContentRoot(ContentRoot contentRoot) {

    }

    default Set<ContentRoot> getContentRoots() {
        return Collections.emptySet();
    }

    /**
     *
     * @return The project that this module is part of
     */
    default Project getProject() {
        throw new UnsupportedOperationException();
    }

    default void setProject(Project project) {
        throw new UnsupportedOperationException();
    }
}
