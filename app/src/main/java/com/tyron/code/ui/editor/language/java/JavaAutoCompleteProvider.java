package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.completion.model.CompletionList;
import com.tyron.builder.parser.FileManager;
import com.tyron.completion.CompileBatch;
import com.tyron.completion.provider.CompletionEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;

public class JavaAutoCompleteProvider implements AutoCompleteProvider {
    
    private final CodeEditor mEditor;
    private final SharedPreferences mPreferences;

    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }


    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) {
        FileManager.getInstance().save(mEditor.getCurrentFile(), mEditor.getText().toString());

        if (!mPreferences.getBoolean("code_editor_completion", true)) {
            return Collections.emptyList();
        }

        Cursor cursor = mEditor.getCursor();

        // The previous call hasn't finished
        CompileBatch batch = CompletionEngine.getInstance().getCompiler().cachedCompile;
        if (batch != null && !batch.closed) {
            return Collections.emptyList();
        }
        CompletionList list = CompletionEngine.getInstance().complete(mEditor.getCurrentFile(), cursor.getLeft());

        List<CompletionItem> result = new ArrayList<>();

        for (com.tyron.completion.model.CompletionItem item : list.items) {
            result.add(new CompletionItem(item));
        }

        return result;
    }
}
