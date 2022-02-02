package com.tyron.code.ui.editor.language.kotlin;

import android.graphics.Color;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.BuildConfig;
import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Editor;
import com.tyron.kotlin_completion.CompletionEngine;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora2.data.BlockLine;
import io.github.rosemoe.sora2.data.Span;
import io.github.rosemoe.sora2.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora2.text.TextAnalyzeResult;
import io.github.rosemoe.sora2.text.TextAnalyzer;
import io.github.rosemoe.sora2.widget.CodeEditor;
import io.github.rosemoe.sora2.widget.EditorColorScheme;

public class KotlinAnalyzer extends AbstractCodeAnalyzer<Object> {

    private final WeakReference<Editor> mEditorReference;
    private final List<DiagnosticWrapper> mDiagnostics;

    public KotlinAnalyzer(Editor editor) {
        mEditorReference = new WeakReference<>(editor);
        mDiagnostics = new ArrayList<>();
    }

    @Override
    public void setup() {
        putColor(EditorColorScheme.KEYWORD, KotlinLexer.OVERRIDE,
                KotlinLexer.FUN, KotlinLexer.PACKAGE, KotlinLexer.IMPORT,
                KotlinLexer.CLASS, KotlinLexer.INTERFACE);

        // todo add block lines
    }

    @Override
    public void setDiagnostics(Editor editor, List<DiagnosticWrapper> diagnostics) {
        mDiagnostics.clear();
        mDiagnostics.addAll(diagnostics);
    }

    @Override
    public Lexer getLexer(CharStream input) {
        return new KotlinLexer(input);
    }

    @Override
    public void analyzeInBackground(CharSequence content) {
        Editor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }
        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        if (currentProject != null) {
            Module module = currentProject.getModule(editor.getCurrentFile());
            if (module instanceof AndroidModule) {
                if (ApplicationLoader.getDefaultPreferences()
                        .getBoolean(SharedPreferenceKeys.KOTLIN_HIGHLIGHTING, true)) {
                    ProgressManager.getInstance().runLater(() -> {
                        CompletionEngine.getInstance((AndroidModule) module)
                                .doLint(editor.getCurrentFile(), content.toString(), editor::setDiagnostics);
                    }, 1500);
                }
            }
        }
    }

    private static class UnknownToken implements Token {

        public static UnknownToken INSTANCE = new UnknownToken();

        @Override
        public String getText() {
            return "";
        }

        @Override
        public int getType() {
            return -1;
        }

        @Override
        public int getLine() {
            return 0;
        }

        @Override
        public int getCharPositionInLine() {
            return 0;
        }

        @Override
        public int getChannel() {
            return 0;
        }

        @Override
        public int getTokenIndex() {
            return 0;
        }

        @Override
        public int getStartIndex() {
            return 0;
        }

        @Override
        public int getStopIndex() {
            return 0;
        }

        @Override
        public TokenSource getTokenSource() {
            return null;
        }

        @Override
        public CharStream getInputStream() {
            return null;
        }
    }
}
