package com.tyron.code.language.kotlin;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.code.language.CachedAutoCompleteProvider;
import com.tyron.code.language.LanguageManager;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;
import com.tyron.kotlin.completion.KotlinEnvironment;

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

public class KotlinLanguage implements Language {

    private static final String GRAMMAR_NAME = "kotlin.tmLanguage";
    private static final String LANGUAGE_PATH = "textmate/kotlin/syntaxes/kotlin.tmLanguage";
    private static final String CONFIG_PATH = "textmate/kotlin/language-configuration.json";

    private final TextMateLanguage delegate;
    private final Editor editor;
    private final CachedAutoCompleteProvider autoCompleteProvider;

    private KotlinEnvironment kotlinEnvironment;

    public KotlinLanguage(Editor editor) {
        this.editor = editor;
        delegate = LanguageManager.createTextMateLanguage(GRAMMAR_NAME,
                LANGUAGE_PATH,
                CONFIG_PATH,
                editor);
        autoCompleteProvider = new CachedAutoCompleteProvider(editor,
                new KotlinAutoCompleteProvider(editor));
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
        CompletionList completionList = autoCompleteProvider.getCompletionList(null,
                position.getLine(),
                position.getColumn());
        if (completionList == null) {
            return;
        }
        completionList.getItems().forEach(publisher::addItem);
    }

    @Override
    public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
        return delegate.getIndentAdvance(content, line, column);
    }

    @Override
    public boolean useTab() {
        return delegate.useTab();
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
}
