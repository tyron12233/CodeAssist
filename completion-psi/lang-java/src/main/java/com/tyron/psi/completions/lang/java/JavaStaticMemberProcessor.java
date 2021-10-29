package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportList;
import org.jetbrains.kotlin.com.intellij.psi.PsiImportStaticStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiMember;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameHelper;
import org.jetbrains.kotlin.com.intellij.psi.PsiReference;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiUtil;

import java.util.List;

/**
 * @author peter
 */
public class JavaStaticMemberProcessor extends StaticMemberProcessor {
    private final PsiElement myOriginalPosition;

    public JavaStaticMemberProcessor(CompletionParameters parameters) {
        super(parameters.getPosition());
        myOriginalPosition = parameters.getOriginalPosition();
        final PsiFile file = parameters.getPosition().getContainingFile();
        if (file instanceof PsiJavaFile) {
            final PsiImportList importList = ((PsiJavaFile)file).getImportList();
            if (importList != null) {
                for (PsiImportStaticStatement statement : importList.getImportStaticStatements()) {
                    importMembersOf(statement.resolveTargetClass());
                }
            }
        }
    }

    @Nullable
    @Override
    protected LookupElement createLookupElement(@NotNull PsiMember member, @NotNull final PsiClass containingClass, boolean shouldImport) {
        shouldImport |= myOriginalPosition != null && PsiTreeUtil.isAncestor(containingClass, myOriginalPosition, false);

        if (!PsiNameHelper.getInstance(member.getProject()).isIdentifier(member.getName(), PsiUtil.getLanguageLevel(getPosition()))) {
            return null;
        }

        PsiReference ref = createReferenceToMemberName(member);
        if (ref == null) return null;

        if (ref instanceof PsiReferenceExpression && ((PsiReferenceExpression)ref).multiResolve(true).length > 0) {
            shouldImport = false;
        }

        return LookupElementBuilder.create(member.toString());
//        if (member instanceof PsiMethod) {
//            return AutoCompletionPolicy.NEVER_AUTOCOMPLETE.applyPolicy(new GlobalMethodCallElement((PsiMethod)member, shouldImport, false));
//        }
//        return AutoCompletionPolicy.NEVER_AUTOCOMPLETE.applyPolicy(new VariableLookupItem((PsiField)member, shouldImport) {
//            @Override
//            public void handleInsert(@NotNull InsertionContext context) {
//                FeatureUsageTracker.getInstance().triggerFeatureUsed(JavaCompletionFeatures.GLOBAL_MEMBER_NAME);
//
//                super.handleInsert(context);
//            }
//        }.qualifyIfNeeded(ObjectUtils.tryCast(getPosition().getParent(), PsiJavaCodeReferenceElement.class)));
    }

    private PsiReference createReferenceToMemberName(@NotNull PsiMember member) {
        String exprText = member.getName() + (member instanceof PsiMethod ? "()" : "");
        return JavaPsiFacade.getElementFactory(member.getProject()).createExpressionFromText(exprText, myOriginalPosition).findReferenceAt(0);
    }

    @Override
    protected LookupElement createLookupElement(@NotNull List<? extends PsiMethod> overloads,
                                                @NotNull PsiClass containingClass,
                                                boolean shouldImport) {
        shouldImport |= myOriginalPosition != null && PsiTreeUtil.isAncestor(containingClass, myOriginalPosition, false);

        return LookupElementBuilder.create(overloads.get(0));
//        final JavaMethodCallElement element = new GlobalMethodCallElement(overloads.get(0), shouldImport, true);
//        JavaCompletionUtil.putAllMethods(element, overloads);
//        return element;
    }

//    private static class GlobalMethodCallElement extends JavaMethodCallElement {
//        GlobalMethodCallElement(PsiMethod member, boolean shouldImport, boolean mergedOverloads) {
//            super(member, shouldImport, mergedOverloads);
//        }
//
//        @Override
//        public void handleInsert(@NotNull InsertionContext context) {
//            FeatureUsageTracker.getInstance().triggerFeatureUsed(JavaCompletionFeatures.GLOBAL_MEMBER_NAME);
//
//            super.handleInsert(context);
//        }
//    }
}