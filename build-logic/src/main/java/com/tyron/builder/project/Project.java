package com.tyron.builder.project;

import com.tyron.builder.model.ProjectSettings;

import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderEx;

import java.io.IOException;

public interface Project extends UserDataHolderEx {

    ProjectSettings getSettings();

    FileManager getFileManager();

    /**
     * Start parsing the project contents such as manifest data, project settings, etc.
     *
     * Implementations may throw an IOException if something went wrong during parsing
     */
    void open() throws IOException;
}
