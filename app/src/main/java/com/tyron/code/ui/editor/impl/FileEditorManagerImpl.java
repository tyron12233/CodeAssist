package com.tyron.code.ui.editor.impl;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.R;
import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorManager;
import com.tyron.fileeditor.api.FileEditorProvider;

import java.io.File;
import java.util.Arrays;
import java.util.function.Consumer;

public class FileEditorManagerImpl extends FileEditorManager {

    private static volatile FileEditorManager sInstance = null;

    public static synchronized FileEditorManager getInstance() {
        if (sInstance == null) {
            sInstance = new FileEditorManagerImpl();
        }
        return sInstance;
    }

    @Override
    public void openFile(@NonNull Context context, File file, Consumer<FileEditor> callback) {
        FileEditor[] fileEditors = openFile(file, true);
        if (fileEditors.length == 0) {
            return;
        }
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

    public FileEditorManagerImpl() {

    }

    @NonNull
    @Override
    public FileEditor[] openFile(@NonNull File file, boolean focus) {
        FileEditor[] editors;
        FileEditorProvider[] providers = FileEditorProviderManagerImpl.getInstance().getProviders(file);
        editors = new FileEditor[providers.length];
        for (int i = 0; i < providers.length; i++) {
            FileEditor editor = providers[i].createEditor(file);
            editors[i] = editor;
        }
        return editors;
    }

    @Override
    public void closeFile(@NonNull File file) {

    }
}
