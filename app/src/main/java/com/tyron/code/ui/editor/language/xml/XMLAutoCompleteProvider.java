package com.tyron.code.ui.editor.language.xml;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import java.util.List;
import io.github.rosemoe.sora.data.CompletionItem;
import java.util.Collections;

public class XMLAutoCompleteProvider implements AutoCompleteProvider {

	public XMLAutoCompleteProvider() {

	}

	@Override
	public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) {
		return Collections.emptyList();
	}
}
