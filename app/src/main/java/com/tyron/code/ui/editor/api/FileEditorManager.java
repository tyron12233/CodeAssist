package com.tyron.code.ui.editor.api;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.R;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.editor.impl.FileEditorSavedState;

import java.io.File;
import java.util.Arrays;
import java.util.function.Consumer;

public abstract class FileEditorManager {

    public static FileEditorManager getInstance() {
        return FileEditorManagerImpl.getInstance();
    }

    /**
     * Open an editor from a saved state
     * @param state the saved state of the editor
     * @return the editor
     */
    public FileEditor openFile(FileEditorSavedState state) {
        FileEditor[] fileEditors = openFile(state.getFile(), true);
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
    public void openFile(@NonNull Context context, File file, Consumer<FileEditor> callback) {
        FileEditor[] fileEditors = openFile(file, true);
        if (fileEditors.length > 1) {
            CharSequence[] items = Arrays.stream(fileEditors)
                    .map(FileEditor::getName)
                    .toArray(String[]::new);
            new MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.file_editor_selection_title)
                    .setItems(items, (__, which) ->
                            callback.accept(fileEditors[which]))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else {
            callback.accept(fileEditors[0]);
        }
    }
    /**
     *
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
