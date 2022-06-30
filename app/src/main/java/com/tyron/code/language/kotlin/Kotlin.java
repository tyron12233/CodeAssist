package com.tyron.code.language.kotlin;

import com.tyron.code.language.Language;
import com.tyron.editor.Editor;

import java.io.File;

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
