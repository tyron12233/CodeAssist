package com.tyron.code.ui.editor.api;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;

import java.io.File;

/**
 * Base class for providing Editor fragments
 */
public abstract class FileEditorFactory {

    /**
     * Create an Editor instance from a given file
     * @param file file object to read
     * @return Editor instance with the file contents
     */
    protected abstract FileEditor createEditor(@NonNull Project project, @NonNull File file);
}
