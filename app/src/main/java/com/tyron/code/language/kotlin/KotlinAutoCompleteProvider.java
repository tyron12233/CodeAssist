package com.tyron.code.language.kotlin;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.KotlinModule;
import com.tyron.builder.project.api.Module;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.language.AbstractAutoCompleteProvider;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;
import com.tyron.kotlin.completion.core.model.KotlinEnvironment;
import com.tyron.kotlin.completion.core.resolve.AnalysisResultWithProvider;
import com.tyron.kotlin.completion.core.resolve.KotlinAnalyzer;
import com.tyron.kotlin_completion.CompletionEngine;

import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.components.ServiceManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.resolve.jvm.KotlinCliJavaFileManager;

import java.util.Objects;

public class KotlinAutoCompleteProvider extends AbstractAutoCompleteProvider {

    private static final String TAG = KotlinAutoCompleteProvider.class.getSimpleName();

    private final Editor mEditor;
    private final SharedPreferences mPreferences;

    private KotlinCoreEnvironment environment;

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

        if (environment == null) {
            environment = KotlinEnvironment.getEnvironment((KotlinModule) currentModule);
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
