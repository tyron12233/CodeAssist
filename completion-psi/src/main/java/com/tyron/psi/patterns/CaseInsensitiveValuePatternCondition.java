package com.tyron.psi.patterns;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.util.ProcessingContext;

/**
 * @author peter
 */
public class CaseInsensitiveValuePatternCondition extends PatternCondition<String> {
    private final String[] myValues;

    public CaseInsensitiveValuePatternCondition(String methodName, final String... values) {
        super(methodName);
        myValues = values;
    }

    public String[] getValues() {
        return myValues;
    }

    @Override
    public boolean accepts(@NotNull final String str, final ProcessingContext context) {
        for (final String value : myValues) {
            if (str.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

}