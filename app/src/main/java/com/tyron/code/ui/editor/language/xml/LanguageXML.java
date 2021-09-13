package com.tyron.code.ui.editor.language.xml;
import com.tyron.code.compiler.manifest.xml.XmlFormatPreferences;
import com.tyron.code.compiler.manifest.xml.XmlFormatStyle;
import com.tyron.code.compiler.manifest.xml.XmlPrettyPrinter;

import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.interfaces.CodeAnalyzer;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.text.TextAnalyzer;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.widget.SymbolPairMatch;
import io.github.rosemoe.editor.interfaces.NewlineHandler;

public class LanguageXML implements EditorLanguage {

	private final CodeEditor mEditor;
	public LanguageXML(CodeEditor codeEditor) {
		mEditor = codeEditor;
	}
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
		return XmlPrettyPrinter.prettyPrint(String.valueOf(text),
				XmlFormatPreferences.defaults(), XmlFormatStyle.LAYOUT, "\n");
	}

	@Override
	public AutoCompleteProvider getAutoCompleteProvider() {
		return new XMLAutoCompleteProvider();
	}

	@Override
	public CodeAnalyzer getAnalyzer() {
		return new XMLAnalyzer(mEditor);
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
