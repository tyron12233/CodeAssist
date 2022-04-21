package com.tyron.builder.problems;

/**
 * A solution is attached to a {@link Problem}. It should provide helpful
 * suggestions as to how to fix it.
 */
public interface Solution extends WithDescription, WithDocumentationLink {
    static Solution of(String simpleDescription) {
        return new BaseSolution(() -> simpleDescription, () -> null, () -> null);
    }
}