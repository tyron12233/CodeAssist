package com.tyron.psi.completion;

import android.util.Log;

import com.tyron.psi.completion.impl.CompletionServiceImpl;
import com.tyron.psi.completions.lang.java.JavaCompletionContributor;

import org.jetbrains.kotlin.com.intellij.core.CoreProjectEnvironment;
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
        environment.registerProjectExtensionPoint(CompletionContributor.EP, JavaCompletionContributor.class);
        environment.getEnvironment().registerApplicationService(CompletionService.class, mCompletionService);
    }

    public void complete(PsiJavaFile file, PsiElement element, int offset) {
        CompletionParameters completionParameters = new CompletionParameters(element, file, CompletionType.BASIC, offset, 0, null, new CompletionProcess() {
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
