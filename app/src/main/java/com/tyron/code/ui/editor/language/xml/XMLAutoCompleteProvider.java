package com.tyron.code.ui.editor.language.xml;

import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import java.util.List;
import io.github.rosemoe.editor.struct.CompletionItem;
import java.util.Collections;

public class XMLAutoCompleteProvider implements AutoCompleteProvider {

	public XMLAutoCompleteProvider() {

	}

	@Override
	public List<CompletionItem> getAutoCompleteItems(String prefix, boolean isInCodeBlock, TextAnalyzeResult colors, int line) {
		return Collections.emptyList();
	}

}
