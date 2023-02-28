package com.tyron.code.language.groovy;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.code.language.LanguageManager;
import com.tyron.legacyEditor.Editor;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.Token;

import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
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
        return new NewlineHandler[0];
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
}
