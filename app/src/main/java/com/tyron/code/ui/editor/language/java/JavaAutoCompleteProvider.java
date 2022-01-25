package com.tyron.code.ui.editor.language.java;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.tyron.code.ui.editor.language.AbstractAutoCompleteProvider;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.completion.main.CompletionEngine;
import com.tyron.completion.model.CompletionList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;

public class JavaAutoCompleteProvider extends AbstractAutoCompleteProvider {

    private final CodeEditor mEditor;
    private final SharedPreferences mPreferences;

    public JavaAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }


    @Nullable
    @Override
    public CompletableFuture<CompletionList> getCompletionList(
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
                return CompletableFuture.supplyAsync(() -> CompletionEngine.getInstance()
                        .complete(project,
                                currentModule,
                                mEditor.getCurrentFile(),
                                content.get().toString(),
                                prefix,
                                line,
                                column,
                                mEditor.getCursor().getLeft()));
            }
        }
        return null;
    }
}
