package com.tyron.code.editor.log;

import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.interfaces.NewlineHandler;
import io.github.rosemoe.editor.widget.SymbolPairMatch;

public class LogEditorLanguage implements EditorLanguage {

    @Override
    public CodeAnalyzer getAnalyzer() {
        return new LogAnalyzer();
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return null;
    }

    @Override
    public boolean isAutoCompleteChar(char ch) {
        return false;
    }

    @Override
    public int getIndentAdvance(String content) {
        return 0;
    }

    @Override
    public boolean useTab() {
        return false;
    }

    @Override
    public CharSequence format(CharSequence text) {
        return null;
    }

    @Override
    public SymbolPairMatch getSymbolPairs() {
        return null;
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return new NewlineHandler[0];
    }
}
