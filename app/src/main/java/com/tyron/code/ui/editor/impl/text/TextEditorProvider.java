package com.tyron.code.ui.editor.impl.text;

import androidx.annotation.NonNull;

import com.tyron.builder.project.Project;
import com.tyron.code.ui.editor.api.FileEditor;
import com.tyron.code.ui.editor.api.FileEditorProvider;

import java.io.File;

public class TextEditorProvider implements FileEditorProvider {

    public static final String TYPE_ID = "text-editor";

    @Override
    public boolean accept(@NonNull Project project, @NonNull File file) {
        return !file.isDirectory();
    }

    @NonNull
    @Override
    public FileEditor createEditor(@NonNull Project project, @NonNull File file) {
        return new TextEditorImpl(project, file, this);
    }

    @NonNull
    @Override
    public String getEditorTypeId() {
        return TYPE_ID;
    }
}
