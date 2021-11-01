package com.tyron.psi.completions.lang.java;

import static com.tyron.psi.completions.lang.java.patterns.PsiJavaPatterns.psiElement;
import static com.tyron.psi.patterns.StandardPatterns.or;

import com.tyron.psi.completion.CompletionContributor;
import com.tyron.psi.completion.CompletionParameters;
import com.tyron.psi.completion.CompletionType;
import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.filter.ElementExtractorFilter;
import com.tyron.psi.completions.lang.java.filter.getters.ExpectedTypesGetter;
import com.tyron.psi.completions.lang.java.filter.types.AssignableFromFilter;
import com.tyron.psi.completions.lang.java.scope.JavaCompletionProcessor;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.tailtype.TailType;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.psi.*;
import org.jetbrains.kotlin.com.intellij.psi.filters.ElementFilter;
import org.jetbrains.kotlin.com.intellij.psi.filters.OrFilter;
import org.jetbrains.kotlin.com.intellij.psi.infos.CandidateInfo;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.util.ReflectionUtil;
import org.jetbrains.kotlin.com.intellij.util.SmartList;
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import gnu.trove.TObjectHashingStrategy;

public class JavaSmartCompletionContributor {
    static final TObjectHashingStrategy<ExpectedTypeInfo> EXPECTED_TYPE_INFO_STRATEGY = new TObjectHashingStrategy<ExpectedTypeInfo>() {
        @Override
        public int computeHashCode(ExpectedTypeInfo object) {
            return object.getType().hashCode();
        }

        @Override
        public boolean equals(ExpectedTypeInfo o1, ExpectedTypeInfo o2) {
            return o1.getType().equals(o2.getType());
        }
    };

    private static final ElementExtractorFilter THROWABLES_FILTER = new ElementExtractorFilter(new AssignableFromFilter(CommonClassNames.JAVA_LANG_THROWABLE));
    static final ElementPattern<PsiElement> AFTER_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW));
    static final ElementPattern<PsiElement> AFTER_THROW_NEW = psiElement().afterLeaf(psiElement().withText(PsiKeyword.NEW).afterLeaf(PsiKeyword.THROW));
    public static final ElementPattern<PsiElement> INSIDE_EXPRESSION = or(
            psiElement().withParent(PsiExpression.class)
                    .andNot(psiElement().withParent(PsiLiteralExpression.class))
                    .andNot(psiElement().withParent(PsiMethodReferenceExpression.class)),
            psiElement().inside(PsiClassObjectAccessExpression.class),
            psiElement().inside(PsiThisExpression.class),
            psiElement().inside(PsiSuperExpression.class));
    static final ElementPattern<PsiElement> INSIDE_TYPECAST_EXPRESSION = psiElement().withParent(
            psiElement(PsiReferenceExpression.class).afterLeaf(psiElement().withText(")").withParent(PsiTypeCastExpression.class)));

    @Nullable
    private static ElementFilter getClassReferenceFilter(final PsiElement element, final boolean inRefList) {
        //throw new foo
        if (AFTER_THROW_NEW.accepts(element)) {
            return THROWABLES_FILTER;
        }

        //new xxx.yyy
        if (psiElement().afterLeaf(psiElement().withText(".")).withSuperParent(2, psiElement(PsiNewExpression.class)).accepts(element)) {
            if (((PsiNewExpression) element.getParent().getParent()).getClassReference() == element.getParent()) {
                PsiType[] types = ExpectedTypesGetter.getExpectedTypes(element, false);
                return new OrFilter(ContainerUtil.map2Array(types, ElementFilter.class, type -> new AssignableFromFilter(type)));
            }
        }

        return null;
    }

    public static ExpectedTypeInfo [] getExpectedTypes(final CompletionParameters parameters) {
        return getExpectedTypes(parameters.getPosition(), parameters.getCompletionType() == CompletionType.SMART);
    }

    public static ExpectedTypeInfo[] getExpectedTypes(PsiElement position, boolean voidable) {
        if (psiElement().withParent(psiElement(PsiReferenceExpression.class).withParent(PsiThrowStatement.class)).accepts(position)) {
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

    public static SmartCompletionDecorator decorate(LookupElement lookupElement, Collection<? extends ExpectedTypeInfo> infos) {
        return new SmartCompletionDecorator(lookupElement, infos);
    }

    static Set<LookupElement> completeReference(final PsiElement element,
                                                PsiJavaCodeReferenceElement reference,
                                                final ElementFilter filter,
                                                final boolean acceptClasses,
                                                final boolean acceptMembers,
                                                CompletionParameters parameters, final PrefixMatcher matcher) {
        ElementFilter checkClass = new ElementFilter() {
            @Override
            public boolean isAcceptable(Object element, PsiElement context) {
                return filter.isAcceptable(element, context);
            }

            @Override
            public boolean isClassAcceptable(Class hintClass) {
                if (ReflectionUtil.isAssignable(PsiClass.class, hintClass)) {
                    return acceptClasses;
                }

                if (ReflectionUtil.isAssignable(PsiVariable.class, hintClass) ||
                        ReflectionUtil.isAssignable(PsiMethod.class, hintClass) ||
                        ReflectionUtil.isAssignable(CandidateInfo.class, hintClass)) {
                    return acceptMembers;
                }
                return false;
            }
        };
        JavaCompletionProcessor.Options options =
                JavaCompletionProcessor.Options.DEFAULT_OPTIONS.withFilterStaticAfterInstance(parameters.getInvocationCount() <= 1);
        return JavaCompletionUtil.processJavaReference(element, reference, checkClass, options, matcher::prefixMatches, parameters);
    }

}
