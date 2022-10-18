package com.tyron.code.language;

import android.content.res.AssetManager;

import com.tyron.code.ApplicationLoader;
import com.tyron.code.language.groovy.Groovy;
import com.tyron.code.language.java.Java;
import com.tyron.code.language.json.Json;
import com.tyron.code.language.kotlin.Kotlin;
import com.tyron.code.language.xml.Xml;
import com.tyron.editor.Editor;

import org.apache.commons.vfs2.FileObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.widget.CodeEditor;

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

    public io.github.rosemoe.sora.lang.Language get(Editor editor, FileObject file) {
        for (Language lang : mLanguages) {
            if (lang.isApplicable(file)) {
                return lang.get(editor);
            }
        }
        return null;
    }

    public io.github.rosemoe.sora.lang.Language get(Editor editor, File file) {
        for (Language lang : mLanguages) {
            if (lang.isApplicable(file)) {
                return lang.get(editor);
            }
        }
        return null;
    }

    public static TextMateLanguage createTextMateLanguage(String grammarName, String grammarPath, String configurationPath, Editor editor) {
        AssetManager assets = ApplicationLoader.getInstance().getAssets();
        try {
            return TextMateLanguage.createNoCompletion(
                    grammarName,
                    assets.open(grammarPath),
                    new InputStreamReader(assets.open(configurationPath)),
                    ((TextMateColorScheme) ((CodeEditor) editor).getColorScheme()).getRawTheme());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
