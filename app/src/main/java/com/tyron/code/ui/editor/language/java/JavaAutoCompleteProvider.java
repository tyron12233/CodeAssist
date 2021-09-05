package com.tyron.code.ui.editor.language.java;

import com.tyron.code.compiler.java.CompileBatch;
import com.tyron.code.completion.CompletionEngine;
import com.tyron.code.model.CompletionList;
import com.tyron.code.parser.FileManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.struct.CompletionItem;
import io.github.rosemoe.editor.text.Cursor;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.widget.CodeEditor;

public class JavaAutoCompleteProvider implements AutoCompleteProvider {
    
    private final CodeEditor mEditor;
    
    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String partial, boolean endsWithParen, TextAnalyzeResult prev, int line) {
       FileManager.getInstance().save(mEditor.getCurrentFile(), mEditor.getText().toString());
		Cursor cursor = mEditor.getCursor();

		// The previous call hasn't finished
		CompileBatch batch = CompletionEngine.getInstance().getCompiler().cachedCompile;
		if (batch != null && !batch.closed) {
			return Collections.emptyList();
		}
		CompletionList list = CompletionEngine.getInstance().complete(mEditor.getCurrentFile(), cursor.getLeft());

		List<CompletionItem> result = new ArrayList<>();

        for (com.tyron.code.model.CompletionItem item : list.items) {
            result.add(new CompletionItem(item));
        }

        return result;
    }

}
