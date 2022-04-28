package com.tyron.code.ui.editor.impl.xml;

import androidx.annotation.NonNull;

import com.tyron.fileeditor.api.FileEditor;
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorProvider;
import com.tyron.code.util.ProjectUtils;

import java.io.File;

public class LayoutTextEditorProvider extends RosemoeEditorProvider {

    private static final String TYPE_ID = "layout-rosemoe-code-editor";

    @Override
    public boolean accept(@NonNull File file) {
        if (file.isDirectory()) {
            return false;
        }
        return ProjectUtils.isLayoutXMLFile(file);
    }

    @NonNull
    @Override
    public FileEditor createEditor(@NonNull File file) {
        return new LayoutEditor(file, this);
    }

    @NonNull
    @Override
    public String getEditorTypeId() {
        return TYPE_ID;
    }
}
