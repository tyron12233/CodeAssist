package com.tyron.code.ui.editor.language.kotlin;

import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.tyron.code.ui.project.ProjectManager;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.model.CompletionList;
import com.tyron.kotlin_completion.CompletionEngine;

import java.nio.channels.ClosedByInterruptException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;

public class KotlinAutoCompleteProvider implements AutoCompleteProvider {

    private static final String TAG = KotlinAutoCompleteProvider.class.getSimpleName();

    private final CodeEditor mEditor;
    private final SharedPreferences mPreferences;


    public KotlinAutoCompleteProvider(CodeEditor editor) {
        mEditor = editor;
        mPreferences = PreferenceManager.getDefaultSharedPreferences(editor.getContext());
    }

    private CompletableFuture<CompletionList> mTask;

    @Override
    public List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult analyzeResult, int line, int column) throws InterruptedException {
        if (mTask != null) {
            mTask.cancel(true);
            mTask = null;
        }

        if (!mPreferences.getBoolean(SharedPreferenceKeys.KOTLIN_COMPLETIONS, false)) {
            return null;
        }

        if (com.tyron.completion.java.provider.CompletionEngine.isIndexing()) {
            return null;
        }

        if (!mPreferences.getBoolean(SharedPreferenceKeys.KOTLIN_COMPLETIONS, false)) {
            return null;
        }

        Project project = ProjectManager.getInstance()
                .getCurrentProject();
        if (project == null) {
            return null;
        }

        Module currentModule = project.getModule(mEditor.getCurrentFile());

        if (!(currentModule instanceof AndroidModule)) {
            return null;
        }

        CompletionEngine engine = CompletionEngine.getInstance((AndroidModule) currentModule);

        if (engine.isIndexing()) {
            return null;
        }

        try {
            // waiting for code editor to support async code completions
            mTask = engine.complete(mEditor.getCurrentFile(), mEditor.getText().toString(), prefix, line, column, mEditor.getCursor().getLeft());
        } catch (RuntimeException e) {
            if (e.getCause() instanceof ClosedByInterruptException) {
                throw new InterruptedException(e.getCause().getMessage());
            }
            throw e;
        }

        if (mTask.isCancelled()) {
            return null;
        }

        try {
            return mTask.get().items.stream().map(CompletionItem::new)
                    .collect(Collectors.toList());
        } catch (ExecutionException e) {
            return null;
        }
    }
}
