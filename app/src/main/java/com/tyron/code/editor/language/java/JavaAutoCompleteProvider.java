package com.tyron.code.editor.language.java;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import io.github.rosemoe.editor.struct.CompletionItem;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.widget.CodeEditor;
import com.tyron.code.parser.JavaParser;
import io.github.rosemoe.editor.text.Cursor;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import com.tyron.code.completion.CompletionProvider;
import com.tyron.code.model.CompletionList;
import java.util.ArrayList;
import com.tyron.code.editor.log.LogViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.appcompat.app.AppCompatActivity;
import com.tyron.code.CompilerProvider;
import com.tyron.code.JavaCompilerService;
import com.tyron.code.parser.FileManager;
import java.util.Collections;
import java.util.stream.Collectors;
import com.tyron.code.completion.CompletionEngine;
import com.tyron.code.compiler.CompileBatch;

public class JavaAutoCompleteProvider implements AutoCompleteProvider {
    
    private final CodeEditor mEditor;
    private final LogViewModel viewModel;
	private final CompilerProvider provider;
    
    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        viewModel = new ViewModelProvider((AppCompatActivity) editor.getContext()).get(LogViewModel.class);
		provider = new JavaCompilerService(
                new HashSet<>(FileManager.getInstance().getCurrentProject().getJavaFiles().values()),
			Collections.emptySet(),
			Collections.emptySet()
		);
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String partial, boolean endsWithParen, TextAnalyzeResult prev, int line) {
       FileManager.getInstance().save(mEditor.getCurrentFile(), mEditor.getText().toString());
		Cursor cursor = mEditor.getCursor();
        
		// The previous call hasnt finished
		CompileBatch batch = CompletionEngine.getInstance().getCompiler().cachedCompile;
		if (batch != null && !CompletionEngine.getInstance().getCompiler().cachedCompile.closed) {
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
