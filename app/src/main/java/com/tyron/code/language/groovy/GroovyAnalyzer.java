package com.tyron.code.language.groovy;

import android.content.res.AssetManager;

import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.analyzer.BaseTextmateAnalyzer;
import com.tyron.editor.Editor;

import java.io.InputStream;
import java.io.InputStreamReader;

import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;

public class GroovyAnalyzer extends BaseTextmateAnalyzer {

    private static final String GRAMMAR_NAME = "groovy.tmLanguage";
    private static final String LANGUAGE_PATH = "textmate/groovy/syntaxes/groovy.tmLanguage";
    private static final String CONFIG_PATH = "textmate/groovy/language-configuration.json";

    public GroovyAnalyzer(Editor editor,
                          String grammarName,
                          InputStream open,
                          InputStreamReader config,
                          IRawTheme rawTheme) throws Exception {
        super(editor, grammarName, open, config, rawTheme);
    }

    public static GroovyAnalyzer create(Editor editor) {
        try {
            AssetManager assetManager = ApplicationLoader.applicationContext.getAssets();

            try (InputStreamReader config = new InputStreamReader(assetManager.open(CONFIG_PATH))) {
                return new GroovyAnalyzer(editor, GRAMMAR_NAME, assetManager.open(LANGUAGE_PATH),
                                          config, ((TextMateColorScheme) ((CodeEditorView) editor)
                        .getColorScheme()).getRawTheme());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
