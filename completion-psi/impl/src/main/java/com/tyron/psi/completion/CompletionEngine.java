package com.tyron.psi.completion;

import android.util.Log;

import com.tyron.psi.completion.impl.CompletionServiceImpl;
import com.tyron.psi.completions.lang.java.JavaCompletionContributor;
import com.tyron.psi.lookup.LookupElementPresentation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment;
import org.jetbrains.kotlin.com.intellij.core.CoreProjectEnvironment;
import org.jetbrains.kotlin.com.intellij.lang.java.JavaLanguage;
import org.jetbrains.kotlin.com.intellij.openapi.components.ComponentManager;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.DefaultPluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginId;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
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
    }

    volatile int invocationCount = -1;

    public synchronized void complete(PsiJavaFile file, PsiElement element, int offset) {
        CompletionParameters completionParameters = new CompletionParameters(element, file, CompletionType.SMART, offset, invocationCount++, null, new CompletionProcess() {
            @Override
            public boolean isAutopopupCompletion() {
                return true;
            }
        });
        mCompletionService.performCompletion(completionParameters, new Consumer<CompletionResult>() {
            @Override
            public void consume(CompletionResult completionResult) {
                Log.d("DASDASD", completionResult.toString());
            }
        });
    }
}
