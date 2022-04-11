package com.tyron.builder.api.internal.file.pattern;

public interface PathMatcher {
    /**
     * Returns the minimum number of segments a path must have to satisfy this matcher.
     */
    int getMinSegments();

    /**
     * Returns the maximum number of segments a path must have to satisfy this matcher.
     */
    int getMaxSegments();

    /**
     * Returns true if the path starting at the given offset satisfies this pattern.
     */
    boolean matches(String[] segments, int startIndex);

    /**
     * Returns true if the path starting at the given offset could be satisfy this pattern if it contained additional segments at the end.
     */
    boolean isPrefix(String[] segments, int startIndex);
}