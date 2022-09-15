package com.tyron.code.language.groovy;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.code.language.CompletionItemWrapper;
import com.tyron.code.language.LanguageManager;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.util.Ranges;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Set;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class GroovyLanguage implements Language {

    private static final String GRAMMAR_NAME = "groovy.tmLanguage";
    private static final String LANGUAGE_PATH = "textmate/groovy/syntaxes/groovy.tmLanguage";
    private static final String CONFIG_PATH = "textmate/groovy/language-configuration.json";

    private final Editor editor;
    private final TextMateLanguage delegate;


    public GroovyLanguage(Editor editor) {
        this.editor = editor;
        delegate = LanguageManager.createTextMateLanguage(GRAMMAR_NAME, LANGUAGE_PATH, CONFIG_PATH, editor);
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return delegate.getAnalyzeManager();
    }

    @Override
    public int getInterruptionLevel() {
        return delegate.getInterruptionLevel();
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content,
                                    @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) throws CompletionCancelledException {
        String prefix = CompletionHelper.computePrefix(content, position, MyCharacter::isJavaIdentifierPart);

        if (true) {
            return;
        }
        try {
            File currentFile = editor.getCurrentFile();
            URI uri = currentFile.toURI();
            Position pos = new Position(position.getLine(), position.getColumn());

            CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
            GroovyClassLoader classLoader = new GroovyClassLoader(getClass().getClassLoader(), compilerConfiguration);
            CompilationUnit unit = new CompilationUnit(compilerConfiguration, null, classLoader);
            SourceUnit source = new SourceUnit(
                    currentFile.getName(),
                    content.getReference().toString(),
                    unit.getConfiguration(),
                    classLoader,
                    unit.getErrorCollector()
            );
            unit.addSource(source);

            unit.compile(Phases.CANONICALIZATION);

            CompletionVisitor completionVisitor = new CompletionVisitor();
            completionVisitor.visitCompilationUnit(uri,
                    unit
            );

            Set<MethodCallExpression> methodCalls = completionVisitor.getMethodCalls(uri);
            MethodCallExpression containingCall = null;
            for (MethodCallExpression call : methodCalls) {
                Expression expression = call.getArguments();
                Range range = GroovyUtils.toRange(expression);
                if (Ranges.containsPosition(range, pos)
                    && (containingCall == null || Ranges.containsRange(GroovyUtils.toRange(containingCall.getArguments()),
                        GroovyUtils.toRange(call.getArguments())))) {
                    // find inner containing call
                    containingCall = call;
                }
            }

            if (containingCall != null) {
                CompletionHandler completionHandler = new CompletionHandler();
                List<CompletionItem> completionItems = completionHandler.getCompletionItems(
                        containingCall,
                        currentFile.getName(),
                        editor.getProject().getRootFile().getAbsolutePath(),
                        completionVisitor.getPlugins(uri));
                CompletionList.Builder builder = new CompletionList.Builder(prefix);
                completionItems.forEach(builder::addItem);
                builder.build().getItems().forEach(it -> {
                    publisher.addItem(new CompletionItemWrapper(it));
                });
            }
        } catch (Exception e) {
            System.out.println(e);
        }
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        return delegate.getIndentAdvance(content, line, column);
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @NonNull
    @Override
    public Formatter getFormatter() {
        return delegate.getFormatter();
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return delegate.getSymbolPairs();
    }

    @Nullable
    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[]{
                new BraceHandler(),
                new JavaDocHandler(),
                new TwoIndentHandler()
        };
    }

    @Override
    public void destroy() {
        delegate.destroy();
    }

    public int getIndentAdvance(String p1) {
        GroovyLexer groovyLexer = new GroovyLexer(CharStreams.fromString(p1));
        Token token;
        int advance = 0;
        while ((token = groovyLexer.nextToken()).getType() != Token.EOF) {
            if (token.getType() == GroovyLexer.LBRACK) {
                advance++;
            }
        }
        return (advance * delegate.getTabSize());
    }

    class TwoIndentHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            Log.d("BeforeText", beforeText);
            if (beforeText.replace("\r", "").trim().startsWith(".")) {
                return false;
            }
            return beforeText.endsWith(")") && !afterText.startsWith(";");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText) + (4 * 2);
            String text;
            StringBuilder sb = new StringBuilder().append('\n')
                    .append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = 0;
            return new NewlineHandleResult(sb, shiftLeft);
        }


    }

    class BraceHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.endsWith("{") && afterText.startsWith("}");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceBefore = getIndentAdvance(beforeText);
            int advanceAfter = getIndentAdvance(afterText);
            String text;
            StringBuilder sb = new StringBuilder("\n").append(
                            TextUtils.createIndent(count + advanceBefore, tabSize, useTab())).append('\n')
                    .append(text = TextUtils.createIndent(count + advanceAfter, tabSize, useTab()));
            int shiftLeft = text.length() + 1;
            return new NewlineHandleResult(sb, shiftLeft);
        }
    }

    class JavaDocStartHandler implements NewlineHandler {

        private boolean shouldCreateEnd = true;

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim().startsWith("/**");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText);
            String text = "";
            StringBuilder sb = new StringBuilder().append("\n")
                    .append(TextUtils.createIndent(count + advanceAfter, tabSize, useTab()))
                    .append(" * ");
            if (shouldCreateEnd) {
                sb.append("\n").append(
                                text = TextUtils.createIndent(count + advanceAfter, tabSize,
                                        useTab()))
                        .append(" */");
            }
            return new NewlineHandleResult(sb, text.length() + 4);
        }
    }

    class JavaDocHandler implements NewlineHandler {

        @Override
        public boolean matchesRequirement(String beforeText, String afterText) {
            return beforeText.trim().startsWith("*") && !beforeText.trim().startsWith("*/");
        }

        @Override
        public NewlineHandleResult handleNewline(String beforeText, String afterText, int tabSize) {
            int count = TextUtils.countLeadingSpaceCount(beforeText, tabSize);
            int advanceAfter = getIndentAdvance(afterText);
            StringBuilder sb = new StringBuilder().append("\n")
                    .append(TextUtils.createIndent(count + advanceAfter, tabSize, useTab()))
                    .append("* ");
            return new NewlineHandleResult(sb, 0);
        }
    }
}
