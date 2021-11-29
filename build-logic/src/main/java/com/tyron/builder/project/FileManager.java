package com.tyron.builder.project;

import org.jetbrains.kotlin.com.intellij.openapi.util.UserDataHolderEx;

import java.io.File;

public interface FileManager extends UserDataHolderEx {

    /**
     * Associate this file manager with the given project
     */
    void open(Project project);

    /**
     * Called to give the project a chance to query the root directory and save each files
     * into its own corresponding data
     */
    void index();

    void delete(File file);
}
