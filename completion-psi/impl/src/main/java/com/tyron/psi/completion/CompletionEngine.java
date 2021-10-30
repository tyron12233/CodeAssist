package com.tyron.psi.completion;

import android.util.Log;

import com.tyron.psi.completion.impl.CompletionServiceImpl;
import com.tyron.psi.completions.lang.java.BasicExpressionCompletionContributor;
import com.tyron.psi.completions.lang.java.JavaClassNameCompletionContributor;
import com.tyron.psi.completions.lang.java.JavaCompletionContributor;
import com.tyron.psi.editor.CaretModel;
import com.tyron.psi.editor.Editor;
import com.tyron.psi.lookup.LookupElementPresentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.core.CoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.components.ComponentManager;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.DefaultPluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.util.Consumer;

public class CompletionEngine {

    private final CoreProjectEnvironment mProjectEnvironment;
    private final CompletionService mCompletionService;

    public CompletionEngine(CoreProjectEnvironment environment) {
        mProjectEnvironment = environment;
        mCompletionService = new CompletionServiceImpl();

        environment.registerProjectExtensionPoint(CompletionContributor.EP, CompletionContributor.class);
        environment.getEnvironment().registerApplicationService(CompletionService.class, mCompletionService);

        JavaCompletionContributor javaCompletionContributor = new JavaCompletionContributor();
        CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, javaCompletionContributor);
        CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaClassNameCompletionContributor());
      //  CompletionContributor.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new BasicExpressionCompletionContributor());
    }

    volatile int invocationCount = -1;

    public synchronized void complete(PsiJavaFile file, PsiElement element, int offset, Consumer<CompletionResult> consumer) {
        CompletionParameters completionParameters = new CompletionParameters(element, file, CompletionType.BASIC, offset, invocationCount++, new Editor() {
            @Override
            public Document getDocument() {
                return new Document() {
                    @Override
                    public @NotNull CharSequence getImmutableCharSequence() {
                        return null;
                    }

                    @Override
                    public int getLineCount() {
                        return 0;
                    }

                    @Override
                    public int getLineNumber(int i) {
                        return 0;
                    }

                    @Override
                    public int getLineStartOffset(int i) {
                        return 0;
                    }

                    @Override
                    public int getLineEndOffset(int i) {
                        return 0;
                    }

                    @Override
                    public void replaceString(int i, int i1, @NotNull CharSequence charSequence) {

                    }

                    @Override
                    public boolean isWritable() {
                        return false;
                    }

                    @Override
                    public long getModificationStamp() {
                        return 0;
                    }

                    @Override
                    public void setText(@NotNull CharSequence charSequence) {

                    }

                    @Override
                    public <T> T getUserData(@NotNull Key<T> key) {
                        return null;
                    }

                    @Override
                    public <T> void putUserData(@NotNull Key<T> key, @Nullable T t) {

                    }
                };
            }

            @Override
            public CaretModel getCaretModel() {
                return null;
            }

            @Override
            public Project getProject() {
                return CompletionEngine.this.mProjectEnvironment.getProject();
            }

            @Override
            public boolean isViewer() {
                return false;
            }

            @Override
            public <T> T getUserData(@NotNull Key<T> key) {
                return null;
            }

            @Override
            public <T> void putUserData(@NotNull Key<T> key, @Nullable T t) {

            }
        }, new CompletionProcess() {
            @Override
            public boolean isAutopopupCompletion() {
                return true;
            }
        });
        mCompletionService.performCompletion(completionParameters, consumer);
    }
}
