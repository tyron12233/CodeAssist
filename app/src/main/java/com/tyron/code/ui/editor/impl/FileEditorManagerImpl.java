package com.tyron.code.ui.editor.impl;

import androidx.annotation.NonNull;

import com.tyron.code.ui.editor.api.FileEditor;
import com.tyron.code.ui.editor.api.FileEditorManager;
import com.tyron.code.ui.editor.api.FileEditorProvider;

import java.io.File;

public class FileEditorManagerImpl extends FileEditorManager {

    private static volatile FileEditorManager sInstance = null;

    public static synchronized FileEditorManager getInstance() {
        if (sInstance == null) {
            sInstance = new FileEditorManagerImpl();
        }
        return sInstance;
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
