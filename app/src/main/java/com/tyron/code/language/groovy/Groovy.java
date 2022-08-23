package com.tyron.code.language.groovy;

import com.tyron.code.language.Language;
import com.tyron.code.language.LanguageManager;
import com.tyron.editor.Editor;

import org.apache.commons.vfs2.FileObject;

import java.io.File;

public class Groovy implements Language {

    private static final String GRAMMAR_NAME = "groovy.tmLanguage";
    private static final String LANGUAGE_PATH = "textmate/groovy/syntaxes/groovy.tmLanguage";
    private static final String CONFIG_PATH = "textmate/groovy/language-configuration.json";

    @Override
    public boolean isApplicable(File ext) {
        return ext.getName().endsWith(".groovy") || ext.getName().endsWith(".gradle");
    }

    @Override
    public boolean isApplicable(FileObject fileObject) {
        String extension = fileObject.getName().getExtension();
        return "groovy".equals(extension) || "gradle".equals(extension);
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