package com.tyron.builder.api.internal.file.pattern;

/**
 * A pattern step for a pattern segment a the common case with a '*' suffix on the pattern. e.g. '._*'
 */
public class HasPrefixPatternStep implements PatternStep {
    private final String prefix;
    private final boolean caseSensitive;
    private final int prefixLength;

    public HasPrefixPatternStep(String prefix, boolean caseSensitive) {
        this.prefix = prefix;
        prefixLength = prefix.length();
        this.caseSensitive = caseSensitive;
    }

    @Override
    public String toString() {
        return "{prefix: " + prefix + "}";
    }

    @Override
    public boolean matches(String candidate) {
        return candidate.regionMatches(!caseSensitive, 0, prefix, 0, prefixLength);
    }
}