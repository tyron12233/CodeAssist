package com.tyron.psi.completion;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.lang.LanguageExtension;
import org.jetbrains.kotlin.com.intellij.openapi.application.ReadAction;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbService;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.openapi.util.Pair;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;

import java.util.List;

public abstract class CompletionContributor {

    public static final ExtensionPointName<CompletionContributor> EP = new ExtensionPointName<>("com.intellij.completion.contributor");
    private final MultiMap<CompletionType, Pair<ElementPattern<? extends PsiElement>, CompletionProvider<CompletionParameters>>> myMap =
            new MultiMap<>();

    public final void extend(@Nullable CompletionType type,
                             @NotNull final ElementPattern<? extends PsiElement> place, CompletionProvider<CompletionParameters> provider) {
        myMap.putValue(type, new Pair<>(place, provider));
    }

    /**
     * The main contributor method that is supposed to provide completion variants to result, based on completion parameters.
     * The default implementation looks for {@link CompletionProvider}s you could register by
     * invoking {@link #extend(CompletionType, ElementPattern, CompletionProvider)} from your contributor constructor,
     * matches the desired completion type and {@link ElementPattern} with actual ones, and, depending on it, invokes those
     * completion providers.<p>
     *
     * If you want to implement this functionality directly by overriding this method, the following is for you.
     * Always check that parameters match your situation, and that completion type ({@link CompletionParameters#getCompletionType()}
     * is of your favourite kind. This method is run inside a read action. If you do any long activity non-related to PSI in it, please
     * ensure you call {@link ProgressManager#checkCanceled()} often enough so that the completion process
     * can be cancelled smoothly when the user begins to type in the editor.
     */
    public void fillCompletionVariants(@NotNull final CompletionParameters parameters, @NotNull CompletionResultSet result) {
        for (final Pair<ElementPattern<? extends PsiElement>, CompletionProvider<CompletionParameters>> pair : myMap.get(parameters.getCompletionType())) {
            ProgressManager.checkCanceled();
            final ProcessingContext context = new ProcessingContext();
            if (pair.first.accepts(parameters.getPosition(), context)) {
                pair.second.addCompletionVariants(parameters, context, result);
                if (result.isStopped()) {
                    return;
                }
            }
        }
        for (final Pair<ElementPattern<? extends PsiElement>, CompletionProvider<CompletionParameters>> pair : myMap.get(null)) {
            final ProcessingContext context = new ProcessingContext();
            if (pair.first.accepts(parameters.getPosition(), context)) {
                pair.second.addCompletionVariants(parameters, context, result);
                if (result.isStopped()) {
                    return;
                }
            }
        }
    }

    /**
     * Invoked before completion is started. It is used mainly for determining custom offsets in the editor, and to change default dummy identifier.
     */
    public void beforeCompletion(@NotNull CompletionInitializationContext context) {
    }

    /**
     * Invoked in a read action in parallel to the completion process. Used to calculate the replacement offset
     * (see {@link CompletionInitializationContext#setReplacementOffset(int)})
     * if it takes too much time to spend it in {@link #beforeCompletion(CompletionInitializationContext)},
     * e.g. doing {@link PsiFile#findReferenceAt(int)}
     *
     * Guaranteed to be invoked before any lookup element is selected
     *
     * @param context context
     */
    public void duringCompletion(@NotNull CompletionInitializationContext context) {
    }


    @NotNull
    public static List<CompletionContributor> forParameters(@NotNull final CompletionParameters parameters) {
        return ReadAction.compute(() -> {
            PsiElement position = parameters.getPosition();
            return forLanguageHonorDumbness(PsiUtilCore.getLanguageAtOffset(position.getContainingFile(), parameters.getOffset()), position.getProject());
        });
    }

    @NotNull
    public static List<CompletionContributor> forLanguage(@NotNull Language language) {
        return INSTANCE.forKey(language);
    }

    @NotNull
    public static List<CompletionContributor> forLanguageHonorDumbness(@NotNull Language language, @NotNull Project project) {
        return DumbService.getInstance(project).filterByDumbAwareness(forLanguage(language));
    }

    private static final LanguageExtension<CompletionContributor> INSTANCE = new CompletionExtension<>(EP.getName());
}
