package com.tyron.code.ui.editor.language.json;

import com.tyron.code.ui.editor.language.Language;

import java.io.File;

import io.github.rosemoe.sora2.interfaces.EditorLanguage;
import io.github.rosemoe.sora2.widget.CodeEditor;

public class Json implements Language {
    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".json");
    }

    @Override
    public EditorLanguage get(CodeEditor editor) {
        return new JsonLanguage(editor);
    }
}
