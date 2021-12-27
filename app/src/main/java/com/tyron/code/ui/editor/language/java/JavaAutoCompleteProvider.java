package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.model.CompletionList;

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

        Project project = ProjectManager.getInstance().getCurrentProject();

        if (project == null) {
            return null;
        }

        Module currentModule = project.getModule(mEditor.getCurrentFile());

        if (currentModule instanceof JavaModule) {
            List<CompletionItem> result = new ArrayList<>();

            Optional<CharSequence> content = currentModule.getFileManager()
                    .getFileContent(mEditor.getCurrentFile());
            if (content.isPresent()) {
                CompletionList completionList = CompletionEngine.getInstance()
                        .complete(project,
                                currentModule,
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
