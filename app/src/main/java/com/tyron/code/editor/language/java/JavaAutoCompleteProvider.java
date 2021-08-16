package com.tyron.code.editor.language.java;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
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

public class JavaAutoCompleteProvider implements AutoCompleteProvider {
    
    private CodeEditor mEditor;
    
    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
    }
    
    //TODO: Add return types / variable types
    @Override
    public List<CompletionItem> getAutoCompleteItems(String partial, boolean endsWithParen, TextAnalyzeResult prev, int line) {
        Cursor cursor = mEditor.getCursor();
        JavaParser parser = new JavaParser();
        CompilationUnitTree tree = parser.parse(mEditor.getText().toString(), cursor.getLeft());
        JavacTask task = parser.getTask();
        JavaAnalyzer.getInstance().setDiagnostics(parser.getDiagnostics());
        CompletionProvider provider = new CompletionProvider(task);
        
        CompletionList list = provider.complete(tree, cursor.getLeft());
        List<CompletionItem> result = new ArrayList<>();
        
        for (com.tyron.code.model.CompletionItem item : list.items) {
            CompletionItem newItem = new CompletionItem(item.label, "");
            result.add(newItem);
        }
        
        return result;
    }

}
