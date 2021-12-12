package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.ProjectManager;
import com.tyron.builder.project.api.JavaProject;
import com.tyron.builder.project.api.Project;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.provider.CompletionEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
            return null;
        }

        if (CompletionEngine.isIndexing()) {
            return null;
        }

        Project currentProject = ProjectManager.getInstance().getCurrentProject();

        if (currentProject instanceof JavaProject) {
            List<CompletionItem> result = new ArrayList<>();

            Optional<CharSequence> content = currentProject.getFileManager()
                    .getFileContent(mEditor.getCurrentFile());
            if (content.isPresent()) {
                CompletionList completionList = CompletionEngine.getInstance()
                        .complete((JavaProject) currentProject,
                                mEditor.getCurrentFile(),
                                content.get().toString(),
                                prefix,
                                line,
                                column,
                                mEditor.getCursor().getLeft());

                for (com.tyron.completion.model.CompletionItem item : completionList.items) {
                    result.add(new CompletionItem(item));
                }
                return result;
            }
        }
        return null;
    }
}
