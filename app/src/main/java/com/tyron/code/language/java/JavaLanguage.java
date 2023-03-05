package com.tyron.code.language.java;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.sun.tools.javac.util.JCDiagnostic;
import com.tyron.builder.project.Project;
import com.tyron.code.language.CompletionItemWrapper;
import com.tyron.code.language.EditorFormatter;
import com.tyron.code.language.LanguageManager;
import com.tyron.completion.CompletionParameters;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.java.compiler.services.NBLog;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;
import com.tyron.language.api.CodeAssistLanguage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion;
import io.github.rosemoe.sora.lang.format.AsyncFormatter;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextRange;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

public class JavaLanguage  implements Language, EditorFormatter, CodeAssistLanguage {

    private static final Logger LOGGER = LoggerFactory.getLogger(JavaLanguage.class);

    private static final String GRAMMAR_NAME = "java.tmLanguage.json";
    private static final String LANGUAGE_PATH = "textmate/java/syntaxes/java.tmLanguage.json";
    private static final String CONFIG_PATH = "textmate/java/language-configuration.json";

    private final Editor editor;
    private final TextMateLanguage delegate;
    private final Formatter formatter = new AsyncFormatter() {
        @Nullable
        @Override
        public TextRange formatAsync(@NonNull Content text, @NonNull TextRange cursorRange) {
            String format = com.tyron.eclipse.formatter.Formatter.format(text.toString(),
                    cursorRange.getStartIndex(),
                    cursorRange.getEndIndex() - cursorRange.getStartIndex());
            if (!text.toString().equals(format)) {
                text.delete(0, text.getLineCount() - 1);
                text.insert(0, 0, format);
            }
            return cursorRange;
        }

        @Nullable
        @Override
        public TextRange formatRegionAsync(@NonNull Content text,
                                           @NonNull TextRange rangeToFormat,
                                           @NonNull TextRange cursorRange) {
            return null;
        }
    };


    public JavaLanguage(Editor editor) {
        this.editor = editor;
        delegate = LanguageManager.createTextMateLanguage(GRAMMAR_NAME, LANGUAGE_PATH, CONFIG_PATH, editor);
    }


    public boolean isAutoCompleteChar(char p1) {
        return p1 == '.' || MyCharacter.isJavaIdentifierPart(p1);
    }

    public int getIndentAdvance(String p1) {
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(p1);
        Tokens token;
        int advance = 0;
        while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
            switch (token) {
                case LBRACE:
                    advance++;
                    break;
            }
        }
        return (advance * getTabWidth());
    }

    public int getFormatIndent(String line) {
        JavaTextTokenizer tokenizer = new JavaTextTokenizer(line);
        Tokens token;
        int advance = 0;
        while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
            switch (token) {
                case LBRACE:
                    advance++;
                    break;
                case RBRACE:
                    advance--;
            }
        }
        return (advance * getTabWidth());
    }

    @NonNull
    @Override
    public AnalyzeManager getAnalyzeManager() {
        return delegate.getAnalyzeManager();
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_SLIGHT;
    }

    @Override
    public void requireAutoComplete(@NonNull ContentReference content,
                                    @NonNull CharPosition position,
                                    @NonNull CompletionPublisher publisher,
                                    @NonNull Bundle extraArguments) throws CompletionCancelledException {
        String prefix = CompletionHelper.computePrefix(content, position, MyCharacter::isJavaIdentifierPart);
        CompletionParameters parameters = CompletionParameters.builder()
                .setColumn(position.getColumn())
                .setLine(position.getLine())
                .setIndex(position.getIndex())
                .setEditor(editor)
                .setFile(editor.getCurrentFile())
                .setProject(editor.getProject())
                .setModule(editor.getProject().getMainModule())
                .setContents(content.getReference().toString())
                .setPrefix(prefix)
                .build();
        JavaCompletionProvider provider = new JavaCompletionProvider();
        CompletionList list = provider.complete(parameters);

        publisher.setUpdateThreshold(0);
        publisher.addItems(list.getItems().stream().map(CompletionItemWrapper::new)
                .collect(Collectors.toList()));
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        String text = content.getLine(line).substring(0, column);
        return getIndentAdvance(text);
    }

    @Override
    public boolean useTab() {
        return true;
    }

    @NonNull
    @Override
    public Formatter getFormatter() {
        return formatter;
    }

    public int getTabWidth() {
        return 4;
    }

    public CharSequence format(CharSequence p1) {
        return format(p1, 0, p1.length());
    }

    @NonNull
    @Override
    public CharSequence format(@NonNull CharSequence contents, int start, int end) {
        return com.tyron.eclipse.formatter.Formatter.format(contents.toString(), start,
                end - start);
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return delegate.getSymbolPairs();
    }

    private final NewlineHandler[] newLineHandlers =
            new NewlineHandler[]{new BraceHandler(), new TwoIndentHandler(),
                    new JavaDocStartHandler(), new JavaDocHandler()};

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newLineHandlers;
    }

    @Override
    public void destroy() {
        delegate.destroy();
    }

    @Override
    public void onContentChange(File file, CharSequence content) {
        Project project = editor.getProject();
        if (project == null) {
            return;
        }
        CompilationInfo compilationInfo = CompilationInfo.get(project, editor.getCurrentFile());
        if (compilationInfo == null) {
            return;
        }
        JavaFileObject fileObject = new SimpleJavaFileObject(editor.getCurrentFile().toURI(),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
        try {
            compilationInfo.update(fileObject);
        } catch (Throwable t) {
            LOGGER.error("Failed to update compilation unit", t);
        }
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
