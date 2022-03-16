package com.tyron.completion.java.patterns;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.PsiElementPattern;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayInitializerMemberValue;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

public final class PsiAnnotationPattern extends PsiElementPattern<PsiAnnotation, PsiAnnotationPattern> {
    public static final PsiAnnotationPattern PSI_ANNOTATION_PATTERN = new PsiAnnotationPattern();

    private PsiAnnotationPattern() {
        super(PsiAnnotation.class);
    }

    public PsiAnnotationPattern qName(final ElementPattern<String> pattern) {
        return with(new PatternCondition<PsiAnnotation>("qName") {
            @Override
            public boolean accepts(@NotNull final PsiAnnotation psiAnnotation, final ProcessingContext context) {
                return pattern.accepts(psiAnnotation.getQualifiedName(), context);
            }
        });
    }
    public PsiAnnotationPattern qName(@NonNls final String qname) {
        return with(new PatternCondition<PsiAnnotation>("qName") {
            @Override
            public boolean accepts(@NotNull final PsiAnnotation psiAnnotation, final ProcessingContext context) {
                return psiAnnotation.hasQualifiedName(qname);
            }
        });
    }

    public PsiAnnotationPattern insideAnnotationAttribute(@NotNull final String attributeName, @NotNull final ElementPattern<? extends PsiAnnotation> parentAnnoPattern) {
        return with(new PatternCondition<PsiAnnotation>("insideAnnotationAttribute") {
            final PsiNameValuePairPattern attrPattern = PsiNameValuePairPattern.NAME_VALUE_PAIR_PATTERN
                    .withName(attributeName).withSuperParent(2, parentAnnoPattern);

            @Override
            public boolean accepts(@NotNull PsiAnnotation annotation, ProcessingContext context) {
                PsiElement attr = getParentElement(annotation);
                if (attr instanceof PsiArrayInitializerMemberValue) attr = getParentElement(attr);
                return attrPattern.accepts(attr);
            }
        });
    }

    private PsiElement getParentElement(@NotNull PsiElement element) {
        return getParent(element);
    }
}