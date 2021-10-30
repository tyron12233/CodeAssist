package com.tyron.psi.completions.lang.java;

import com.tyron.psi.completion.PrefixMatcher;
import com.tyron.psi.completions.lang.java.filter.getters.ClassLiteralGetter;
import com.tyron.psi.completions.lang.java.guess.GuessManager;
import com.tyron.psi.completions.lang.java.lookup.KeywordLookupItem;
import com.tyron.psi.lookup.LookupElement;
import com.tyron.psi.lookup.LookupElementBuilder;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.kotlin.com.intellij.psi.JavaPsiFacade;
import org.jetbrains.kotlin.com.intellij.psi.PsiElement;
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiKeyword;
import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiSubstitutor;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.PsiTypeCastExpression;
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable;
import org.jetbrains.kotlin.com.intellij.psi.ResolveState;
import org.jetbrains.kotlin.com.intellij.psi.scope.PsiScopeProcessor;
import org.jetbrains.kotlin.com.intellij.psi.scope.util.PsiScopesUtil;
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.kotlin.com.intellij.util.Consumer;
import org.jetbrains.kotlin.com.intellij.util.containers.MultiMap;

/**
 * @author peter
 */
public final class BasicExpressionCompletionContributor {

    private static void addKeyword(final Consumer<? super LookupElement> result, final PsiElement element, final String s) {
        result.consume(createKeywordLookupItem(element, s));
    }

    public static LookupElement createKeywordLookupItem(final PsiElement element, final String s) {
        return new KeywordLookupItem(JavaPsiFacade.getElementFactory(element.getProject()).createKeyword(s, element), element);
    }

    public static void fillCompletionVariants(JavaSmartCompletionParameters parameters,
                                              final Consumer<? super LookupElement> result,
                                              PrefixMatcher matcher) {
        final PsiElement element = parameters.getPosition();
        if (JavaKeywordCompletion.isAfterTypeDot(element)) {
            addKeyword(result, element, PsiKeyword.CLASS);
            addKeyword(result, element, PsiKeyword.THIS);

        }

        if (!JavaKeywordCompletion.AFTER_DOT.accepts(element)) {
            if (parameters.getParameters().getInvocationCount() <= 1) {
                new CollectionsUtilityMethodsProvider(parameters.getPosition(),
                        parameters.getExpectedType(),
                        parameters.getDefaultType(), result)
                        .addCompletions(StringUtil.isNotEmpty(matcher.getPrefix()));
            }
            ClassLiteralGetter.addCompletions(parameters, result, matcher);

            final PsiElement position = parameters.getPosition();
            final PsiType expectedType = parameters.getExpectedType();

//            for (final TemplateImpl template : TemplateSettings.getInstance().getTemplates()) {
//                if (!template.isDeactivated() && template.getTemplateContext().isEnabled(new SmartCompletionContextType())) {
//                    result.consume(new SmartCompletionTemplateItem(template, position));
//                }
//            }

            addKeyword(result, position, PsiKeyword.TRUE);
            addKeyword(result, position, PsiKeyword.FALSE);
//
//            if (!JavaCompletionContributor.IN_SWITCH_LABEL.accepts(position)) {
//                for (PsiExpression expression : ThisGetter.getThisExpressionVariants(position)) {
//                    result.consume(new ExpressionLookupItem(expression));
//                }
//            }

            processDataflowExpressionTypes(parameters, expectedType, matcher, result);
        }

    }

    static void processDataflowExpressionTypes(JavaSmartCompletionParameters parameters, @Nullable PsiType expectedType, final PrefixMatcher matcher, Consumer<? super LookupElement> consumer) {
        final PsiExpression context = PsiTreeUtil.getParentOfType(parameters.getPosition(), PsiExpression.class);
        if (context == null) return;

        MultiMap<PsiExpression, PsiType> map = GuessManager.getInstance(context.getProject()).getControlFlowExpressionTypes(context, parameters.getParameters().getInvocationCount() > 1);
        if (map.entrySet().isEmpty()) {
            return;
        }

        PsiScopesUtil.treeWalkUp(new PsiScopeProcessor() {
            @Override
            public boolean execute(@NotNull PsiElement element, @NotNull ResolveState state) {
                if (element instanceof PsiLocalVariable) {
                    if (!matcher.prefixMatches(((PsiLocalVariable)element).getName())) {
                        return true;
                    }

                    final PsiExpression expression = ((PsiLocalVariable)element).getInitializer();
                    if (expression instanceof PsiTypeCastExpression) {
                        PsiTypeCastExpression typeCastExpression = (PsiTypeCastExpression)expression;
                        final PsiExpression operand = typeCastExpression.getOperand();
                        if (operand != null) {
                            if (map.get(operand).contains(typeCastExpression.getType())) {
                                map.remove(operand, typeCastExpression.getType());
                            }
                        }
                    }
                }
                return true;
            }
        }, context, context.getContainingFile());

        for (PsiExpression expression : map.keySet()) {
            for (PsiType castType : map.get(expression)) {
                PsiType baseType = expression.getType();
                if (expectedType == null || (expectedType.isAssignableFrom(castType) && (baseType == null || !expectedType.isAssignableFrom(baseType)))) {
                    consumer.consume(expressionToLookupElement(expression));
//                    consumer.consume(CastingLookupElementDecorator.createCastingElement(expressionToLookupElement(expression), castType));
                }
            }
        }
    }

    @NotNull
    private static LookupElement expressionToLookupElement(@NotNull PsiExpression expression) {
        if (expression instanceof PsiReferenceExpression) {
            final PsiReferenceExpression refExpr = (PsiReferenceExpression)expression;
            if (!refExpr.isQualified()) {
                final PsiElement target = refExpr.resolve();
//                if (target instanceof PsiVariable) {
//                    final VariableLookupItem item = new VariableLookupItem((PsiVariable)target);
//                    item.setSubstitutor(PsiSubstitutor.EMPTY);
//                    return item;
//                }
            }
        }
        if (expression instanceof PsiMethodCallExpression) {
            final PsiMethodCallExpression call = (PsiMethodCallExpression)expression;
            if (!call.getMethodExpression().isQualified()) {
                final PsiMethod method = call.resolveMethod();
                if (method != null) {
                    return LookupElementBuilder.create(method);
//                    return new JavaMethodCallElement(method);
                }
            }
        }
        return LookupElementBuilder.create(expression);
//        return new ExpressionLookupItem(expression);
    }

}