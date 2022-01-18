package com.tyron.code.ui.editor.language.xml;

import android.graphics.Color;
import android.os.Handler;
import android.util.Log;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.incremental.resource.IncrementalAapt2Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.BuildConfig;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.xml.lexer.XMLLexer;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CodePointCharStream;
import org.antlr.v4.runtime.Token;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Stack;
import java.util.concurrent.Executors;

import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.text.TextAnalyzer;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class XMLAnalyzer implements CodeAnalyzer {

    private final WeakReference<CodeEditor> mEditorReference;

    public XMLAnalyzer(CodeEditor codeEditor) {
        mEditorReference = new WeakReference<>(codeEditor);
    }

    @Override
    public void analyze(CharSequence content, TextAnalyzeResult colors,
                        TextAnalyzer.AnalyzeThread.Delegate delegate) {
        CodeEditor editor = mEditorReference.get();
        if (editor == null) {
            return;
        }
        try {
            CodePointCharStream stream =
                    CharStreams.fromReader(new StringReader(content.toString()));
            XMLLexer lexer = new XMLLexer(stream);
            Token token, previous = null;
            Stack<BlockLine> stack = new Stack<>();

            int lastLine = 1;
            int line, column;

            while (delegate.shouldAnalyze()) {
                token = lexer.nextToken();
                if (token == null) break;
                if (token.getType() == XMLLexer.EOF) {
                    lastLine = token.getLine() - 1;
                    break;
                }
                line = token.getLine() - 1;
                column = token.getCharPositionInLine();
                lastLine = line;

                switch (token.getType()) {
                    case XMLLexer.COMMENT:
                        colors.addIfNeeded(line, column, EditorColorScheme.COMMENT);
                        break;
                    case XMLLexer.Name:
                        if (previous != null && previous.getType() == XMLLexer.SLASH) {
                            colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
                            break;
                        } else if (previous != null && previous.getType() == XMLLexer.OPEN) {
                            colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
                            BlockLine block = new BlockLine();
                            block.startLine = previous.getLine() - 1;
                            block.startColumn = previous.getCharPositionInLine();
                            stack.push(block);
                            break;
                        }
                        String attribute = token.getText();
                        if (attribute.contains(":")) {
                            colors.addIfNeeded(line, column, EditorColorScheme.ATTRIBUTE_NAME);
                            colors.addIfNeeded(line, column + attribute.indexOf(":"),
                                    EditorColorScheme.TEXT_NORMAL);
                            break;
                        }
                        colors.addIfNeeded(line, column, EditorColorScheme.IDENTIFIER_NAME);
                        break;
                    case XMLLexer.EQUALS:
                        Span span1 = colors.addIfNeeded(line, column, EditorColorScheme.OPERATOR);
                        span1.setUnderlineColor(Color.TRANSPARENT);
                        break;
                    case XMLLexer.STRING:
                        String text = token.getText();
                        if (text.startsWith("\"#")) {
                            try {
                                int color = Color.parseColor(text.substring(1, text.length() - 1));
                                colors.addIfNeeded(line, Span.obtain(column,
                                        EditorColorScheme.LITERAL));
                                colors.add(line, Span.obtain(column + 1,
                                        EditorColorScheme.LITERAL)).setUnderlineColor(color);
                                colors.add(line, Span.obtain(column + text.length() - 1,
                                        EditorColorScheme.LITERAL)).setUnderlineColor(Color.TRANSPARENT);
                                colors.addIfNeeded(line, column + text.length(),
                                        EditorColorScheme.TEXT_NORMAL).setUnderlineColor(Color.TRANSPARENT);
                                break;
                            } catch (Exception ignore) {
                            }
                        }
                        colors.addIfNeeded(line, column, EditorColorScheme.LITERAL);
                        break;
                    case XMLLexer.SLASH_CLOSE:
                        colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
                        if (!stack.isEmpty()) {
                            BlockLine block = stack.pop();
                            block.endLine = line;
                            block.endColumn = column;
                            if (block.startLine != block.endLine) {
                                if (previous.getLine() == token.getLine()) {
                                    block.toBottomOfEndLine = true;
                                }
                                colors.addBlockLine(block);
                            }
                        }
                        break;
                    case XMLLexer.SLASH:
                        colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
                        if (previous != null && previous.getType() == XMLLexer.OPEN) {
                            if (!stack.isEmpty()) {
                                BlockLine block = stack.pop();
                                block.endLine = previous.getLine() - 1;
                                block.endColumn = previous.getCharPositionInLine();
                                if (block.startLine != block.endLine) {
                                    if (previous.getLine() == token.getLine()) {
                                        block.toBottomOfEndLine = true;
                                    }
                                    colors.addBlockLine(block);
                                }
                            }
                        }
                        break;
                    case XMLLexer.OPEN:
                    case XMLLexer.CLOSE:
                        colors.addIfNeeded(line, column, EditorColorScheme.HTML_TAG);
                        break;
                    default:
                        Span s = Span.obtain(column, EditorColorScheme.TEXT_NORMAL);
                        colors.addIfNeeded(line, s);
                        s.setUnderlineColor(Color.TRANSPARENT);
                }

                if (token.getType() != XMLLexer.SEA_WS && token.getType() != XMLLexer.S) {
                    previous = token;
                }
            }

            new BasicXmlPullAnalyzer().analyze(content, colors, delegate);

            colors.determine(lastLine);
            compile(editor.getCurrentFile(), content.toString(), colors);
        } catch (Throwable ignore) {

        }
    }

    private final Handler handler = new Handler();
    long delay = 1000L;
    long lastTime;

    private void compile(File file, String contents, TextAnalyzeResult colors) {
        handler.removeCallbacks(runnable);
        lastTime = System.currentTimeMillis();
        runnable.setContents(contents);
        runnable.setFile(file);
        runnable.setColors(colors);
        handler.postDelayed(runnable, delay);
    }

    CompileRunnable runnable = new CompileRunnable();

    private class CompileRunnable implements Runnable {

        private TextAnalyzeResult colors;
        private File file;
        private String contents;

        public CompileRunnable() {
        }

        public void setColors(TextAnalyzeResult colors) {
            this.colors = colors;
        }

        public void setFile(File file) {
            this.file = file;
        }

        private void setContents(String contents) {
            this.contents = contents;
        }

        @Override
        public void run() {
            if (colors == null) {
                return;
            }
            if (System.currentTimeMillis() < (lastTime - 500)) {
                return;
            }

            Executors.newSingleThreadExecutor().execute(() -> {
                if (file == null || colors == null || contents == null) {
                    return;
                }
                boolean isResource = ProjectUtils.isResourceXMLFile(file);

                if (isResource) {
                    Project project = ProjectManager.getInstance().getCurrentProject();
                    if (project != null) {
                        Module module = project.getModule(file);
                        if (module instanceof AndroidModule) {
                            try {
                                doGenerate(project, (AndroidModule) module, file, contents);
                            } catch (IOException | CompilationFailedException e) {
                                if (BuildConfig.DEBUG) {
                                    Log.e("XMLAnalyzer", "Failed compiling", e);
                                }
                            }
                        }
                    }
                }
            });
        }

        private void doGenerate(Project project, AndroidModule module, File file,
                                String contents) throws IOException, CompilationFailedException {
            if (!file.canWrite() || !file.canRead()) {
                return;
            }

            FileUtils.writeStringToFile(file, contents, StandardCharsets.UTF_8);
            IncrementalAapt2Task task = new IncrementalAapt2Task(module, ILogger.EMPTY, false);
            task.prepare(BuildType.DEBUG);
            task.run();


            // work around to refresh R.java file
            File resourceClass = module.getJavaFile(module.getPackageName() + ".R");
            if (resourceClass != null) {
                JavaCompilerProvider provider =
                        CompilerService.getInstance().getIndex(JavaCompilerProvider.KEY);
                JavaCompilerService service = provider.getCompiler(project, module);

                if (service.isReady()) {
                    CompilerContainer container = service.compile(resourceClass.toPath());
                    container.run(__ -> {

                    });
                }
            }
        }
    }
}
