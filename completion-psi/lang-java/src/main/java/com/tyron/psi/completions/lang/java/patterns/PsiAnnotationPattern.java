package com.tyron.psi.completions.lang.java.patterns;

import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiNameValuePair;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiArrayInitializerMemberValue;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;
import com.tyron.psi.patterns.PsiElementPattern;

/**
 * @author peter
 */
public final class PsiAnnotationPattern extends PsiElementPattern<PsiAnnotation, PsiAnnotationPattern> {
    static final PsiAnnotationPattern PSI_ANNOTATION_PATTERN = new PsiAnnotationPattern();

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
            final PsiNameValuePairPattern attrPattern = psiNameValuePair().withName(attributeName).withSuperParent(2, parentAnnoPattern);

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