package com.tyron.builder.project.api;

import com.tyron.builder.model.ProjectSettings;

import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderEx;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface Project extends UserDataHolderEx {

    ProjectSettings getSettings();

    FileManager getFileManager();

    List<Module> getModules();

    File getRootFile();

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
}
