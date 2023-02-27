package com.tyron.code.language.java;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.Project;
import com.tyron.code.language.EditorFormatter;
import com.tyron.code.language.LanguageManager;
import com.tyron.completion.legacy.CompletionParameters;
import com.tyron.completion.java.JavaCompletionProvider;
import com.tyron.completion.java.ShortNamesCache;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;
import com.tyron.language.api.CodeAssistLanguage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.format.AsyncFormatter;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextRange;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;

@Deprecated
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
            throw new UnsupportedOperationException("Formatting is not yet supported.");
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
                .setFile(editor.getCurrentFile())
                .setProject(editor.getProject())
                .setModule(ShortNamesCache.JDK_MODULE)
                .setContents(content.getReference().toString())
                .setPrefix(prefix)
                .build();
        JavaCompletionProvider provider = new JavaCompletionProvider();
        CompletionList list = provider.complete(parameters);

        publisher.setUpdateThreshold(0);
        list.getItems().forEach(publisher::addItem);
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
        throw new UnsupportedOperationException();
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return delegate.getSymbolPairs();
    }

    private final NewlineHandler[] newLineHandlers =
            new NewlineHandler[0];

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
}
