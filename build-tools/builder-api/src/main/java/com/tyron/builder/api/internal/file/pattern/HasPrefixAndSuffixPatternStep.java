package com.tyron.builder.api.internal.file.pattern;

public class HasPrefixAndSuffixPatternStep implements PatternStep {
    private final HasPrefixPatternStep prefixMatch;
    private final HasSuffixPatternStep suffixMatch;

    public HasPrefixAndSuffixPatternStep(String prefix, String suffix, boolean caseSensitive) {
        prefixMatch = new HasPrefixPatternStep(prefix, caseSensitive);
        suffixMatch = new HasSuffixPatternStep(suffix, caseSensitive, prefix.length());
    }

    @Override
    public String toString() {
        return "{prefix: " + prefixMatch + " suffix: " + suffixMatch + "}";
    }

    @Override
    public boolean matches(String candidate) {
        return prefixMatch.matches(candidate) && suffixMatch.matches(candidate);
    }
}