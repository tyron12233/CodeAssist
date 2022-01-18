package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.BuildConfig;
import com.tyron.code.lint.DefaultLintClient;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.CompileTask;
import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.util.ErrorCodes;
import com.tyron.completion.java.util.TreeUtil;

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

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.data.NavigationItem;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.langs.java.JavaCodeAnalyzer;
import io.github.rosemoe.sora.langs.java.JavaTextTokenizer;
import io.github.rosemoe.sora.langs.java.Tokens;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Indexer;
import io.github.rosemoe.sora.text.LineNumberCalculator;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

public class JavaAnalyzer extends JavaCodeAnalyzer {
    private static final Debouncer sDebouncer = new Debouncer(Duration.ofMillis(700));
    private static final String TAG = JavaAnalyzer.class.getSimpleName();
    /**
     * These are tokens that cannot exist before a valid function identifier
     */
    private static final Tokens[] sKeywordsBeforeFunctionName = new Tokens[]{Tokens.RETURN,
            Tokens.BREAK, Tokens.IF, Tokens.AND, Tokens.OR, Tokens.OREQ, Tokens.OROR,
            Tokens.ANDAND, Tokens.ANDEQ, Tokens.RPAREN, Tokens.LPAREN, Tokens.LBRACE, Tokens.NEW,
            Tokens.DOT, Tokens.SEMICOLON, Tokens.EQ, Tokens.NOTEQ, Tokens.NOT, Tokens.RBRACE,
            Tokens.COMMA, Tokens.PLUS, Tokens.PLUSEQ, Tokens.MINUS, Tokens.MINUSEQ, Tokens.MULT,
            Tokens.MULTEQ, Tokens.DIV, Tokens.DIVEQ};

    private final WeakReference<CodeEditor> mEditorReference;
    private List<DiagnosticWrapper> mDiagnostics;
    private final List<DiagnosticWrapper> mPreviousDiagnostics = new ArrayList<>();
    private final SharedPreferences mPreferences;

    public JavaAnalyzer(CodeEditor editor) {
        mEditorReference = new WeakReference<>(editor);
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
        mDiagnostics = new ArrayList<>();
    }

    @Override
    public void setDiagnostics(List<DiagnosticWrapper> diagnostics) {
        mDiagnostics = diagnostics;
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {
        sDebouncer.schedule(cancel -> {
            doAnalyzeInBackground(cancel, contents);
            return Unit.INSTANCE;
        });
    }

    private JavaCompilerService getCompiler(CodeEditor editor) {
        Project project = ProjectManager.getInstance().getCurrentProject();
        if (project == null) {
            return null;
        }
        Module module = project.getModule(editor.getCurrentFile());
        if (module instanceof JavaModule) {
            JavaCompilerProvider provider =
                    CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
            if (provider != null) {
                return provider.getCompiler(project, (JavaModule) module);
            }
        }
        return null;
    }

    private void doAnalyzeInBackground(Function0<Boolean> cancel, CharSequence contents) {
        CodeEditor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }
        if (cancel.invoke()) {
            return;
        }
        // do not compile the file if it not yet closed as it will cause issues when
        // compiling multiple files at the same time
        if (mPreferences.getBoolean("code_editor_error_highlight", true) && !CompletionEngine.isIndexing()) {
            JavaCompilerService service = getCompiler(editor);
            if (service != null && service.isReady()) {
                try {
                    SourceFileObject sourceFileObject =
                            new SourceFileObject(editor.getCurrentFile().toPath(),
                                    contents.toString(), Instant.now());
                    CompilerContainer container =
                                 service.compile(Collections.singletonList(sourceFileObject));
                    container.run(task -> {
                        if (!cancel.invoke()) {
                            List<DiagnosticWrapper> collect =
                                    task.diagnostics.stream()
                                            .map(d -> modifyDiagnostic(task, d))
                                            .collect(Collectors.toList());
                            editor.setDiagnostics(collect);
                        }
                    });
                } catch (Throwable e) {
                    if (BuildConfig.DEBUG) {
                        Log.e(TAG, "Unable to get diagnostics", e);
                    }
                    service.close();
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

                TreePath modifiedPath = null;
                switch (code) {
                    case ErrorCodes.MISSING_RETURN_STATEMENT:
                        modifiedPath = TreeUtil.findParentOfType(treePath, MethodTree.class);
                        break;
                    case ErrorCodes.DOES_NOT_OVERRIDE_ABSTRACT:
                        modifiedPath = TreeUtil.findParentOfType(treePath, ClassTree.class);
                        break;
                }

                if (modifiedPath != null) {
                    setDiagnosticPosition(wrapped, positions, modifiedPath.getCompilationUnit(), modifiedPath.getLeaf());
                }
            }
        }
        return wrapped;
    }

    private void setDiagnosticPosition(DiagnosticWrapper wrapped,
                                       SourcePositions positions,
                                       CompilationUnitTree root, Tree tree) {
        long startPosition = positions.getStartPosition(root, tree);
        long endPosition = getEndPosition(positions, root, tree);
        wrapped.setStartPosition(startPosition);
        wrapped.setEndPosition(endPosition);
    }

    private long getStartPosition(SourcePositions positions, CompilationUnitTree root, Tree tree) {
        return positions.getStartPosition(root, tree);
    }

    private long getEndPosition(SourcePositions positions, CompilationUnitTree root, Tree tree) {
        if (tree instanceof MethodTree) {
            BlockTree body = ((MethodTree) tree).getBody();
            if (body != null) {
                return positions.getStartPosition(root, body);
            }
        } else if (tree instanceof ClassTree) {
            List<? extends Tree> implementsClause = ((ClassTree) tree).getImplementsClause();
            if (implementsClause != null) {
                Tree last = implementsClause.get(implementsClause.size() - 1);
                return positions.getEndPosition(root, last);
            }
            Tree extendsClause = ((ClassTree) tree).getExtendsClause();
            if (extendsClause != null) {
                return positions.getEndPosition(root, extendsClause);
            }
        }
        return positions.getEndPosition(root, tree);
    }

    public void analyze(CharSequence content, TextAnalyzeResult colors,
                        TextAnalyzer.AnalyzeThread.Delegate delegate) {
        CodeEditor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }
        StringBuilder text = content instanceof StringBuilder ? (StringBuilder) content :
                new StringBuilder(content);
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

        HighlightUtil.markDiagnostics(editor, mDiagnostics, colors);
    }
}