package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.BuildConfig;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.editor.language.kotlin.KotlinLexer;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.CompileTask;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.util.ErrorCodes;
import com.tyron.completion.java.util.TreeUtil;
import com.tyron.completion.progress.ProcessCanceledException;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.openjdk.javax.tools.Diagnostic;
import org.openjdk.javax.tools.JavaFileObject;
import org.openjdk.source.tree.BlockTree;
import org.openjdk.source.tree.ClassTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.MethodTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.Trees;
import org.openjdk.tools.javac.api.ClientCodeWrapper;
import org.openjdk.tools.javac.tree.JCTree;
import org.openjdk.tools.javac.util.JCDiagnostic;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.LineNumberCalculator;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;

public class JavaAnalyzer extends AbstractCodeAnalyzer<Object> {

    private static final Debouncer sDebouncer = new Debouncer(Duration.ofMillis(700));
    private static final String TAG = JavaAnalyzer.class.getSimpleName();
    /**
     * These are tokens that cannot exist before a valid function identifier
     */
    private static final Tokens[] sKeywordsBeforeFunctionName =
            new Tokens[]{Tokens.RETURN, Tokens.BREAK, Tokens.IF, Tokens.AND, Tokens.OR, Tokens.OREQ,
                    Tokens.OROR, Tokens.ANDAND, Tokens.ANDEQ, Tokens.RPAREN, Tokens.LPAREN, Tokens.LBRACE, Tokens.NEW,
                    Tokens.DOT, Tokens.SEMICOLON, Tokens.EQ, Tokens.NOTEQ, Tokens.NOT, Tokens.RBRACE,
                    Tokens.COMMA, Tokens.PLUS, Tokens.PLUSEQ, Tokens.MINUS, Tokens.MINUSEQ, Tokens.MULT,
                    Tokens.MULTEQ, Tokens.DIV, Tokens.DIVEQ};

    private final WeakReference<Editor> mEditorReference;
    private List<DiagnosticWrapper> mDiagnostics;
    private final List<DiagnosticWrapper> mPreviousDiagnostics = new ArrayList<>();
    private final SharedPreferences mPreferences;

    public JavaAnalyzer(Editor editor) {
        mEditorReference = new WeakReference<>(editor);
        mPreferences = ApplicationLoader.getDefaultPreferences();
        mDiagnostics = new ArrayList<>();
    }

    @Override
    public void setDiagnostics(Editor editor, List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;
    }

    @Override
    public Lexer getLexer(CharStream input) {
        return new KotlinLexer(input);
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {
        sDebouncer.cancel();
        sDebouncer.schedule(cancel -> {
            doAnalyzeInBackground(cancel, contents);
            return Unit.INSTANCE;
        });
    }

    private JavaCompilerService getCompiler(Editor editor) {
        Project project = ProjectManager.getInstance()
                .getCurrentProject();
        if (project == null) {
            return null;
        }
        if (project.isCompiling()) {
            return null;
        }
        Module module = project.getModule(editor.getCurrentFile());
        if (module instanceof JavaModule) {
            JavaCompilerProvider provider = CompilerService.getInstance()
                    .getIndex(JavaCompilerProvider.KEY);
            if (provider != null) {
                return provider.getCompiler(project, (JavaModule) module);
            }
        }
        return null;
    }

    private void doAnalyzeInBackground(Function0<Boolean> cancel, CharSequence contents) {
        Editor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }
        if (cancel.invoke()) {
            return;
        }

        // do not compile the file if it not yet closed as it will cause issues when
        // compiling multiple files at the same time
        if (mPreferences.getBoolean(SharedPreferenceKeys.JAVA_ERROR_HIGHLIGHTING, true)) {
            JavaCompilerService service = getCompiler(editor);
            if (service != null) {
                File currentFile = editor.getCurrentFile();
                if (currentFile == null) {
                    return;
                }
                Module module = ProjectManager.getInstance().getCurrentProject().getModule(currentFile);
                if (!module.getFileManager().isOpened(currentFile)) {
                    return;
                }
                try {
                    ProgressManager.getInstance()
                            .runLater(() -> editor.setAnalyzing(true));
                    SourceFileObject sourceFileObject = new SourceFileObject(currentFile
                                                                                     .toPath(),
                                                                             contents.toString(),
                                                                             Instant.now());
                    CompilerContainer container = service.compile(Collections.singletonList(sourceFileObject));
                    container.run(task -> {
                        if (!cancel.invoke()) {
                            List<DiagnosticWrapper> collect = task.diagnostics.stream()
                                    .map(d -> modifyDiagnostic(task, d))
                                    .peek(it -> ProgressManager.checkCanceled())
                                    .collect(Collectors.toList());
                            editor.setDiagnostics(collect);

                            ProgressManager.getInstance()
                                    .runLater(() -> editor.setAnalyzing(false), 300);
                        }
                    });
                } catch (Throwable e) {
                    if (e instanceof ProcessCanceledException) {
                        throw e;
                    }
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Unable to get diagnostics", e);
                    }
                    service.destroy();
                    ProgressManager.getInstance()
                            .runLater(() -> editor.setAnalyzing(false));
                }
            }
        }
    }

    private DiagnosticWrapper modifyDiagnostic(CompileTask task, Diagnostic<? extends JavaFileObject> diagnostic) {
        DiagnosticWrapper wrapped = new DiagnosticWrapper(diagnostic);

        if (diagnostic instanceof ClientCodeWrapper.DiagnosticSourceUnwrapper) {
            Trees trees = Trees.instance(task.task);
            SourcePositions positions = trees.getSourcePositions();

            JCDiagnostic jcDiagnostic = ((ClientCodeWrapper.DiagnosticSourceUnwrapper) diagnostic).d;
            JCDiagnostic.DiagnosticPosition diagnosticPosition =
                    jcDiagnostic.getDiagnosticPosition();
            JCTree tree = diagnosticPosition.getTree();

            if (tree != null) {
                TreePath treePath = trees.getPath(task.root(), tree);
                String code = jcDiagnostic.getCode();

                long start = diagnostic.getStartPosition();
                long end = diagnostic.getEndPosition();
                switch (code) {
                    case ErrorCodes.MISSING_RETURN_STATEMENT:
                        TreePath block = TreeUtil.findParentOfType(treePath, BlockTree.class);
                        if (block != null) {
                            // show error span only at the end parenthesis
                            end = positions.getEndPosition(task.root(), block.getLeaf()) + 1;
                            start = end - 2;
                        }
                        break;
                    case ErrorCodes.DEPRECATED:
                        if (treePath.getLeaf()
                                    .getKind() == Tree.Kind.METHOD) {
                            MethodTree methodTree = (MethodTree) treePath.getLeaf();
                            if (methodTree.getBody() != null) {
                                start = positions.getStartPosition(task.root(), methodTree);
                                end = positions.getStartPosition(task.root(), methodTree.getBody());
                            }
                        }
                        break;
                }

                wrapped.setStartPosition(start);
                wrapped.setEndPosition(end);
            }
        }
        return wrapped;
    }

    @Override
    protected Styles analyze(StringBuilder text, Delegate<Object> delegate) {
        Styles styles = new Styles();
        MappedSpans.Builder colors = new MappedSpans.Builder();

        Editor editor = mEditorReference.get();
        if (editor == null) {
            return styles;
        }

        boolean loaded = getExtraArguments().getBoolean("loaded", false);
        if (!loaded) {
            return styles;
        }

        JavaTextTokenizer tokenizer = new JavaTextTokenizer(text);
        tokenizer.setCalculateLineColumn(false);
        Tokens token, previous = Tokens.UNKNOWN;
        int line = 0, column = 0;
        LineNumberCalculator helper = new LineNumberCalculator(text);

        Stack<CodeBlock> stack = new Stack<>();
        int maxSwitch = 1, currSwitch = 0;

        boolean first = true;

        while (!delegate.isCancelled()) {
            ProgressManager.checkCanceled();
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
                    CodeBlock block = styles.obtainNewBlock();
                    block.startLine = line;
                    block.startColumn = column;
                    stack.push(block);
                    break;
                }
                case RBRACE: {
                    colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                    if (!stack.isEmpty()) {
                        CodeBlock block = stack.pop();
                        block.endLine = line;
                        block.endColumn = column;
                        if (block.startLine != block.endLine) {
                            styles.addCodeBlock(block);
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
        styles.setSuppressSwitch(maxSwitch + 10);
        styles.spans = colors.build();

        if (mShouldAnalyzeInBg) {
            ProgressManager.checkCanceled();
            analyzeInBackground(text);
        }
        HighlightUtil.markDiagnostics(editor, mDiagnostics, styles);
        return styles;
    }

    /**
     * CodeAssist changed: do not interrupt the thread when destroying this analyzer, as it will
     * also destroy the cache.
     */
    @Override
    public void destroy() {
        setToNull("ref");
        setToNull("extraArguments");
        setToNull("data");

        Field thread = ReflectionUtil.getDeclaredField(SimpleAnalyzeManager.class, "thread");
        if (thread != null) {
            thread.setAccessible(true);
            try {
                Thread o = (Thread) thread.get(this);
                ProgressManager.getInstance().cancelThread(o);

                thread.set(this, null);
            } catch (Throwable e) {
                throw new Error(e);
            }
        }
    }

    private void setToNull(String fieldName) {
        Field declaredField =
                ReflectionUtil.getDeclaredField(SimpleAnalyzeManager.class, fieldName);
        if (declaredField != null) {
            declaredField.setAccessible(true);
            try {
                declaredField.set(this, null);
            } catch (IllegalAccessException e) {
                throw new Error(e);
            }
        }
    }
}