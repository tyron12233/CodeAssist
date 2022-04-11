package com.tyron.builder.api.tasks.util.internal;

import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.tasks.util.PatternSet;

public class PatternSets {
    private static final Factory<PatternSet> PATTERN_SET_FACTORY = new PatternSetFactory(PatternSpecFactory.INSTANCE);

    /**
     * Should use as an injected service instead.
     * @deprecated
     */
    @Deprecated
    public static Factory<PatternSet> getNonCachingPatternSetFactory() {
        return PATTERN_SET_FACTORY;
    }

    public static Factory<PatternSet> getPatternSetFactory(PatternSpecFactory patternSpecFactory) {
        return new PatternSetFactory(patternSpecFactory);
    }

    private static final class PatternSetFactory implements Factory<PatternSet> {
        private final PatternSpecFactory patternSpecFactory;

        private PatternSetFactory(PatternSpecFactory patternSpecFactory) {
            this.patternSpecFactory = patternSpecFactory;
        }

        @Override
        public PatternSet create() {
            return new InternalPatternSet(patternSpecFactory);
        }
    }

    // This is only required to avoid adding a new public constructor to the public `PatternSet` type.
    private static class InternalPatternSet extends PatternSet {
        public InternalPatternSet(PatternSpecFactory patternSpecFactory) {
            super(patternSpecFactory);
        }
    }

}