package com.tyron.code.ui.editor.language.kotlin;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.language.AbstractAutoCompleteProvider;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;
import com.tyron.kotlin_completion.CompletionEngine;

public class KotlinAutoCompleteProvider extends AbstractAutoCompleteProvider {

    private static final String TAG = KotlinAutoCompleteProvider.class.getSimpleName();

    private final Editor mEditor;
    private final SharedPreferences mPreferences;


    public KotlinAutoCompleteProvider(Editor editor) {
        mEditor = editor;
        mPreferences = ApplicationLoader.getDefaultPreferences();
    }

    @Nullable
    @Override
    public CompletionList getCompletionList(String prefix, int line, int column) {
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

        if (mEditor.getCurrentFile() == null) {
            return null;
        }

        CompletionEngine engine = CompletionEngine.getInstance((AndroidModule) currentModule);

        if (engine.isIndexing()) {
            return null;
        }

        // waiting for code editor to support async code completions
        return engine.complete(mEditor.getCurrentFile(),
                String.valueOf(mEditor.getContent()),
                prefix,
                line,
                column,
                mEditor.getCaret().getStart());
    }
}
