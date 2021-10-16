package com.tyron.code.ui.editor.language.kotlin;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.builder.parser.FileManager;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.CompletionEngine;

import org.jetbrains.kotlin.com.intellij.openapi.progress.ProcessCanceledException;

import java.nio.channels.ClosedByInterruptException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;

public class KotlinAutoCompleteProvider implements AutoCompleteProvider {

    private final CodeEditor mEditor;
    private final SharedPreferences mPreferences;


    public KotlinAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }

    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) throws InterruptedException {
        if (com.tyron.completion.provider.CompletionEngine.isIndexing()) {
            return null;
        }

        if (!mPreferences.getBoolean("code_editor_completion", true)) {
            return Collections.emptyList();
        }

        try {
            CompletionList list = CompletionEngine.getInstance(ProjectManager.getInstance().getCurrentProject())
                    .complete(mEditor.getCurrentFile(), mEditor.getText().toString(), mEditor.getCursor().getLeft());
            List<CompletionItem> result = new ArrayList<>();
            List<com.tyron.completion.model.CompletionItem> item = list.items;
            for (com.tyron.completion.model.CompletionItem comp : item) {
                result.add(new CompletionItem(comp));
            }
            return result;
        } catch (ProcessCanceledException | ClosedByInterruptException e) {
            throw new InterruptedException(e.getMessage());
        }
    }
}
