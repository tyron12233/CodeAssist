package com.tyron.code.ui.editor.language.xml;

import android.graphics.Color;
import android.os.Handler;
import android.util.Log;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.BuildConfig;
import com.tyron.code.ui.editor.language.AbstractCodeAnalyzer;
import com.tyron.code.ui.editor.language.HighlightUtil;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.ProjectUtils;
import com.tyron.common.util.Debouncer;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.compiler.JavaCompilerService;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.completion.xml.lexer.XMLLexer;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.Token;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Stack;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import kotlin.Unit;

public class XMLAnalyzer extends AbstractCodeAnalyzer<Object> {

    private static final Debouncer sDebouncer = new Debouncer(Duration.ofMillis(900L));

    private final WeakReference<Editor> mEditorReference;
    private final Stack<CodeBlock> mBlockLine = new Stack<>();
    private int mMaxSwitch = 1;
    private int mCurrSwitch = 0;

    public XMLAnalyzer(Editor codeEditor) {
        mEditorReference = new WeakReference<>(codeEditor);
    }

    @Override
    public Lexer getLexer(CharStream input) {
        return new XMLLexer(input);
    }

    @Override
    public void analyzeInBackground(CharSequence contents) {
        Editor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }

        File currentFile = editor.getCurrentFile();
        if (currentFile == null) {
            return;
        }

        List<DiagnosticWrapper> diagnosticWrappers = new ArrayList<>();

        ProgressManager.getInstance().runLater(() -> editor.setAnalyzing(true));

        sDebouncer.cancel();
        sDebouncer.schedule(cancel -> {
            compile(currentFile, contents.toString(), new ILogger() {
                @Override
                public void info(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                @Override
                public void debug(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                @Override
                public void warning(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                @Override
                public void error(DiagnosticWrapper wrapper) {
                    addMaybe(wrapper);
                }

                private void addMaybe(DiagnosticWrapper wrapper) {
                    if (currentFile.equals(wrapper.getSource())) {
                        diagnosticWrappers.add(wrapper);
                    }
                }
            });

            if (!cancel.invoke()) {
                ProgressManager.getInstance().runLater(() -> {
                    editor.setDiagnostics(diagnosticWrappers.stream()
                            .filter(it -> it.getLineNumber() > 0)
                            .collect(Collectors.toList()));
                    ProgressManager.getInstance().runLater(() -> editor.setAnalyzing(false), 300);
                });
            }
            return Unit.INSTANCE;
        });

    }

    @Override
    public void setup() {
        putColor(EditorColorScheme.COMMENT, XMLLexer.COMMENT);
        putColor(EditorColorScheme.HTML_TAG, XMLLexer.Name);
    }

    @Override
    protected void beforeAnalyze() {
        mBlockLine.clear();
        mMaxSwitch = 1;
        mCurrSwitch = 0;
    }

    @Override
    public boolean onNextToken(Token token, Styles styles, MappedSpans.Builder colors) {
        int line = token.getLine() - 1;
        int column = token.getCharPositionInLine();

        Token previous = getPreviousToken();

        switch (token.getType()) {
            case XMLLexer.COMMENT:
                colors.addIfNeeded(line, column, TextStyle.makeStyle(EditorColorScheme.COMMENT));
                return true;
            case XMLLexer.Name:
                if (previous != null && previous.getType() == XMLLexer.SLASH) {
                    colors.addIfNeeded(line, column, TextStyle.makeStyle(EditorColorScheme.HTML_TAG));
                    return true;
                } else if (previous != null && previous.getType() == XMLLexer.OPEN) {
                    colors.addIfNeeded(line, column, TextStyle.makeStyle(EditorColorScheme.HTML_TAG));
                    CodeBlock block = new CodeBlock();
                    block.startLine = previous.getLine() - 1;
                    block.startColumn = previous.getCharPositionInLine();
                    mBlockLine.push(block);
                    return true;
                }
                String attribute = token.getText();
                if (attribute.contains(":")) {
                    colors.addIfNeeded(line, column, EditorColorScheme.ATTRIBUTE_NAME);
                    colors.addIfNeeded(line, column + attribute.indexOf(":"),
                            EditorColorScheme.TEXT_NORMAL);
                    return true;
                }
                colors.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_NAME);
                return true;
            case XMLLexer.EQUALS:
                colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                return true;
            case XMLLexer.STRING:
                String text = token.getText();
                if (text.startsWith("\"#")) {
                    try {
                        int color = Color.parseColor(text.substring(1, text.length() - 1));
                        colors.addIfNeeded(line, column, EditorColorScheme.LITERAL);

                        Span span = Span.obtain(column + 1, EditorColorScheme.LITERAL);
                        span.setUnderlineColor(color);
                        colors.add(line, span);

                        Span middle = Span.obtain(column + text.length() - 1,
                                EditorColorScheme.LITERAL);
                        middle.setUnderlineColor(Color.TRANSPARENT);
                        colors.add(line, middle);

                        Span end = Span.obtain(column + text.length(), TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL));
                        end.setUnderlineColor(Color.TRANSPARENT);
                        colors.add(line, end);
                        break;
                    } catch (Exception ignore) {
                    }
                }
                colors.addIfNeeded(line, column, EditorColorScheme.LITERAL);
                return true;
            case XMLLexer.SLASH_CLOSE:
                colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
                if (!mBlockLine.isEmpty()) {
                    CodeBlock block = mBlockLine.pop();
                    block.endLine = line;
                    block.endColumn = column;
                    if (block.startLine != block.endLine) {
                        if (previous != null && previous.getLine() == token.getLine()) {
                            block.toBottomOfEndLine = true;
                        }
                        styles.addCodeBlock(block);
                    }
                }
                return true;
            case XMLLexer.SLASH:
                colors.addIfNeeded(line, column, TextStyle.makeStyle(EditorColorScheme.HTML_TAG));
                if (previous != null && previous.getType() == XMLLexer.OPEN) {
                    if (!mBlockLine.isEmpty()) {
                        CodeBlock block = mBlockLine.pop();
                        block.endLine = previous.getLine() - 1;
                        block.endColumn = previous.getCharPositionInLine();
                        if (block.startLine != block.endLine) {
                            if (previous.getLine() == token.getLine()) {
                                block.toBottomOfEndLine = true;
                            }
                            styles.addCodeBlock(block);
                        }
                    }
                }
                return true;
            case XMLLexer.OPEN:
            case XMLLexer.CLOSE:
                colors.addIfNeeded(line, column, TextStyle.makeStyle(EditorColorScheme.HTML_TAG));
                return true;
            case XMLLexer.SEA_WS:
            case XMLLexer.S:
                // skip white spaces
                return true;
            default:
                colors.addIfNeeded(line, column, TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL));
                return true;
        }

        return false;
    }

    @Override
    protected void afterAnalyze(CharSequence content, Styles styles, MappedSpans.Builder colors) {
        if (mBlockLine.isEmpty()) {
            if (mCurrSwitch > mMaxSwitch) {
                mMaxSwitch = mCurrSwitch;
            }
        }
        styles.setSuppressSwitch(mMaxSwitch + 10);

        for (DiagnosticWrapper d : mDiagnostics) {
            HighlightUtil.setErrorSpan(styles, d.getStartLine());
        }
    }

    private final Handler handler = new Handler();
    long delay = 1000L;
    long lastTime;

    private void compile(File file, String contents, ILogger logger) {
        boolean isResource = ProjectUtils.isResourceXMLFile(file);

        if (isResource) {
            Project project = ProjectManager.getInstance().getCurrentProject();
            if (project != null) {
                Module module = project.getModule(file);
                if (module instanceof AndroidModule) {
                    try {
                        doGenerate(project, (AndroidModule) module, file, contents, logger);
                    } catch (IOException | CompilationFailedException e) {
                        if (BuildConfig.DEBUG) {
                            Log.e("XMLAnalyzer", "Failed compiling", e);
                        }
                    }
                }
            }
        }
    }

    private void doGenerate(Project project, AndroidModule module, File file,
                            String contents, ILogger logger) throws IOException, CompilationFailedException {
        if (!file.canWrite() || !file.canRead()) {
            return;
        }

        if (!module.getFileManager().isOpened(file)) {
            Log.e("XMLAnalyzer", "File is not yet opened!");
            return;
        }

        Optional<CharSequence> fileContent = module.getFileManager().getFileContent(file);
        if (!fileContent.isPresent()) {
            Log.e("XMLAnalyzer", "No snapshot for file found.");
            return;
        }

        contents = fileContent.get().toString();
        FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
        IncrementalAapt2Task task = new IncrementalAapt2Task(module, logger, false);

        try {
            task.prepare(BuildType.DEBUG);
            task.run();
        } catch (CompilationFailedException e) {
            throw e;
        }

        // work around to refresh R.java file
        File resourceClass = module.getJavaFile(module.getPackageName() + ".R");
        if (resourceClass != null) {
            JavaCompilerProvider provider =
                    CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
            JavaCompilerService service = provider.getCompiler(project, module);

            CompilerContainer container = service.compile(resourceClass.toPath());
            container.run(__ -> {

            });
        }
    }
}
