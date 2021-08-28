package com.tyron.code.editor.language.xml;
import com.tyron.code.editor.language.Language;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.widget.SymbolPairMatch;
import io.github.rosemoe.editor.interfaces.NewlineHandler;
import com.tyron.code.editor.language.xml.LanguageXML;

public class LanguageXML implements EditorLanguage {

	@Override
	public boolean isAutoCompleteChar(char ch) {
		return false;
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
	public AutoCompleteProvider getAutoCompleteProvider() {
		return new XMLAutoCompleteProvider();
	}

	@Override
	public CodeAnalyzer getAnalyzer() {
		return null;
	}

	@Override
	public SymbolPairMatch getSymbolPairs() {
		return null;
	}

	@Override
	public NewlineHandler[] getNewlineHandlers() {
		return null;
	}

	@Override
	public int getIndentAdvance(String content) {
		return 0;
	}
}
