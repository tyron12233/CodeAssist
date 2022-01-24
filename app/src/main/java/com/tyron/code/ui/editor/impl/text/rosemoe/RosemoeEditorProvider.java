package com.tyron.code.ui.editor.impl.text.rosemoe;

import androidx.annotation.NonNull;

import com.tyron.fileeditor.api.FileEditor;
import com.tyron.fileeditor.api.FileEditorProvider;

import java.io.File;

public class RosemoeEditorProvider implements FileEditorProvider {

    private static final String TYPE_ID = "rosemoe-code-editor";

    @Override
    public boolean accept(@NonNull File file) {
        return file.exists() && !file.isDirectory();
    }

    @NonNull
    @Override
    public FileEditor createEditor(@NonNull File file) {
        return new RosemoeCodeEditor(file, this);
    }

    @NonNull
    @Override
    public String getEditorTypeId() {
        return TYPE_ID;
    }
}
