package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.code.lint.DefaultLintClient;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.completion.provider.CompletionEngine;

import org.jetbrains.kotlin.com.intellij.lang.java.lexer.JavaLexer;
import org.jetbrains.kotlin.com.intellij.lexer.Lexer;
import org.jetbrains.kotlin.com.intellij.lexer.LexerPosition;
import org.jetbrains.kotlin.com.intellij.pom.java.LanguageLevel;
import org.jetbrains.kotlin.com.intellij.psi.JavaDocTokenType;
import org.jetbrains.kotlin.com.intellij.psi.JavaTokenType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.ElementType;
import org.jetbrains.kotlin.com.intellij.psi.impl.source.tree.JavaDocElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.psi.tree.TokenSet;
import org.openjdk.javax.tools.Diagnostic;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.stream.Collectors;

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
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public class JavaAnalyzer extends JavaCodeAnalyzer {

    private static final String TAG = JavaAnalyzer.class.getSimpleName();
    private static final Map<IElementType, Integer> ourMap;
    private static final Debouncer ourDebouncer = new Debouncer(Duration.ofMillis(500));

    static {
        ourMap = new HashMap<>();

        fillMap(ourMap, ElementType.KEYWORD_BIT_SET, EditorColorScheme.KEYWORD);
        fillMap(ourMap, ElementType.LITERAL_BIT_SET, EditorColorScheme.KEYWORD);
        fillMap(ourMap, ElementType.OPERATION_BIT_SET, EditorColorScheme.OPERATOR);
        for (IElementType type : JavaDocTokenType.ALL_JAVADOC_TOKENS.getTypes()) {
            ourMap.put(type, EditorColorScheme.COMMENT);
        }

        ourMap.put(JavaTokenType.AT, EditorColorScheme.ANNOTATION);
        ourMap.put(JavaTokenType.RBRACE, EditorColorScheme.OPERATOR);
        ourMap.put(JavaTokenType.LBRACE, EditorColorScheme.OPERATOR);
        ourMap.put(JavaTokenType.SEMICOLON, EditorColorScheme.KEYWORD);
        ourMap.put(JavaTokenType.COMMA, EditorColorScheme.KEYWORD);
        ourMap.put(JavaTokenType.INTEGER_LITERAL, EditorColorScheme.LITERAL);
        ourMap.put(JavaTokenType.LONG_LITERAL, EditorColorScheme.LITERAL);
        ourMap.put(JavaTokenType.FLOAT_LITERAL, EditorColorScheme.LITERAL);
        ourMap.put(JavaTokenType.DOUBLE_LITERAL, EditorColorScheme.LITERAL);
        ourMap.put(JavaTokenType.STRING_LITERAL, EditorColorScheme.LITERAL);
        ourMap.put(JavaTokenType.CHARACTER_LITERAL, EditorColorScheme.LITERAL);
        ourMap.put(JavaTokenType.C_STYLE_COMMENT, EditorColorScheme.COMMENT);
        ourMap.put(JavaTokenType.END_OF_LINE_COMMENT, EditorColorScheme.COMMENT);
        ourMap.put(JavaDocElementType.DOC_COMMENT, EditorColorScheme.COMMENT);
    }

    public static void fillMap(Map<IElementType, Integer> map, TokenSet keys, int color) {
        fillMap(map, color, keys.getTypes());
    }

    protected static void fillMap(Map<IElementType, Integer> map, int color, IElementType... types) {
        for (IElementType type : types) {
            map.put(type, color);
        }
    }

    private final Lexer mLexer = new JavaLexer(LanguageLevel.JDK_1_8);
    private final CodeEditor mEditor;
    private DefaultLintClient mClient;
    private final SharedPreferences mPreferences;

    private final Stack<BlockLine> mBlockStack = new Stack<>();
    private final List<NavigationItem> mNavigationList = new ArrayList<>();

    public JavaAnalyzer(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }

    public void analyze(CharSequence content, TextAnalyzeResult colors, TextAnalyzer.AnalyzeThread.Delegate delegate) {

        IElementType token;
        LineNumberCalculator helper = new LineNumberCalculator(content);

        mBlockStack.clear();
        mNavigationList.clear();
        int maxSwitch = 1;
        int currSwitch = 0;

        Object prevState = colors.getExtra();
        if (prevState instanceof LexerPosition) {
            mLexer.restore(((LexerPosition) prevState));
        } else {
            mLexer.start(content);
        }

        while (delegate.shouldAnalyze()) {
            token = mLexer.getTokenType();
            if (token == null) {
                break;
            }
            Integer color = ourMap.get(token);
            if (color == null) {
                color = EditorColorScheme.TEXT_NORMAL;
                if (token == JavaTokenType.IDENTIFIER) {
                    try {
                        Span span =
                                colors.getSpanMap()
                                        .get(helper.getLine() - 1)
                                        .get(helper.getColumn() - 1);
                        if (span != null) {
                            if (span.colorId == EditorColorScheme.ANNOTATION) {
                                color = EditorColorScheme.ANNOTATION;
                            }
                        }
                    } catch (IndexOutOfBoundsException ignore) {

                    }
                }
            }
            colors.addIfNeeded(helper.getLine(), helper.getColumn(), color);

            if (token == JavaTokenType.RBRACE) {
                colors.addIfNeeded(helper.getLine(), helper.getColumn(), EditorColorScheme.OPERATOR);
                if (!mBlockStack.isEmpty()) {
                    BlockLine block = mBlockStack.pop();
                    block.endLine = helper.getLine();
                    block.endColumn = helper.getColumn();
                    if (block.startLine != block.endLine) {
                        colors.addBlockLine(block);
                    }
                }
            }

            if (token == JavaTokenType.LBRACE) {
                if (mBlockStack.isEmpty()) {
                    if (currSwitch > maxSwitch) {
                        maxSwitch = currSwitch;
                    }
                    currSwitch = 0;
                }
                currSwitch++;
                BlockLine block = colors.obtainNewBlock();
                block.startLine = helper.getLine();
                block.startColumn = helper.getColumn();
                mBlockStack.push(block);
            }
            helper.update(mLexer.getTokenEnd() - mLexer.getTokenStart());
            mLexer.advance();
        }

        if (mBlockStack.isEmpty()) {
            if (currSwitch > maxSwitch) {
                maxSwitch = currSwitch;
            }
        }
        colors.determine(helper.getLine());
        colors.setSuppressSwitch(maxSwitch + 10);
        colors.setNavigation(mNavigationList);
        colors.setExtra(mLexer.getCurrentPosition());

        analyzeInBackground(() -> false, colors);
    }

    /**
     * Does not actually analyze in background yet, waiting for the code editor to support it
     */
    private Unit analyzeInBackground(Function0<Boolean> cancelCallback, TextAnalyzeResult result) {
        List<DiagnosticWrapper> innerDiagnostics = new ArrayList<>();
        // do not compile the file if it not yet closed as it will cause issues when
        // compiling multiple files at the same time
        if (mPreferences.getBoolean("code_editor_error_highlight", true) && !CompletionEngine.isIndexing()) {
            Project project = ProjectManager.getInstance().getCurrentProject();
            if (project != null) {
                JavaCompilerService service = CompletionEngine.getInstance().getCompiler(project);
                if (service.isReady()) {
                    File currentFile = mEditor.getCurrentFile();
                    try {
                        try (CompileTask task = service.compile(
                                Collections.singletonList(new SourceFileObject(currentFile.toPath(),
                                        project.getFileManager().readFile(currentFile),
                                        Instant.now())))) {
                            innerDiagnostics.addAll(task.diagnostics.stream()
                                    .map(DiagnosticWrapper::new)
                                    .collect(Collectors.toList())
                            );
                        }
                    } catch (Throwable e) {
                        Log.e("JavaAnalyzer", "Failed compiling the file", e);
                        service.close();
                    }
                }
            }
        }
        if (!cancelCallback.invoke()) {
            markDiagnostics(innerDiagnostics, result);
        }
        return Unit.INSTANCE;
    }

    private void markDiagnostics(List<DiagnosticWrapper> diagnostics, TextAnalyzeResult colors) {
        mEditor.getText().beginStreamCharGetting(0);
        Indexer indexer = mEditor.getText().getIndexer();

        diagnostics.forEach(it -> {
            try {
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
                colors .markProblemRegion(flag, start.line, start.column, end.line, end.column);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                // Work around for the indexer requiring a sorted positions
                Log.w(TAG, "Unable to mark problem region: diagnostics " + diagnostics, e);
            }
        });
        mEditor.getText().endStreamCharGetting();
    }
}
