package com.tyron.code.ui.editor.language.java;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;

import java.util.List;
import io.github.rosemoe.editor.struct.CompletionItem;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.widget.CodeEditor;
import io.github.rosemoe.editor.text.Cursor;

import com.tyron.code.model.CompletionList;
import java.util.ArrayList;
import com.tyron.code.ui.editor.log.LogViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.appcompat.app.AppCompatActivity;

import com.tyron.code.parser.FileManager;
import java.util.Collections;

import com.tyron.code.completion.CompletionEngine;
import com.tyron.code.compiler.CompileBatch;

public class JavaAutoCompleteProvider implements AutoCompleteProvider {
    
    private final CodeEditor mEditor;
    private final LogViewModel viewModel;
    
    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        viewModel = new ViewModelProvider((AppCompatActivity) editor.getContext()).get(LogViewModel.class);
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String partial, boolean endsWithParen, TextAnalyzeResult prev, int line) {
       FileManager.getInstance().save(mEditor.getCurrentFile(), mEditor.getText().toString());
		Cursor cursor = mEditor.getCursor();
        
		// The previous call hasnt finished
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
