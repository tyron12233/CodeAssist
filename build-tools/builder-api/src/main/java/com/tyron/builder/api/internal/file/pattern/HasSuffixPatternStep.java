package com.tyron.builder.api.internal.file.pattern;

/**
 * A pattern step for a pattern segment a the common case with a '*' prefix on the pattern. e.g. '*.java'
 */
public class HasSuffixPatternStep implements PatternStep {
    private final String suffix;
    private final boolean caseSensitive;
    private final int suffixLength;
    private final int prefixLength;

    public HasSuffixPatternStep(String suffix, boolean caseSensitive) {
        this(suffix, caseSensitive, 0);
    }

    // Used by HasPrefixAndSuffixPatternStep to ensure the suffix isn't matching any part of the prefix.
    HasSuffixPatternStep(String suffix, boolean caseSensitive, int prefixLength) {
        this.suffix = suffix;
        suffixLength = suffix.length();
        this.caseSensitive = caseSensitive;
        this.prefixLength = prefixLength;
    }

    @Override
    public String toString() {
        return "{suffix: " + suffix + "}";
    }

    @Override
    public boolean matches(String candidate) {
        return isLongEnough(candidate) && candidate.regionMatches(!caseSensitive, candidate.length() - suffixLength, suffix, 0, suffixLength);
    }

    // Confirms there is enough space in candidate to fit both suffix and prefix.
    private boolean isLongEnough(String candidate) {
        return prefixLength + suffixLength <= candidate.length();
    }
}