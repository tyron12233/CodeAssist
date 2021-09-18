package com.tyron.code.ui.editor.language.kotlin;

import com.tyron.code.ui.editor.language.Language;

import java.io.File;

import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.widget.CodeEditor;

public class Kotlin implements Language {
    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".kt");
    }

    @Override
    public EditorLanguage get(CodeEditor editor) {
        return new KotlinLanguage(editor);
    }
}
