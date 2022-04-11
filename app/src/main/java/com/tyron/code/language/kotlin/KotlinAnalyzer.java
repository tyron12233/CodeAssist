package com.tyron.code.language.kotlin;

import android.content.res.AssetManager;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.analyzer.DiagnosticTextmateAnalyzer;
import com.tyron.code.language.AbstractCodeAnalyzer;
import com.tyron.code.language.HighlightUtil;
import com.tyron.code.language.java.JavaAnalyzer;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Editor;
import com.tyron.kotlin_completion.CompletionEngine;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ref.WeakReference;

import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.textmate.core.theme.IRawTheme;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class KotlinAnalyzer extends DiagnosticTextmateAnalyzer {

    private static final String GRAMMAR_NAME = "kotlin.tmLanguage";
    private static final String LANGUAGE_PATH = "textmate/kotlin/syntaxes/kotlin.tmLanguage";
    private static final String CONFIG_PATH = "textmate/kotlin/language-configuration.json";

    public static KotlinAnalyzer create(Editor editor) {
        try {
            AssetManager assetManager = ApplicationLoader.applicationContext.getAssets();

            try (InputStreamReader config = new InputStreamReader(assetManager.open(CONFIG_PATH))) {
                return new KotlinAnalyzer(editor, GRAMMAR_NAME, assetManager.open(LANGUAGE_PATH),
                                        config, ((TextMateColorScheme) ((CodeEditorView) editor)
                        .getColorScheme()).getRawTheme());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    public KotlinAnalyzer(Editor editor,
                          String grammarName,
                          InputStream grammarIns,
                          Reader languageConfiguration,
                          IRawTheme theme) throws Exception {
        super(editor, grammarName, grammarIns, languageConfiguration, theme);
    }

    @Override
    public void analyzeInBackground(CharSequence content) {

        // remove this when kotlin analysis is stable
        if (true) {
            return;
        }

        if (mEditor == null) {
            return;
        }
        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        if (currentProject != null) {
            Module module = currentProject.getModule(mEditor.getCurrentFile());
            if (module instanceof AndroidModule) {
                if (ApplicationLoader.getDefaultPreferences()
                        .getBoolean(SharedPreferenceKeys.KOTLIN_HIGHLIGHTING, true)) {
                    ProgressManager.getInstance().runLater(() -> {

                        mEditor.setAnalyzing(true);

                        CompletionEngine.getInstance((AndroidModule) module)
                                .doLint(mEditor.getCurrentFile(), content.toString(), diagnostics -> {
                                    mEditor.setDiagnostics(diagnostics);

                                    ProgressManager.getInstance().runLater(() -> mEditor.setAnalyzing(false), 300);
                                });
                    }, 900);
                }
            }
        }
    }

}
