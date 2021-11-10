package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.code.lint.DefaultLintClient;
import com.tyron.code.lint.LintIssue;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.model.Position;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.lint.api.Severity;

import org.openjdk.javax.tools.Diagnostic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Stack;
import java.util.stream.Collectors;

import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.data.NavigationItem;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.langs.java.JavaCodeAnalyzer;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Indexer;
import io.github.rosemoe.sora.text.LineNumberCalculator;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class JavaAnalyzer extends JavaCodeAnalyzer {

    private static final String TAG = JavaAnalyzer.class.getSimpleName();
    private static final Tokens[] sKeywordsBeforeFunctionName = new Tokens[]{Tokens.RETURN, Tokens.BREAK, Tokens.IF, Tokens.AND, Tokens.OR, Tokens.OREQ,
            Tokens.OROR, Tokens.ANDAND, Tokens.ANDEQ, Tokens.RPAREN, Tokens.LBRACE, Tokens.NEW, Tokens.DOT, Tokens.SEMICOLON, Tokens.EQ, Tokens.NOTEQ, Tokens.NOT,
            Tokens.RBRACE};

    private final List<DiagnosticWrapper> diagnostics = new ArrayList<>();
    private final List<LintIssue> mLintDiagnostics = new ArrayList<>();

    private final CodeEditor mEditor;
    private DefaultLintClient mClient;

    private final SharedPreferences mPreferences;

    public JavaAnalyzer(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }

    public void analyze(CharSequence content, TextAnalyzeResult colors, TextAnalyzer.AnalyzeThread.Delegate delegate) {

        Instant startTime = Instant.now();

        diagnostics.clear();

        StringBuilder text = content instanceof StringBuilder ? (StringBuilder) content : new StringBuilder(content);
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(text);
        tokenizer.setCalculateLineColumn(false);
        Tokens token, previous = Tokens.UNKNOWN;
        int line = 0, column = 0;
        LineNumberCalculator helper = new LineNumberCalculator(text);

        Stack<BlockLine> stack = new Stack<>();
        List<NavigationItem> labels = new ArrayList<>();
        int maxSwitch = 1, currSwitch = 0;

        boolean first = true;

        while (delegate.shouldAnalyze()) {
            try {
                // directNextToken() does not skip any token
                token = tokenizer.directNextToken();
            } catch (RuntimeException e) {
                //When a spelling input is in process, this will happen because of format mismatch
                token = Tokens.CHARACTER_LITERAL;
            }
            if (token == Tokens.EOF) {
                break;
            }
            // Backup values because looking ahead in function name match will change them
            int thisIndex = tokenizer.getIndex();
            int thisLength = tokenizer.getTokenLength();

            switch (token) {
                case WHITESPACE:
                case NEWLINE:
                    if (first) {
                        colors.addNormalIfNull();
                    }
                    break;
                case IDENTIFIER:
                    //Add a identifier to auto complete

                    //The previous so this will be the annotation's type name
                    if (previous == Tokens.AT) {
                        colors.addIfNeeded(line, column, EditorColorScheme.ANNOTATION);
                        break;
                    }
                    //Here we have to get next token to see if it is function
                    //We can only get the next token in stream.
                    //If more tokens required, we have to use a stack in tokenizer
                    Tokens next = tokenizer.directNextToken();
                    //The next is LPAREN,so this is function name or type name
                    if (next == Tokens.LPAREN) {
                        boolean found = false;
                        for (Tokens before : sKeywordsBeforeFunctionName) {
                            if (before == previous) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            colors.addIfNeeded(line, column, EditorColorScheme.FUNCTION_NAME);
                            tokenizer.pushBack(tokenizer.getTokenLength());
                            break;
                        }
                    }
                    //Push back the next token
                    tokenizer.pushBack(tokenizer.getTokenLength());
                    //This is a class definition

                    colors.addIfNeeded(line, column, EditorColorScheme.TEXT_NORMAL);
                    break;
                case CHARACTER_LITERAL:
                case STRING:
                case FLOATING_POINT_LITERAL:
                case INTEGER_LITERAL:
                    colors.addIfNeeded(line, column, EditorColorScheme.LITERAL);
                    break;
                case INT:
                case LONG:
                case BOOLEAN:
                case BYTE:
                case CHAR:
                case FLOAT:
                case DOUBLE:
                case SHORT:
                case VOID:
                case ABSTRACT:
                case ASSERT:
                case CLASS:
                case DO:
                case FINAL:
                case FOR:
                case IF:
                case NEW:
                case PUBLIC:
                case PRIVATE:
                case PROTECTED:
                case PACKAGE:
                case RETURN:
                case STATIC:
                case SUPER:
                case SWITCH:
                case ELSE:
                case VOLATILE:
                case SYNCHRONIZED:
                case STRICTFP:
                case GOTO:
                case CONTINUE:
                case BREAK:
                case TRANSIENT:
                case TRY:
                case CATCH:
                case FINALLY:
                case WHILE:
                case CASE:
                case DEFAULT:
                case CONST:
                case ENUM:
                case EXTENDS:
                case IMPLEMENTS:
                case IMPORT:
                case INSTANCEOF:
                case INTERFACE:
                case NATIVE:
                case THIS:
                case THROW:
                case THROWS:
                case TRUE:
                case FALSE:
                case NULL:
                case SEMICOLON:
                    colors.addIfNeeded(line, column, EditorColorScheme.KEYWORD);
                    break;
                case LBRACE: {
                    colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                    if (stack.isEmpty()) {
                        if (currSwitch > maxSwitch) {
                            maxSwitch = currSwitch;
                        }
                        currSwitch = 0;
                    }
                    currSwitch++;
                    BlockLine block = colors.obtainNewBlock();
                    block.startLine = line;
                    block.startColumn = column;
                    stack.push(block);
                    break;
                }
                case RBRACE: {
                    colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                    if (!stack.isEmpty()) {
                        BlockLine block = stack.pop();
                        block.endLine = line;
                        block.endColumn = column;
                        if (block.startLine != block.endLine) {
                            colors.addBlockLine(block);
                        }
                    }
                    break;
                }
                case LINE_COMMENT:
                case LONG_COMMENT:
                    colors.addIfNeeded(line, column, EditorColorScheme.COMMENT);
                    break;
                default:
                    if (token == Tokens.LBRACK || (token == Tokens.RBRACK && previous == Tokens.LBRACK)) {
                        colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                        break;
                    }
                    colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
            }


            first = false;
            helper.update(thisLength);
            line = helper.getLine();
            column = helper.getColumn();
            if (token != Tokens.WHITESPACE && token != Tokens.NEWLINE) {
                previous = token;
            }
        }
        if (stack.isEmpty()) {
            if (currSwitch > maxSwitch) {
                maxSwitch = currSwitch;
            }
        }
        colors.determine(line);
        colors.setSuppressSwitch(maxSwitch + 10);
        colors.setNavigation(labels);

        // Work around to CodeEditor's bug
        // this prevents the analyzer to stop working while the user types
        try {
            mLintDiagnostics.forEach(it -> {
                int flag = it.getSeverity() == Severity.ERROR ? Span.FLAG_ERROR : Span.FLAG_WARNING;
                Position start = it.getLocation().getStart();
                Position end = it.getLocation().getEnd();
                colors.markProblemRegion(flag, Objects.requireNonNull(start).line, start.column, Objects.requireNonNull(end).line, end.column);
            });
        } catch (IndexOutOfBoundsException e) {
            Log.w(TAG, "Unable to mark problem region", e);
        }

        mAnalyzeRunnable.setAnalyzeResult(colors);
        mAnalyzeRunnable.run();

        Log.d(TAG, "Analysis took " + Duration.between(startTime, Instant.now()).toMillis() + " ms");
    }


    public List<LintIssue> getDiagnostics() {
        return mLintDiagnostics;
    }

    private final AnalyzeRunnable mAnalyzeRunnable = new AnalyzeRunnable();

    private class AnalyzeRunnable implements Runnable {

        private TextAnalyzeResult colors;

        public AnalyzeRunnable() {

        }

        public void setAnalyzeResult(TextAnalyzeResult colors) {
            this.colors = colors;
        }

        @Override
        public void run() {
            Log.d(getClass().getName(), "Analyzing in background...");

            List<DiagnosticWrapper> diagnostics = new ArrayList<>();

            // do not compile the file if it not yet closed as it will cause issues when
            // compiling multiple files at the same time
            if (mPreferences.getBoolean("code_editor_error_highlight", true) && !CompletionEngine.isIndexing()) {
                Project project = ProjectManager.getInstance().getCurrentProject();
                if (project != null) {
                    JavaCompilerService service = CompletionEngine.getInstance().getCompiler(project);
                    if (service.isReady()) {
                        try {
                            try (CompileTask task = service.compile(
                                    Collections.singletonList(new SourceFileObject(mEditor.getCurrentFile().toPath(), mEditor.getText().toString(), Instant.now())))) {
                                diagnostics.addAll(task.diagnostics.stream().map(DiagnosticWrapper::new).collect(Collectors.toList()));
                            }
                        } catch (RuntimeException e) {
                            Log.e("JavaAnalyzer", "Failed compiling the file", e);
                            service.close();
                        }
                    }
                }
            }

            markDiagnostics(diagnostics, colors);
        }
    }

    private void markDiagnostics(List<DiagnosticWrapper> diagnostics, TextAnalyzeResult colors) {
        diagnostics.forEach(it -> {
            try {
                Indexer indexer = mEditor.getText().getIndexer();

                if (it.getStartPosition() == -1) {
                    it.setStartPosition(it.getPosition());
                }
                if (it.getEndPosition() == -1) {
                    it.setEndPosition(it.getPosition());
                }

                CharPosition start = indexer.getCharPosition((int) it.getStartPosition());
                CharPosition end = indexer.getCharPosition((int) it.getEndPosition());

                // the editor does not support marking underline spans for the same start and end index
                // to work around this, we just subtract one to the start index
                if (start.line == end.line && end.column == start.column) {
                    start.column--;
                }

                int flag = it.getKind() == Diagnostic.Kind.ERROR ? Span.FLAG_ERROR : Span.FLAG_WARNING;
                colors.markProblemRegion(flag, start.line, start.column, end.line, end.column);
            } catch (IllegalArgumentException |  IndexOutOfBoundsException e) {
                // Work around for the indexer requiring a sorted positions
                Log.w(TAG, "Unable to mark problem region: diagnostics " + diagnostics, e);
            }
        });
    }
}
