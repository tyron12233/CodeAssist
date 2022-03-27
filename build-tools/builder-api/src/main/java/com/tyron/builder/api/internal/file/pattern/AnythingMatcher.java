package com.tyron.builder.api.internal.file.pattern;

public class AnythingMatcher implements PathMatcher {
    @Override
    public String toString() {
        return "{anything}";
    }

    @Override
    public int getMaxSegments() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMinSegments() {
        return 0;
    }

    @Override
    public boolean matches(String[] segments, int startIndex) {
        return true;
    }

    @Override
    public boolean isPrefix(String[] segments, int startIndex) {
        return true;
    }
}