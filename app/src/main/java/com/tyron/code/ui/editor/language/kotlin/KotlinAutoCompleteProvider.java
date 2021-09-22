package com.tyron.code.ui.editor.language.kotlin;

import com.tyron.builder.parser.FileManager;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.CompletionEngine;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.struct.CompletionItem;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import io.github.rosemoe.editor.widget.CodeEditor;

public class KotlinAutoCompleteProvider implements AutoCompleteProvider {

    private CompletionEngine engine;
    private final CodeEditor mEditor;

    public KotlinAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix, boolean isInCodeBlock, TextAnalyzeResult colors, int line) {

        if (com.tyron.completion.provider.CompletionEngine.isIndexing()) {
            return null;
        }

        if (engine == null) {
            engine = new CompletionEngine(FileManager.getInstance().getCurrentProject());
        }

        CompletionList list = engine.complete(mEditor.getCurrentFile(), mEditor.getText().toString(), mEditor.getCursor().getLeft());
        List<CompletionItem> result = new ArrayList<>();
        List<com.tyron.completion.model.CompletionItem> item = list.items;
        for (com.tyron.completion.model.CompletionItem comp : item) {
            result.add(new CompletionItem(comp));
        }

        return result;
    }
}
