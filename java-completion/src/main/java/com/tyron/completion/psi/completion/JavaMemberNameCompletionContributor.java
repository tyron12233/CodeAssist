package com.tyron.completion.psi.completion;
import static org.jetbrains.kotlin.com.intellij.patterns.PsiJavaPatterns.psiElement;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.patterns.ElementPattern;
import org.jetbrains.kotlin.com.intellij.patterns.PatternCondition;
import org.jetbrains.kotlin.com.intellij.patterns.StandardPatterns;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotation;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import java.util.Arrays;

public class JavaMemberNameCompletionContributor {

    public static final ElementPattern<PsiElement> INSIDE_TYPE_PARAMS_PATTERN = psiElement().
            afterLeaf(psiElement().withText("?").andOr(
            psiElement().afterLeaf(psiElement().withText(StandardPatterns.string().oneOf("<", ","))),
            psiElement().with(new PatternCondition<PsiElement>("afterSiblingSkipping") {
                @Override
                public boolean accepts(@NotNull PsiElement psiElement,
                                       ProcessingContext processingContext) {
                    PsiElement parent = psiElement.getParent();
                    if (parent == null) return false;

                    PsiElement[] children = parent.getChildren();
                    int i = Arrays.asList(children).indexOf(parent);

                    ElementPattern<?> skip = psiElement().whitespaceCommentEmptyOrError();
                    ElementPattern<?> pattern =  psiElement(PsiAnnotation.class);

                    while (--i >= 0) {
                        if (!skip.accepts(children[i], processingContext)) {
                            return pattern.accepts(children[i], processingContext);
                        }
                    }

                    return false;
                }
            })));
}
