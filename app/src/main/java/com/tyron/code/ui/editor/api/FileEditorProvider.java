package com.tyron.code.ui.editor.api;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;

import java.io.File;

public interface FileEditorProvider {

    /**
     * @param project the project associated with this file
     * @param file file to be tested for acceptance, this parameter is never null
     *
     * @return whether the provider can create a valid editor instance for the
     * specified file.
     */
    boolean accept(@NonNull Project project, @NonNull File file);

    /**
     * Creates the editor for the specified file. This method is only called
     * if the provider has accepted this file in {@link #accept(Project, File)}
     *
     * @return the created editor for this file, never null.
     */
    @NonNull
    FileEditor createEditor(@NonNull Project project, @NonNull File file);

    /**
     * @return a unique id representing this file editor among others
     */
    @NonNull
    String getEditorTypeId();
}
