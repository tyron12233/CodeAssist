package com.tyron.code.ui.editor.impl;

import androidx.annotation.NonNull;

import com.google.common.eventbus.EventBus;
import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.api.FileEditor;
import com.tyron.code.ui.editor.api.FileEditorManager;
import com.tyron.code.ui.editor.api.FileEditorProvider;
import com.tyron.code.ui.editor.api.FileEditorProviderManager;

import java.io.File;

public class FileEditorManagerImpl extends FileEditorManager {

    private final Project mProject;
    private final FileEditorProviderManager mProviderManager;

    public FileEditorManagerImpl(@NonNull Project project, FileEditorProviderManager providerManager) {
        mProject = project;
        mProviderManager = providerManager;
    }

    @NonNull
    @Override
    public FileEditor[] openFile(@NonNull File file, boolean focus) {
        FileEditor[] editors;
        FileEditorProvider[] providers = mProviderManager.getProviders(mProject, file);
        editors = new FileEditor[providers.length];
        for (int i = 0; i < providers.length; i++) {
            FileEditor editor = providers[i].createEditor(mProject, file);
            editors[i] = editor;
        }
        return editors;
    }

    @Override
    public void closeFile(@NonNull File file) {

    }
}
