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
import com.tyron.code.editor.log.LogViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.appcompat.app.AppCompatActivity;

public class JavaAutoCompleteProvider implements AutoCompleteProvider {
    
    private CodeEditor mEditor;
    private LogViewModel viewModel;
    
    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        viewModel = new ViewModelProvider((AppCompatActivity) editor.getContext()).get(LogViewModel.class);
    }
    
    //TODO: Add return types / variable types
    @Override
    public List<CompletionItem> getAutoCompleteItems(String partial, boolean endsWithParen, TextAnalyzeResult prev, int line) {
        Cursor cursor = mEditor.getCursor();
        JavaParser parser = new JavaParser(viewModel);
        CompilationUnitTree tree = parser.parse(mEditor.getText().toString(), cursor.getLeft());
        JavacTask task = parser.getTask();
        //JavaAnalyzer.getInstance().setDiagnostics(parser.getDiagnostics());
        CompletionProvider provider = new CompletionProvider(parser);
        
        CompletionList list = provider.complete(tree, cursor.getLeft());
        List<CompletionItem> result = new ArrayList<>();
        
        for (com.tyron.code.model.CompletionItem item : list.items) {
            result.add(new CompletionItem(item));
        }
        
        return result;
    }

}
