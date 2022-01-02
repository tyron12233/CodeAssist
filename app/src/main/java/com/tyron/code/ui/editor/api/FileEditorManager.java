package com.tyron.code.ui.editor.api;

import androidx.annotation.NonNull;

import java.io.File;

public abstract class FileEditorManager {

    /**
     * @param file file to open. Parameter cannot be null. File should be valid.
     *
     * @return array of opened editors
     */
    @NonNull
    public abstract FileEditor[] openFile(@NonNull File file, boolean focus);

    /**
     * Closes all editors opened for the file.
     *
     * @param file file to be closed. Cannot be null.
     */
    public abstract void closeFile(@NonNull File file);
}
