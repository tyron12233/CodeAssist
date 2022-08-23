package com.tyron.code.language.kotlin;

import com.tyron.code.language.Language;
import com.tyron.code.language.LanguageManager;
import com.tyron.editor.Editor;

import java.io.File;

public class Kotlin implements Language {

    private static final String GRAMMAR_NAME = "kotlin.tmLanguage";
    private static final String LANGUAGE_PATH = "textmate/kotlin/syntaxes/kotlin.tmLanguage";
    private static final String CONFIG_PATH = "textmate/kotlin/language-configuration.json";

    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".kt");
    }

    @Override
    public io.github.rosemoe.sora.lang.Language get(Editor editor) {
        return LanguageManager.createTextMateLanguage(
                GRAMMAR_NAME,
                LANGUAGE_PATH,
                CONFIG_PATH,
                editor
        );
    }
}
