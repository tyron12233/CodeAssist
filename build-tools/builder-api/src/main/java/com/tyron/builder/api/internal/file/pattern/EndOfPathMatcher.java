package com.tyron.builder.api.internal.file.pattern;

public class EndOfPathMatcher implements PathMatcher {
    @Override
    public String toString() {
        return "{end-of-path}";
    }

    @Override
    public int getMaxSegments() {
        return 0;
    }

    @Override
    public int getMinSegments() {
        return 0;
    }

    @Override
    public boolean matches(String[] segments, int startIndex) {
        return startIndex == segments.length;
    }

    @Override
    public boolean isPrefix(String[] segments, int startIndex) {
        return false;
    }
}