package com.tyron.builder.api.internal.file.pattern;

import com.google.common.collect.Lists;

import java.util.List;

public abstract class PatternMatcher {
    public static final PatternMatcher MATCH_ALL = new PatternMatcher() {
        @Override
        public boolean test(String[] segments, boolean isFile) {
            return true;
        }

        @Override
        public PatternMatcher and(PatternMatcher other) {
            return other;
        }

        @Override
        public PatternMatcher or(PatternMatcher other) {
            return this;
        }
    };

    public abstract boolean test(String[] segments, boolean isFile);

    public PatternMatcher and(final PatternMatcher other) {
        return new And(PatternMatcher.this, other);
    }

    public PatternMatcher or(final PatternMatcher other) {
        return new Or(PatternMatcher.this, other);
    }

    public PatternMatcher negate() {
        return new PatternMatcher() {
            @Override
            public boolean test(String[] segments, boolean isFile) {
                return !PatternMatcher.this.test(segments, isFile);
            }
        };
    }

    private static final class Or extends PatternMatcher {
        private final List<PatternMatcher> parts = Lists.newLinkedList();

        public Or(PatternMatcher patternMatcher, PatternMatcher other) {
            parts.add(patternMatcher);
            parts.add(other);
        }

        @Override
        public PatternMatcher or(PatternMatcher other) {
            parts.add(other);
            return this;
        }

        @Override
        public boolean test(String[] segments, boolean isFile) {
            for (PatternMatcher part : parts) {
                if (part.test(segments, isFile)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class And extends PatternMatcher {
        private final List<PatternMatcher> parts = Lists.newLinkedList();

        public And(PatternMatcher patternMatcher, PatternMatcher other) {
            parts.add(patternMatcher);
            parts.add(other);
        }

        @Override
        public PatternMatcher and(PatternMatcher other) {
            parts.add(other);
            return this;
        }

        @Override
        public boolean test(String[] segments, boolean isFile) {
            for (PatternMatcher part : parts) {
                if (!part.test(segments, isFile)) {
                    return false;
                }
            }
            return true;
        }
    }
}