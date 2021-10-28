package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.IElementTypePattern;
import com.tyron.psi.patterns.InitialPatternCondition;
import com.tyron.psi.patterns.InitialPatternConditionPlus;
import com.tyron.psi.patterns.PlatformPatterns;
import com.tyron.psi.patterns.StandardPatterns;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteral;
import org.jetbrains.kotlin.com.intellij.psi.PsiLiteralExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiModifierListOwner;
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReturnStatement;
import org.jetbrains.kotlin.com.intellij.psi.tree.IElementType;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import java.util.Collections;
import java.util.List;

/**
 * @author peter
 */
public class PsiJavaPatterns extends StandardPatterns {

    public static IElementTypePattern elementType() {
        return PlatformPatterns.elementType();
    }


    public static PsiJavaElementPattern.Capture<PsiElement> psiElement() {
        return new PsiJavaElementPattern.Capture<>(PsiElement.class);
    }

    public static PsiJavaElementPattern.Capture<PsiElement> psiElement(IElementType type) {
        return psiElement().withElementType(type);
    }

    public static <T extends PsiElement> PsiJavaElementPattern.Capture<T> psiElement(final Class<T> aClass) {
        return new PsiJavaElementPattern.Capture<>(aClass);
    }

    @SafeVarargs
    public static PsiJavaElementPattern.Capture<PsiElement> psiElement(final Class<? extends PsiElement>... classAlternatives) {
        return new PsiJavaElementPattern.Capture<>(new InitialPatternCondition<PsiElement>(PsiElement.class) {
            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext context) {
                for (Class<? extends PsiElement> classAlternative : classAlternatives) {
                    if (classAlternative.isInstance(o)) {
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public static PsiJavaElementPattern.Capture<PsiLiteralExpression> literalExpression() {
        return literalExpression(null);
    }

    public static PsiJavaElementPattern.Capture<PsiLiteral> psiLiteral() {
        return psiLiteral(null);
    }

    public static PsiJavaElementPattern.Capture<PsiLiteral> psiLiteral(@Nullable final ElementPattern<?> value) {
        return new PsiJavaElementPattern.Capture<>(new InitialPatternConditionPlus<PsiLiteral>(PsiLiteral.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                return o instanceof PsiLiteral && (value == null || value.accepts(((PsiLiteral)o).getValue(), context));
            }

            @Override
            public List<ElementPattern<?>> getPatterns() {
                return Collections.singletonList(value);
            }
        });
    }

    public static PsiJavaElementPattern.Capture<PsiNewExpression> psiNewExpression(final String ... fqns) {
        return new PsiJavaElementPattern.Capture<>(new InitialPatternCondition<PsiNewExpression>(PsiNewExpression.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                if (o instanceof PsiNewExpression) {
                    PsiJavaCodeReferenceElement reference = ((PsiNewExpression)o).getClassOrAnonymousClassReference();
                    if (reference != null) {
                        for (String fqn : fqns) {
                            if (fqn.equals(reference.getQualifiedName())) return true;
                        }
                    }
                }
                return false;
            }
        });
    }

    public static PsiJavaElementPattern.Capture<PsiLiteralExpression> literalExpression(@Nullable final ElementPattern<?> value) {
        return new PsiJavaElementPattern.Capture<>(new InitialPatternConditionPlus<PsiLiteralExpression>(PsiLiteralExpression.class) {
            @Override
            public boolean accepts(@Nullable final Object o, final ProcessingContext context) {
                return o instanceof PsiLiteralExpression && (value == null || value.accepts(((PsiLiteralExpression)o).getValue(), context));
            }

            @Override
            public List<ElementPattern<?>> getPatterns() {
                return Collections.singletonList(value);
            }
        });
    }

    public static PsiMemberPattern.Capture psiMember() {
        return new PsiMemberPattern.Capture();
    }

    public static PsiMethodPattern psiMethod() {
        return new PsiMethodPattern();
    }

    public static PsiParameterPattern psiParameter() {
        return new PsiParameterPattern();
    }

    public static PsiModifierListOwnerPattern.Capture<PsiModifierListOwner> psiModifierListOwner() {
        return new PsiModifierListOwnerPattern.Capture<>(new InitialPatternCondition<PsiModifierListOwner>(PsiModifierListOwner.class) {
            @Override
            public boolean accepts(@Nullable Object o, ProcessingContext context) {
                return o instanceof PsiModifierListOwner;
            }
        });
    }


    public static PsiFieldPattern psiField() {
        return new PsiFieldPattern();
    }

    public static PsiClassPattern psiClass() {
        return new PsiClassPattern();
    }

    public static PsiAnnotationPattern psiAnnotation() {
        return PsiAnnotationPattern.PSI_ANNOTATION_PATTERN;
    }

    public static PsiNameValuePairPattern psiNameValuePair() {
        return PsiNameValuePairPattern.NAME_VALUE_PAIR_PATTERN;
    }

    public static PsiTypePattern psiType() {
        return new PsiTypePattern();
    }

    public static PsiExpressionPattern.Capture<PsiExpression> psiExpression() {
        return new PsiExpressionPattern.Capture<>(PsiExpression.class);
    }

    public static PsiBinaryExpressionPattern psiBinaryExpression() {
        return new PsiBinaryExpressionPattern();
    }

    public static PsiTypeCastExpressionPattern psiTypeCastExpression() {
        return new PsiTypeCastExpressionPattern();
    }

    public static PsiJavaElementPattern.Capture<PsiReferenceExpression> psiReferenceExpression() {
        return psiElement(PsiReferenceExpression.class);
    }

    public static PsiStatementPattern.Capture<PsiExpressionStatement> psiExpressionStatement() {
        return new PsiStatementPattern.Capture<>(PsiExpressionStatement.class);
    }

    public static PsiStatementPattern.Capture<PsiReturnStatement> psiReturnStatement() {
        return new PsiStatementPattern.Capture<>(PsiReturnStatement.class);
    }
}