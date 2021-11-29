package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.builder.model.Project;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.provider.CompletionEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
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
    public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) throws InterruptedException {
        if (!mPreferences.getBoolean("code_editor_completion", true)) {
            return Collections.emptyList();
        }
        if (CompletionEngine.isIndexing()) {
            return Collections.emptyList();
        }

        Project currentProject = ProjectManager.getInstance().getCurrentProject();
        List<CompletionItem> result = new ArrayList<>();
        if (currentProject != null) {
            CompletionList completionList = CompletionEngine.getInstance().complete(currentProject,
                    mEditor.getCurrentFile(),
                    currentProject.getFileManager().readFile(mEditor.getCurrentFile()),
                    mEditor.getCursor().getLeft());

            for (com.tyron.completion.model.CompletionItem item : completionList.items) {
                result.add(new CompletionItem(item));
            }
            return result;
        } else {
            Log.w("JavaAutoCompleteProvider", "Current project is null");
        }
        return null;
    }
}
