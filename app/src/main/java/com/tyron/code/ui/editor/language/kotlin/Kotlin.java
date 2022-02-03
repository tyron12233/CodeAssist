package com.tyron.code.ui.editor.language.kotlin;

import com.tyron.code.ui.editor.language.Language;
import com.tyron.editor.Editor;

import java.io.File;

import io.github.rosemoe.sora2.interfaces.EditorLanguage;
import io.github.rosemoe.sora2.widget.CodeEditor;

public class Kotlin implements Language {
    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".kt");
    }

    @Override
    public io.github.rosemoe.sora.lang.Language get(Editor editor) {
        return new KotlinLanguage(editor);
    }
}
