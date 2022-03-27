package com.tyron.builder.api.internal.file.pattern;

public class AnyWildcardPatternStep implements PatternStep {
    @Override
    public boolean matches(String candidate) {
        return false;
    }
}
