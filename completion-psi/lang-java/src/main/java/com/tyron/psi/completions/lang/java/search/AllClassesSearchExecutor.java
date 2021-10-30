package com.tyron.psi.completions.lang.java.search;

import com.tyron.psi.concurrency.JobLauncher;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressIndicatorProvider;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.psi.JavaElementVisitor;
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor;
import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiCompiledElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiManager;
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.LocalSearchScope;
import org.jetbrains.kotlin.com.intellij.psi.search.SearchScope;
import org.jetbrains.kotlin.com.intellij.util.Processor;
import org.jetbrains.kotlin.com.intellij.util.QueryExecutor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import gnu.trove.THashSet;

public class AllClassesSearchExecutor  implements QueryExecutor<PsiClass, AllClassesSearch.SearchParameters> {

    @Override
    public boolean execute(@NotNull final AllClassesSearch.SearchParameters queryParameters, @NotNull final Processor<? super PsiClass> consumer) {
        SearchScope scope = queryParameters.getScope();

        if (scope == GlobalSearchScope.EMPTY_SCOPE) {
            return true;
        }

        if (scope instanceof GlobalSearchScope) {
            PsiManager manager = PsiManager.getInstance(queryParameters.getProject());
            manager.startBatchFilesProcessingMode();
            try {
                return processAllClassesInGlobalScope((GlobalSearchScope)scope, queryParameters, consumer);
            }
            finally {
                manager.finishBatchFilesProcessingMode();
            }
        }

        PsiElement[] scopeRoots = ((LocalSearchScope)scope).getScope();
        for (final PsiElement scopeRoot : scopeRoots) {
            if (!processScopeRootForAllClasses(scopeRoot, consumer)) return false;
        }
        return true;
    }

    private static boolean processAllClassesInGlobalScope(@NotNull final GlobalSearchScope scope,
                                                          @NotNull final AllClassesSearch.SearchParameters parameters,
                                                          @NotNull Processor<? super PsiClass> processor) {
        final Set<String> names = new THashSet<>(10000);
        Project project = parameters.getProject();
        processClassNames(project, scope, s -> {
            if (parameters.nameMatches(s)) {
                names.add(s);
            }
            return true;
        });

        List<String> sorted = new ArrayList<>(names);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);

        PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        return JobLauncher.getInstance().invokeConcurrentlyUnderProgress(sorted, ProgressIndicatorProvider.getGlobalProgressIndicator(), name ->
                processByName(project, scope, processor, cache, name));
    }

    public static boolean processClassesByNames(@NotNull Project project,
                                                @NotNull GlobalSearchScope scope,
                                                @NotNull Collection<String> names,
                                                @NotNull Processor<? super PsiClass> processor) {
        final PsiShortNamesCache cache = PsiShortNamesCache.getInstance(project);
        for (final String name : names) {
            ProgressIndicatorProvider.checkCanceled();
            if (!processByName(project, scope, processor, cache, name)) return false;
        }
        return true;
    }

    private static boolean processByName(Project project,
                                         GlobalSearchScope scope,
                                         Processor<? super PsiClass> processor,
                                         PsiShortNamesCache cache,
                                         String name) {
        for (PsiClass psiClass : cache.getClassesByName(name, scope)) {
            ProgressIndicatorProvider.checkCanceled();
            if (!processor.process(psiClass)) {
                return false;
            }
        }
        return true;
    }

    public static boolean processClassNames(@NotNull Project project, @NotNull GlobalSearchScope scope, @NotNull Processor<? super String> processor) {
        boolean success = PsiShortNamesCache.getInstance(project).processAllClassNames(s -> {
            ProgressManager.checkCanceled();
            return processor.process(s);
        }, scope, null);
        ProgressManager.checkCanceled();
        return success;
    }

    private static boolean processScopeRootForAllClasses(@NotNull final PsiElement scopeRoot, @NotNull final Processor<? super PsiClass> processor) {
        final boolean[] stopped = {false};

        final JavaElementVisitor visitor = scopeRoot instanceof PsiCompiledElement ? new JavaRecursiveElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!stopped[0]) {
                    super.visitElement(element);
                }
            }

            @Override
            public void visitClass(PsiClass aClass) {
                stopped[0] = !processor.process(aClass);
                super.visitClass(aClass);
            }
        } : new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                if (!stopped[0]) {
                    super.visitElement(element);
                }
            }

            @Override
            public void visitClass(PsiClass aClass) {
                stopped[0] = !processor.process(aClass);
                super.visitClass(aClass);
            }
        };
        ApplicationManager.getApplication().runReadAction(() -> scopeRoot.accept(visitor));

        return !stopped[0];
    }

}
