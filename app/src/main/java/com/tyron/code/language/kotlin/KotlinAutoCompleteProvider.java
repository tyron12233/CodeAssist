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
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.completion.model.DrawableKind;
import com.tyron.completion.util.CompletionUtils;
import com.tyron.editor.Editor;
import com.tyron.kotlin.completion.KotlinCompletionUtils;
import com.tyron.kotlin.completion.core.model.KotlinAnalysisProjectCache;
import com.tyron.kotlin.completion.core.model.KotlinEnvironment;
import com.tyron.kotlin.completion.core.resolve.AnalysisResultWithProvider;
import com.tyron.kotlin.completion.core.resolve.KotlinAnalyzer;
import com.tyron.kotlin_completion.CompletionEngine;
import com.tyron.kotlin_completion.util.PsiUtils;

import org.jetbrains.kotlin.analyzer.AnalysisResult;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.openapi.components.ServiceManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.name.Name;
import org.jetbrains.kotlin.psi.KtFile;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.resolve.jvm.KotlinCliJavaFileManager;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import kotlin.jvm.functions.Function1;

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

        PsiElement psiElement = KotlinCompletionUtils.INSTANCE
                .getPsiElement(mEditor, mEditor.getCaret().getStart());
        KtSimpleNameExpression parent =
                PsiUtils.findParent(psiElement, KtSimpleNameExpression.class);

        String identifierPart = CompletionUtils.computePrefix(
                mEditor.getContent().getLineString(mEditor.getCaret().getStartLine()),
                mEditor.getCharPosition(mEditor.getCaret().getStart()),
                CompletionUtils.JAVA_PREDICATE
        );
        Collection<DeclarationDescriptor> referenceVariants = KotlinCompletionUtils.INSTANCE
                .getReferenceVariants(parent, name -> true, mEditor.getCurrentFile(), identifierPart);
        List<CompletionItem> items = referenceVariants.stream().map(it -> {
            CompletionItem completionItem = new CompletionItem();
            completionItem.iconKind = DrawableKind.Method;
            completionItem.label = it.getName().toString();
            return completionItem;
        }).collect(Collectors.toList());
        return CompletionList.builder(identifierPart)
                .addItems(items)
                .build();
    }
}
