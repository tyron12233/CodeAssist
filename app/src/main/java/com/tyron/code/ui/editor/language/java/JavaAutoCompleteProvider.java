package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.language.AbstractAutoCompleteProvider;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;

import java.util.Optional;

import io.github.rosemoe.sora2.text.TextAnalyzeResult;
import io.github.rosemoe.sora2.widget.CodeEditor;

public class JavaAutoCompleteProvider extends AbstractAutoCompleteProvider {

    private final Editor mEditor;
    private final SharedPreferences mPreferences;

    public JavaAutoCompleteProvider(Editor editor) {
        mEditor = editor;
        mPreferences = ApplicationLoader.getDefaultPreferences();
    }


    @Nullable
    @Override
    public CompletionList getCompletionList(
            String prefix, TextAnalyzeResult colors, int line, int column) {
        if (!mPreferences.getBoolean("code_editor_completion", true)) {
            return null;
        }

        Project project = ProjectManager.getInstance().getCurrentProject();

        if (project == null) {
            return null;
        }

        Module currentModule = project.getModule(mEditor.getCurrentFile());

        if (currentModule instanceof JavaModule) {
            Optional<CharSequence> content = currentModule.getFileManager()
                    .getFileContent(mEditor.getCurrentFile());
            if (content.isPresent()) {
                 return CompletionEngine.getInstance()
                        .complete(project,
                                currentModule,
                                mEditor.getCurrentFile(),
                                content.get().toString(),
                                prefix,
                                line,
                                column,
                                mEditor.getCaret().getStart());
            }
        }
        return null;
    }
}
