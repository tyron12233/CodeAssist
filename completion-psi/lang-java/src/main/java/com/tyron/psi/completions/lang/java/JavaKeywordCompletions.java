package com.tyron.psi.completions.lang.java;

import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiAnnotation;
import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiClass;
import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiElement;
import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiReferenceExpression;
import static com.tyron.psi.patterns.StandardPatterns.and;
import static com.tyron.psi.patterns.StandardPatterns.or;
import static com.tyron.psi.patterns.StandardPatterns.string;

import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PsiElementPattern;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.JavaCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiAnnotationParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiDeclarationStatement;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpressionList;
import org.jetbrains.kotlin.com.intellij.psi.PsiFile;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiRecordComponent;
import org.jetbrains.kotlin.com.intellij.psi.PsiRecordHeader;
import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchBlock;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCodeFragment;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeParameter;
import org.jetbrains.kotlin.com.intellij.psi.codeStyle.CodeStyleManager;
import org.jetbrains.kotlin.com.intellij.psi.javadoc.PsiDocComment;
import org.jetbrains.kotlin.com.intellij.psi.templateLanguages.OuterLanguageElement;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class JavaKeywordCompletions<List> {
    public static final ElementPattern<PsiElement> AFTER_DOT = psiElement().afterLeaf(".");

    static final ElementPattern<PsiElement> VARIABLE_AFTER_FINAL = psiElement().afterLeaf(PsiKeyword.FINAL).inside(PsiDeclarationStatement.class);

    private static final ElementPattern<PsiElement> INSIDE_PARAMETER_LIST =
            psiElement().withParent(
                    psiElement(PsiJavaCodeReferenceElement.class).insideStarting(
                            psiElement().withTreeParent(
                                    psiElement(PsiParameterList.class).andNot(psiElement(PsiAnnotationParameterList.class)))));
    private static final ElementPattern<PsiElement> INSIDE_RECORD_HEADER =
            psiElement().withParent(
                    psiElement(PsiJavaCodeReferenceElement.class).insideStarting(
                            or(
                                    psiElement().withTreeParent(
                                            psiElement(PsiRecordComponent.class)),
                                    psiElement().withTreeParent(
                                            psiElement(PsiRecordHeader.class)
                                    )
                            )
                    ));


    private static boolean isStatementCodeFragment(PsiFile file) {
        return file instanceof JavaCodeFragment &&
                !(file instanceof PsiExpressionCodeFragment ||
                        file instanceof PsiJavaCodeReferenceCodeFragment ||
                        file instanceof PsiTypeCodeFragment);
    }

    static boolean isEndOfBlock(@NotNull PsiElement element) {
        PsiElement prev = prevSignificantLeaf(element);
        if (prev == null) {
            PsiFile file = element.getContainingFile();
            return !(file instanceof PsiCodeFragment) || isStatementCodeFragment(file);
        }

        if (psiElement().inside(psiAnnotation()).accepts(prev)) return false;

        if (prev instanceof OuterLanguageElement) return true;
        if (psiElement().withText(string().oneOf("{", "}", ";", ":", "else")).accepts(prev)) return true;
        if (prev.textMatches(")")) {
            PsiElement parent = prev.getParent();
            if (parent instanceof PsiParameterList) {
                return PsiTreeUtil.getParentOfType(PsiTreeUtil.prevVisibleLeaf(element), PsiDocComment.class) != null;
            }

            return !(parent instanceof PsiExpressionList || parent instanceof PsiTypeCastExpression
                    || parent instanceof PsiRecordHeader);
        }

        return false;
    }

    static final ElementPattern<PsiElement> START_SWITCH =
            psiElement().afterLeaf(psiElement().withText("{").withParents(PsiCodeBlock.class, PsiSwitchBlock.class));

//    private static final ElementPattern<PsiElement> SUPER_OR_THIS_PATTERN =
//            and(JavaSmartCompletionContributor.INSIDE_EXPRESSION,
//                    not(psiElement().afterLeaf(PsiKeyword.CASE)),
//                    not(psiElement().afterLeaf(psiElement().withText(".").afterLeaf(PsiKeyword.THIS, PsiKeyword.SUPER))),
//                    not(psiElement().inside(PsiAnnotation.class)),
//                    not(START_SWITCH),
//                    not(JavaMemberNameCompletionContributor.INSIDE_TYPE_PARAMS_PATTERN));

    static final Set<String> PRIMITIVE_TYPES = new LinkedHashSet<String>() {{
        add( PsiKeyword.SHORT);
        add(PsiKeyword.BOOLEAN);
        add( PsiKeyword.DOUBLE);
        add(PsiKeyword.LONG);
        add(PsiKeyword.INT);
        add(PsiKeyword.FLOAT);
        add( PsiKeyword.CHAR);
        add(PsiKeyword.BYTE);
    }};


    static final PsiElementPattern<PsiElement,?> START_FOR = psiElement().afterLeaf(psiElement().withText("(").afterLeaf("for"));
    private static final ElementPattern<PsiElement> CLASS_REFERENCE =
            psiElement().withParent(psiReferenceExpression().referencing(psiClass().andNot(psiElement(PsiTypeParameter.class))));

    private final CompletionParameters myParameters;
    private final JavaCompletionSession mySession;
    private final PsiElement myPosition;
    private final PrefixMatcher myKeywordMatcher;
    private final List<LookupElement> myResults = new ArrayList<>();
    private final PsiElement myPrevLeaf;
}
