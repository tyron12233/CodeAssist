package com.tyron.fileeditor.api;

import androidx.annotation.NonNull;

import java.io.File;

public interface FileEditorProvider {

    /**
     * @param file file to be tested for acceptance, this parameter is never null
     *
     * @return whether the provider can create a valid editor instance for the
     * specified file.
     */
    boolean accept(@NonNull File file);

    /**
     * Creates the editor for the specified file. This method is only called
     * if the provider has accepted this file in {@link #accept(File)}
     *
     * @return the created editor for this file, never null.
     */
    @NonNull
    FileEditor createEditor(@NonNull File file);

    /**
     * @return a unique id representing this file editor among others
     */
    @NonNull
    String getEditorTypeId();
}
