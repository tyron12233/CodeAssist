package com.tyron.builder.api.internal.file.pattern;

public class GreedyPathMatcher implements PathMatcher {
    private final PathMatcher next;

    public GreedyPathMatcher(PathMatcher next) {
        this.next = next;
    }

    @Override
    public String toString() {
        return "{greedy next: " + next + "}";
    }

    @Override
    public int getMaxSegments() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMinSegments() {
        return next.getMinSegments();
    }

    @Override
    public boolean matches(String[] segments, int startIndex) {
        int pos = segments.length - next.getMinSegments();
        int minPos = Math.max(startIndex, segments.length - next.getMaxSegments());
        for (; pos >= minPos; pos--) {
            if (next.matches(segments, pos)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isPrefix(String[] segments, int startIndex) {
        return true;
    }
}