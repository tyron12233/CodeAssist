package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;
import com.tyron.psi.patterns.PatternConditionPlus;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.util.Ref;
import org.jetbrains.kotlin.com.intellij.psi.PsiClass;
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameter;
import org.jetbrains.kotlin.com.intellij.psi.PsiParameterList;
import org.jetbrains.kotlin.com.intellij.psi.PsiType;
import org.jetbrains.kotlin.com.intellij.psi.search.searches.SuperMethodsSearch;
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.kotlin.com.intellij.util.ArrayUtilRt;
import org.jetbrains.kotlin.com.intellij.util.PairProcessor;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

import java.util.Objects;

/**
 * @author peter
 */
public class PsiMethodPattern extends PsiMemberPattern<PsiMethod,PsiMethodPattern> {
    public PsiMethodPattern() {
        super(PsiMethod.class);
    }

    public PsiMethodPattern withParameterCount(@NonNls final int paramCount) {
        return with(new PatternCondition<PsiMethod>("withParameterCount") {
            @Override
            public boolean accepts(@NotNull final PsiMethod method, final ProcessingContext context) {
                return method.getParameterList().getParametersCount() == paramCount;
            }
        });
    }

    /**
     * Selects the corrected method by argument types
     * @param inputTypes the array of FQN of the parameter types or wildcards.
     * The special values are:<bl><li>"?" - means any type</li><li>".." - instructs pattern to accept the rest of the arguments</li></bl>
     * @return
     */
    public PsiMethodPattern withParameters(@NonNls final String... inputTypes) {
        final String[] types = inputTypes.length == 0 ? ArrayUtilRt.EMPTY_STRING_ARRAY : inputTypes;
        return with(new PatternCondition<PsiMethod>("withParameters") {
            @Override
            public boolean accepts(@NotNull final PsiMethod psiMethod, final ProcessingContext context) {
                final PsiParameterList parameterList = psiMethod.getParameterList();
                int dotsIndex = -1;
                while (++dotsIndex <types.length) {
                    if (Objects.equals("..", types[dotsIndex])) break;
                }

                if (dotsIndex == types.length && parameterList.getParametersCount() != dotsIndex
                        || dotsIndex < types.length && parameterList.getParametersCount() < dotsIndex) {
                    return false;
                }
                if (dotsIndex > 0) {
                    final PsiParameter[] psiParameters = parameterList.getParameters();
                    for (int i = 0; i < dotsIndex; i++) {
                        if (!Objects.equals("?", types[i]) && !typeEquivalent(psiParameters[i].getType(), types[i])) {
                            return false;
                        }
                    }
                }
                return true;
            }

            private boolean typeEquivalent(PsiType type, String expectedText) {
                PsiType erasure = TypeConversionUtil.erasure(type);
                return erasure != null && erasure.equalsToText(expectedText);
            }
        });
    }

    @NotNull
    public PsiMethodPattern definedInClass(@NonNls final String qname) {
        return definedInClass(PsiJavaPatterns.psiClass().withQualifiedName(qname));
    }

    @NotNull
    public PsiMethodPattern definedInClass(final ElementPattern<? extends PsiClass> pattern) {
        return with(new PatternConditionPlus<PsiMethod, PsiClass>("definedInClass", pattern) {

            @Override
            public boolean processValues(PsiMethod t, final ProcessingContext context, final PairProcessor<? super PsiClass, ? super ProcessingContext> processor) {
                if (!processor.process(t.getContainingClass(), context)) return false;
                final Ref<Boolean> result = Ref.create(Boolean.TRUE);
                SuperMethodsSearch.search(t, null, true, false).forEach(signature -> {
                    if (!processor.process(signature.getMethod().getContainingClass(), context)) {
                        result.set(Boolean.FALSE);
                        return false;
                    }
                    return true;
                });
                return result.get();
            }
        });
    }

    public PsiMethodPattern constructor(final boolean isConstructor) {
        return with(new PatternCondition<PsiMethod>("constructor") {
            @Override
            public boolean accepts(@NotNull final PsiMethod method, final ProcessingContext context) {
                return method.isConstructor() == isConstructor;
            }
        });
    }


    public PsiMethodPattern withThrowsList(final ElementPattern<?> pattern) {
        return with(new PatternCondition<PsiMethod>("withThrowsList") {
            @Override
            public boolean accepts(@NotNull final PsiMethod method, final ProcessingContext context) {
                return pattern.accepts(method.getThrowsList());
            }
        });
    }
}