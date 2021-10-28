package com.tyron.psi.completions.lang.java.patterns;

import com.tyron.psi.patterns.ElementPattern;
import com.tyron.psi.patterns.PatternCondition;
import com.tyron.psi.patterns.PsiElementPattern;
import com.tyron.psi.patterns.PsiNamePatternCondition;
import com.tyron.psi.util.StringUtil;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.psi.PsiNameValuePair;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public final class PsiNameValuePairPattern extends PsiElementPattern<PsiNameValuePair, PsiNameValuePairPattern> {
    static final PsiNameValuePairPattern NAME_VALUE_PAIR_PATTERN = new PsiNameValuePairPattern();

    private PsiNameValuePairPattern() {
        super(PsiNameValuePair.class);
    }

    @Override
    @NotNull
    public PsiNameValuePairPattern withName(@NotNull @NonNls final String requiredName) {
        return with(new PatternCondition<PsiNameValuePair>("withName") {
            @Override
            public boolean accepts(@NotNull final PsiNameValuePair psiNameValuePair, final ProcessingContext context) {
                String actualName = psiNameValuePair.getName();
                return requiredName.equals(actualName) || actualName == null && "value".equals(requiredName);
            }
        });
    }

    @NotNull
    @Override
    public PsiNameValuePairPattern withName(@NotNull final ElementPattern<String> name) {
        return with(new PsiNamePatternCondition<PsiNameValuePair>("withName", name) {
            @Override
            public String getPropertyValue(@NotNull Object o) {
                if (o instanceof PsiNameValuePair) {
                    final String nameValue = ((PsiNameValuePair)o).getName();
                    return StringUtil.notNullize(nameValue, "value");
                }
                return null;
            }
        });
    }
}