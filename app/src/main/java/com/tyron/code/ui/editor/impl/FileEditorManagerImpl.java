package com.tyron.code.ui.editor.impl;

import android.content.Context;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tyron.code.R;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.common.ApplicationProvider;
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

    private MainViewModel mViewModel;

    FileEditorManagerImpl() {

    }

    public void attach(MainViewModel mainViewModel) {
        mViewModel = mainViewModel;
    }

    @Override
    public void openFile(@NonNull Context context, File file, Consumer<FileEditor> callback) {
        checkAttached();

        FileEditor[] fileEditors = openFile(file, true);
        openChooser(context, fileEditors, callback);
    }

    @NonNull
    @Override
    public FileEditor[] openFile(@NonNull File file, boolean focus) {
        checkAttached();

        FileEditor[] editors;
        FileEditorProvider[] providers = FileEditorProviderManagerImpl.getInstance().getProviders(file);
        editors = new FileEditor[providers.length];
        for (int i = 0; i < providers.length; i++) {
            FileEditor editor = providers[i].createEditor(file);
            editors[i] = editor;
        }

        openChooser(editors, mViewModel::openFile);
        return editors;
    }

    @Override
    public void closeFile(@NonNull File file) {
        mViewModel.removeFile(file);
    }

    private void openChooser(FileEditor[] fileEditors, Consumer<FileEditor> callback) {
        openChooser(ApplicationProvider.getApplicationContext(), fileEditors, callback);
    }

    private void openChooser(Context context, FileEditor[] fileEditors, Consumer<FileEditor> callback) {
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

    private void checkAttached() {
        if (mViewModel == null) {
            throw new IllegalStateException("File editor manager is not yet attached to a ViewModel");
        }
    }
}
