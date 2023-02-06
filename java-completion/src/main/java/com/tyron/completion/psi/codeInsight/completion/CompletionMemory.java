package com.tyron.completion.psi.codeInsight.completion;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;
import org.jetbrains.kotlin.com.intellij.openapi.editor.RangeMarker;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.Segment;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.psi.FileViewProvider;
import org.jetbrains.kotlin.com.intellij.psi.PsiCall;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.SmartPsiElementPointer;

import java.util.LinkedList;
import java.util.Optional;

public class CompletionMemory {

    public static final Key<LinkedList<RangeMarker>> LAST_CHOSEN_METHODS = Key.create("COMPLETED_METHODS");
    public static final Key<SmartPsiElementPointer<PsiMethod>> CHOSEN_METHODS = Key.create("CHOSEN_METHODS");


    private static TextRange getAnchorRange(PsiCall call) {
        if (call instanceof PsiMethodCallExpression) {
            PsiElement referenceNameElement = ((PsiMethodCallExpression) call).getMethodExpression()
                    .getReferenceNameElement();
            if (referenceNameElement != null) {
                return referenceNameElement.getTextRange();
            }
        } else if (call instanceof PsiNewExpression) {
            PsiJavaCodeReferenceElement classOrAnonymousClassReference =
                    ((PsiNewExpression) call).getClassOrAnonymousClassReference();
            if (classOrAnonymousClassReference != null) {
                PsiElement referenceNameElement =
                        classOrAnonymousClassReference.getReferenceNameElement();
                if (referenceNameElement != null) {
                    return referenceNameElement.getTextRange();
                }
            }
        }

        return null;
    }
    @Nullable
    public static PsiMethod getChosenMethod(PsiCall call) {
        TextRange anchorRange = getAnchorRange(call);
        if (anchorRange == null) {
            return null;
        }
        PsiFile containingFile = call.getContainingFile();
        if (containingFile == null) {
            return null;
        }
        FileViewProvider viewProvider = containingFile.getViewProvider();
        Document document = viewProvider.getDocument();
        if (document == null) {
            return null;
        }

        LinkedList<RangeMarker> completedMethods = document
                .getUserData(LAST_CHOSEN_METHODS);
        if (completedMethods == null) {
            return null;
        }

        Optional<RangeMarker> method =
                completedMethods.stream().filter(it -> haveSameRange(it, anchorRange)).findAny();
        if (method.isPresent()) {
            SmartPsiElementPointer<PsiMethod> userData = method.get().getUserData(CHOSEN_METHODS);
            if (userData != null) {
                return userData.getElement();
            }
        }
        return null;
    }

    private static boolean haveSameRange(Segment s1, Segment s2) {
        return s1.getStartOffset() == s2.getStartOffset() && s1.getEndOffset() == s2.getEndOffset();
    }
}
