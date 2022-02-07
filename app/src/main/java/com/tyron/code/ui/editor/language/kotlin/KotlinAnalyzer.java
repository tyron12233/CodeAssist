package com.tyron.code.ui.editor.language.kotlin;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Editor;
import com.tyron.kotlin_completion.CompletionEngine;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenSource;

import java.lang.ref.WeakReference;

import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

public class KotlinAnalyzer extends AbstractCodeAnalyzer<Object> {

    private final WeakReference<Editor> mEditorReference;

    public KotlinAnalyzer(Editor editor) {
        mEditorReference = new WeakReference<>(editor);
    }

    @Override
    public void setup() {
        putColor(EditorColorScheme.KEYWORD, KotlinLexer.OVERRIDE,
                KotlinLexer.PUBLIC, KotlinLexer.PRIVATE, KotlinLexer.PROTECTED,
                KotlinLexer.FUN, KotlinLexer.PACKAGE, KotlinLexer.IMPORT,
                KotlinLexer.CLASS, KotlinLexer.INTERFACE, KotlinLexer.VAL,
                KotlinLexer.VAR, KotlinLexer.ABSTRACT, KotlinLexer.BREAK,
                KotlinLexer.TRY, KotlinLexer.THROW, KotlinLexer.FOR,
                KotlinLexer.WHILE, KotlinLexer.IN, KotlinLexer.INTERNAL,
                KotlinLexer.AS, KotlinLexer.CONTINUE, KotlinLexer.RETURN,
                KotlinLexer.GET, KotlinLexer.SET, KotlinLexer.SETTER,
                KotlinLexer.VARARG, KotlinLexer.FINALLY, KotlinLexer.SEMICOLON,
                KotlinLexer.IF, KotlinLexer.ELSE, KotlinLexer.INIT,
                KotlinLexer.LATEINIT, KotlinLexer.OBJECT, KotlinLexer.REIFIED,
                KotlinLexer.BY, KotlinLexer.CATCH, KotlinLexer.SEALED,
                KotlinLexer.SUSPEND, KotlinLexer.IS, KotlinLexer.OPEN,
                KotlinLexer.DATA, KotlinLexer.CONSTRUCTOR, KotlinLexer.WHEN,
                KotlinLexer.WHERE, KotlinLexer.INFIX);
        putColor(EditorColorScheme.LITERAL, KotlinLexer.BinLiteral,
                KotlinLexer.BooleanLiteral, KotlinLexer.LongLiteral,
                KotlinLexer.IntegerLiteral, KotlinLexer.FloatLiteral,
                KotlinLexer.CharacterLiteral, KotlinLexer.DoubleLiteral,
                KotlinLexer.NullLiteral, KotlinLexer.RealLiteral,
                KotlinLexer.LineString, KotlinLexer.MultiLineString,
                KotlinLexer.StringExpression, KotlinLexer.MultiLineStringQuote,
                KotlinLexer.LineStrText, KotlinLexer.SINGLE_QUOTE, KotlinLexer.QUOTE_OPEN,
                KotlinLexer.QUOTE_CLOSE, KotlinLexer.HexLiteral, KotlinLexer.MultiLineStrText,
                KotlinLexer.TRIPLE_QUOTE_CLOSE, KotlinLexer.TRIPLE_QUOTE_OPEN);
        putColor(EditorColorScheme.COMMENT, KotlinLexer.DelimitedComment,
                KotlinLexer.StrExpr_Comment, KotlinLexer.LineComment,
                KotlinLexer.Inside_Comment);
        putColor(EditorColorScheme.ANNOTATION, KotlinLexer.AT,
                KotlinLexer.LabelReference);
        putColor(EditorColorScheme.OPERATOR, KotlinLexer.OPERATOR,
                KotlinLexer.ADD, KotlinLexer.SUB,
                KotlinLexer.MULT, KotlinLexer.DIV,
                KotlinLexer.ELVIS);
        // todo add block lines
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

                        editor.setAnalyzing(true);

                        CompletionEngine.getInstance((AndroidModule) module)
                                .doLint(editor.getCurrentFile(), content.toString(), diagnostics -> {
                                    editor.setDiagnostics(diagnostics);

                                    ProgressManager.getInstance().runLater(() -> editor.setAnalyzing(false), 300);
                                });
                    }, 1500);
                }
            }
        }
    }

    @Override
    protected void afterAnalyze(CharSequence content, Styles styles, MappedSpans.Builder colors) {
        super.afterAnalyze(content, styles, colors);

        Editor editor = mEditorReference.get();
        if (editor != null) {
            HighlightUtil.markDiagnostics(editor, mDiagnostics, styles);
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
