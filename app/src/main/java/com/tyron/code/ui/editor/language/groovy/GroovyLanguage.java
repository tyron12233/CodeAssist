package com.tyron.code.ui.editor.language.groovy;

import io.github.rosemoe.sora2.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora2.interfaces.CodeAnalyzer;
import io.github.rosemoe.sora2.interfaces.EditorLanguage;
import io.github.rosemoe.sora2.interfaces.NewlineHandler;
import io.github.rosemoe.sora2.widget.CodeEditor;
import io.github.rosemoe.sora2.widget.SymbolPairMatch;

public class GroovyLanguage implements EditorLanguage {

    private final CodeEditor mEditor;

    public GroovyLanguage(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public CodeAnalyzer getAnalyzer() {
        return new GroovyAnalyzer(mEditor);
    }

    @Override
    public AutoCompleteProvider getAutoCompleteProvider() {
        return (prefix, analyzeResult, line, column) -> null;
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
        return true;
    }

    @Override
    public CharSequence format(CharSequence text) {
        return text;
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
