package com.tyron.code.ui.editor.language.groovy;

import com.tyron.code.ui.editor.language.Language;

import java.io.File;

import io.github.rosemoe.sora.interfaces.EditorLanguage;
import io.github.rosemoe.sora.widget.CodeEditor;

public class Groovy implements Language {
    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".groovy") || ext.getName().endsWith(".gradle");
    }

    @Override
    public EditorLanguage get(CodeEditor editor) {
        return new GroovyLanguage(editor);
    }
}
