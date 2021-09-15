package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.code.lint.DefaultLintClient;
import com.tyron.code.lint.LintIssue;
import com.tyron.completion.CompileTask;
import com.tyron.completion.JavaCompilerService;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.completion.provider.CompletionEngine;
import com.tyron.builder.parser.FileManager;
import com.tyron.lint.api.DefaultPosition;
import com.tyron.lint.api.Severity;

import io.github.rosemoe.editor.struct.Span;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.text.TextAnalyzer;
import io.github.rosemoe.editor.langs.java.JavaCodeAnalyzer;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.EditorColorScheme;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.editor.text.LineNumberCalculator;
import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.struct.BlockLine;
import io.github.rosemoe.editor.struct.NavigationItem;

import java.util.Locale;
import java.util.Stack;

import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;

public class JavaAnalyzer extends JavaCodeAnalyzer {

    private final List<Diagnostic<? extends JavaFileObject>> diagnostics = new ArrayList<>();
    private final CodeEditor mEditor;
    private final DefaultLintClient mClient;

    private final SharedPreferences mPreferences;

    public JavaAnalyzer(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
        mClient = new DefaultLintClient(FileManager.getInstance().getCurrentProject());
    }
    @Override
    public void analyze(CharSequence content, TextAnalyzeResult colors, TextAnalyzer.AnalyzeThread.Delegate delegate) {
        mClient.scan(mEditor.getCurrentFile());

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

        JavaCompilerService service = CompletionEngine.getInstance().getCompiler();

        // do not compile the file if it not yet closed as it will cause issues when
        // compiling multiple files at the same time
        if (mPreferences.getBoolean("code_editor_error_highlight", true) && service.isReady()) {
            FileManager.writeFile(mEditor.getCurrentFile(), mEditor.getText().toString());
            try (CompileTask task = service.compile(
                    List.of(new SourceFileObject(mEditor.getCurrentFile().toPath(), content.toString(), Instant.now())))) {
                diagnostics.clear();
                diagnostics.addAll(task.diagnostics);
            }
        }

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

            Span currentSpan = null;
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
                        currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.ANNOTATION);
                        break;
                    }
                    //Here we have to get next token to see if it is function
                    //We can only get the next token in stream.
                    //If more tokens required, we have to use a stack in tokenizer
                    Tokens next = tokenizer.directNextToken();
                    //The next is LPAREN,so this is function name or type name
                    if (next == Tokens.LPAREN) {
                        currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.FUNCTION_NAME);
                        tokenizer.pushBack(tokenizer.getTokenLength());
                        break;
                    }
                    //Push back the next token
                    tokenizer.pushBack(tokenizer.getTokenLength());
                    //This is a class definition

                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.TEXT_NORMAL);
                    break;
                case CHARACTER_LITERAL:
                case STRING:
                case FLOATING_POINT_LITERAL:
                case INTEGER_LITERAL:
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.LITERAL);
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
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.KEYWORD);
                    break;
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
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.KEYWORD);
                    break;
                case LBRACE: {
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
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
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
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
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.COMMENT);
                    break;
                default:
                    if (token == Tokens.LBRACK || (token == Tokens.RBRACK && previous == Tokens.LBRACK)) {
                        currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                        break;
                    }
                    currentSpan = colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
            }

            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                if (diagnostic.getStartPosition() <= thisIndex && thisIndex <= diagnostic.getEndPosition()) {
                    if (currentSpan == null) {
                        currentSpan = Span.obtain(column, EditorColorScheme.TEXT_NORMAL);
                        colors.addIfNeeded(line, currentSpan);
                    }
                    currentSpan.setUnderlineColor(diagnostic.getKind() == Diagnostic.Kind.ERROR ? 0xffFF0000 : 0xFFffff00);
                }
            }

            for (LintIssue issue : mClient.getReportedIssues()) {
                if (issue.getLocation().getStart() == null || issue.getLocation().getEnd() == null) {
                    continue;
                }
                DefaultPosition startPos = (DefaultPosition) issue.getLocation().getStart();
                DefaultPosition endPos = (DefaultPosition) issue.getLocation().getEnd();
                if (currentSpan == null) {
                    currentSpan = Span.obtain(column, EditorColorScheme.TEXT_NORMAL);
                    colors.addIfNeeded(line, currentSpan);
                }
                if (startPos.getOffset() <= thisIndex && thisIndex < endPos.getOffset()) {
                    currentSpan.setUnderlineColor(issue.getSeverity() == Severity.ERROR ? 0xffFF0000 : 0xFFFFFF00);
                }
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
    }

    public List<Diagnostic<? extends JavaFileObject>> getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(List<Diagnostic<? extends JavaFileObject>> diags) {
        diagnostics.clear();
        diagnostics.addAll(diags);
    }
}
