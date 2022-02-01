package com.tyron.code.ui.editor.language.json;

import com.tyron.code.ui.editor.language.Language;
import com.tyron.editor.Editor;

import java.io.File;

import io.github.rosemoe.sora.widget.CodeEditor;

public class Json implements Language {
    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".json");
    }

    @Override
    public io.github.rosemoe.sora.lang.Language get(Editor editor) {
        return new JsonLanguage(editor);
    }

}