package com.tyron.code.ui.editor.impl;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.api.FileEditor;
import com.tyron.code.ui.editor.api.FileEditorFactory;

import java.io.File;

public class FileEditorFactoryImpl extends FileEditorFactory {
    @Override
    protected FileEditor createEditor(@NonNull Project project, @NonNull File file) {
        return null;
    }
}
