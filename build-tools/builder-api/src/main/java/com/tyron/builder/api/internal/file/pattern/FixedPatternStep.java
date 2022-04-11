package com.tyron.builder.api.internal.file.pattern;

/**
 * A pattern step for a fixed pattern segment that does not contain any wildcards.
 */
public class FixedPatternStep implements PatternStep {
    private final String value;
    private final boolean caseSensitive;

    public FixedPatternStep(String value, boolean caseSensitive) {
        this.value = value;
        this.caseSensitive = caseSensitive;
    }

    @Override
    public String toString() {
        return "{match: " + value + "}";
    }

    @Override
    public boolean matches(String candidate) {
        return caseSensitive ? candidate.equals(value) : candidate.equalsIgnoreCase(value);
    }
}