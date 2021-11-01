package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.progress.ProgressManager;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceService;
import org.jetbrains.kotlin.com.intellij.psi.ReferenceRange;
import org.jetbrains.kotlin.com.intellij.util.Processor;

import java.util.List;

/**
 * @author peter
 */
public final class SingleTargetRequestResultProcessor extends RequestResultProcessor {
    private static final PsiReferenceService ourReferenceService = PsiReferenceService.getService();
    private final PsiElement myTarget;

    public SingleTargetRequestResultProcessor(@NotNull PsiElement target) {
        super(target);
        myTarget = target;
    }

    @Override
    public boolean processTextOccurrence(@NotNull PsiElement element, int offsetInElement, @NotNull final Processor<? super PsiReference> consumer) {
        if (!myTarget.isValid()) {
            return false;
        }

        final List<PsiReference> references = ourReferenceService.getReferences(element,
                new PsiReferenceService.Hints(myTarget, offsetInElement));
        //noinspection ForLoopReplaceableByForEach
        for (int i = 0; i < references.size(); i++) {
            PsiReference ref = references.get(i);
            ProgressManager.checkCanceled();
            if (ReferenceRange.containsOffsetInElement(ref, offsetInElement) && ref.isReferenceTo(myTarget) && !consumer.process(ref)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("HardCodedStringLiteral")
    @Override
    public String toString() {
        return "SingleTarget: " + myTarget;
    }
}

