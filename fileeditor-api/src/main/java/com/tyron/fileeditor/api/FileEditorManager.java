package com.tyron.fileeditor.api;

import android.content.Context;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.function.Consumer;

public abstract class FileEditorManager {

    /**
     * Open an editor from a saved state
     * @param state the saved state of the editor
     * @return the editor
     */
    public FileEditor openFile(FileEditorSavedState state) {
        FileEditor[] fileEditors = getFileEditors(state.getFile());
        for (FileEditor fileEditor : fileEditors) {
            if (state.getName().equals(fileEditor.getName())) {
                return fileEditor;
            }
        }

        // fallback to the first editor
        return fileEditors[0];
    }

    /**
     * Convenience method to make the user select an editor in a list of file editors,
     * typically shown when there are more than one applicable editors to a file
     *
     * this should be called on the main thread
     * @param context the current context, must not be null
     * @param file the file to be opened
     * @param callback the callback after the user has selected an editor
     */
    public abstract void openFile(@NonNull Context context, File file, Consumer<FileEditor> callback);
    /**
     *
     * @param file file to open. Parameter cannot be null. File should be valid.
     *
     * @return array of opened editors
     */
    @NonNull
    public abstract FileEditor[] openFile(@NonNull Context context, @NonNull File file, boolean focus);

    public abstract FileEditor[] getFileEditors(@NonNull File file);

    public abstract void openFileEditor(@NonNull FileEditor fileEditor);

    /**
     * Closes all editors opened for the file.
     *
     * @param file file to be closed. Cannot be null.
     */
    public abstract void closeFile(@NonNull File file);

    public abstract void openChooser(Context context, FileEditor[] fileEditors, Consumer<FileEditor> callback);
}
