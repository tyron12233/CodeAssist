package com.tyron.completion.psi.completion;

import static org.jetbrains.kotlin.com.intellij.patterns.PlatformPatterns.psiElement;
import static org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns.or;

import com.tyron.completion.TailType;
import com.tyron.completion.psi.codeInsight.ExpectedTypeInfo;
import com.tyron.completion.psi.codeInsight.ExpectedTypeInfoImpl;
import com.tyron.completion.psi.codeInsight.ExpectedTypesProvider;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.CommonClassNames;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassObjectAccessExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiClassType;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElementFactory;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiSuperExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiThrowStatement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.it.unimi.dsi.fastutil.Hash;

import java.util.List;

public class JavaSmartCompletionContributor {

    static final Hash.Strategy<ExpectedTypeInfo> EXPECTED_TYPE_INFO_STRATEGY = new Hash.Strategy<ExpectedTypeInfo>() {
        @Override
        public int hashCode(ExpectedTypeInfo object) {
            return object == null ? 0 : object.getType().hashCode();
        }

        @Override
        public boolean equals(ExpectedTypeInfo o1, ExpectedTypeInfo o2) {
            if (o1 == o2) {
                return true;
            }
            if (o1 == null || o2 == null) {
                return false;
            }
            return o1.getType().equals(o2.getType());
        }
    };

    public static final ElementPattern<PsiElement> AFTER_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW));
    static final ElementPattern<PsiElement>
            AFTER_THROW_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW).afterLeaf(
            psiElement().withText(PsiKeyword.THROW)));

    public static final ElementPattern<PsiElement> INSIDE_EXPRESSION = or(
            psiElement().withParent(PsiExpression.class)
                    .andNot(psiElement().withParent(PsiLiteralExpression.class))
                    .andNot(psiElement().withParent(PsiMethodReferenceExpression.class)),
            psiElement().inside(psiElement(PsiClassObjectAccessExpression.class)),
            psiElement().inside(psiElement(PsiThisExpression.class)),
            psiElement().inside(psiElement(PsiSuperExpression.class)));

    public static ExpectedTypeInfo @NotNull [] getExpectedTypes(PsiElement position, boolean voidable) {
        if (psiElement().withParent(psiElement(PsiReferenceExpression.class).withParent(
                PsiThrowStatement.class)).accepts(position)) {
            final PsiElementFactory factory = JavaPsiFacade.getElementFactory(position.getProject());
            final PsiClassType classType = factory
                    .createTypeByFQClassName(CommonClassNames.JAVA_LANG_RUNTIME_EXCEPTION, position.getResolveScope());
            final List<ExpectedTypeInfo> result = new SmartList<>();
            result.add(new ExpectedTypeInfoImpl(classType, ExpectedTypeInfo.TYPE_OR_SUBTYPE, classType, TailType.SEMICOLON, null, ExpectedTypeInfoImpl.NULL));
            final PsiMethod method = PsiTreeUtil.getContextOfType(position, PsiMethod.class, true);
            if (method != null) {
                for (final PsiClassType type : method.getThrowsList().getReferencedTypes()) {
                    result.add(new ExpectedTypeInfoImpl(type, ExpectedTypeInfo.TYPE_OR_SUBTYPE, type, TailType.SEMICOLON, null, ExpectedTypeInfoImpl.NULL));
                }
            }
            return result.toArray(ExpectedTypeInfo.EMPTY_ARRAY);
        }

        PsiExpression expression = PsiTreeUtil.getContextOfType(position, PsiExpression.class, true);
        if (expression == null) return ExpectedTypeInfo.EMPTY_ARRAY;

        return ExpectedTypesProvider.getExpectedTypes(expression, true, voidable, false);
    }
}
