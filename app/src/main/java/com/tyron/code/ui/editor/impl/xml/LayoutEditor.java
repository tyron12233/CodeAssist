package com.tyron.code.ui.editor.impl.xml;

import androidx.annotation.NonNull;

import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorFragment;
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeCodeEditor;
import com.tyron.code.ui.editor.impl.text.rosemoe.RosemoeEditorProvider;

import java.io.File;

public class LayoutEditor extends RosemoeCodeEditor {

    public LayoutEditor(File file, RosemoeEditorProvider provider) {
        super(file, provider);
    }

    @Override
    protected CodeEditorFragment createFragment(File file) {
        return LayoutTextEditorFragment.newInstance(file);
    }

    @NonNull
    @Override
    public String getName() {
        return "Layout Editor";
    }
}
