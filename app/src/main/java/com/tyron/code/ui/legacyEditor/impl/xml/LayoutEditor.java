package com.tyron.code.ui.legacyEditor.impl.xml;

import android.content.Context;

import androidx.annotation.NonNull;

import com.tyron.code.ui.legacyEditor.impl.text.rosemoe.CodeEditorFragment;
import com.tyron.code.ui.legacyEditor.impl.text.rosemoe.RosemoeCodeEditor;
import com.tyron.code.ui.legacyEditor.impl.text.rosemoe.RosemoeEditorProvider;

import java.io.File;

public class LayoutEditor extends RosemoeCodeEditor {

    public LayoutEditor(Context context, File file, RosemoeEditorProvider provider) {
        super(context, file, provider);
    }

    protected CodeEditorFragment createFragment(File file) {
        return LayoutTextEditorFragment.newInstance(file);
    }

    @NonNull
    @Override
    public String getName() {
        return "Layout Editor";
    }
}
