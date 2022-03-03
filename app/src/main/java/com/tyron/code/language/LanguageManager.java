package com.tyron.code.language;

import com.tyron.code.language.groovy.Groovy;
import com.tyron.code.language.java.Java;
import com.tyron.code.language.json.Json;
import com.tyron.code.language.kotlin.Kotlin;
import com.tyron.code.language.xml.Xml;
import com.tyron.editor.Editor;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class LanguageManager {

    private static LanguageManager Instance = null;

    public static LanguageManager getInstance() {
        if (Instance == null) {
            Instance = new LanguageManager();
        }
        return Instance;
    }

    private final Set<Language> mLanguages = new HashSet<>();

    private LanguageManager() {
        initLanguages();
    }

    private void initLanguages() {
        mLanguages.addAll(
                Arrays.asList(
                        new Xml(),
                        new Java(),
                        new Kotlin(),
                        new Groovy(),
                        new Json()));
    }

    public boolean supports(File file) {
        for (Language language : mLanguages) {
            if (language.isApplicable(file)) {
                return true;
            }
        }
        return false;
    }

    public io.github.rosemoe.sora.lang.Language get(Editor editor, File file) {
        for (Language lang : mLanguages) {
            if (lang.isApplicable(file)) {
                return lang.get(editor);
            }
        }
        return null;
    }
}
