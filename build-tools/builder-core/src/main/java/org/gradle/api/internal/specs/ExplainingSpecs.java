package org.gradle.api.internal.specs;

import org.gradle.internal.Cast;

public class ExplainingSpecs {

    private static final ExplainingSpec<Object> SATISFIES_ALL = new ExplainingSpec<Object>() {
        @Override
        public boolean isSatisfiedBy(Object element) {
            return true;
        }
        @Override
        public String whyUnsatisfied(Object element) {
            return null;
        }
    };

    public static <T> ExplainingSpec<T> satisfyAll() {
        return Cast.uncheckedNonnullCast(SATISFIES_ALL);
    }

    private static final ExplainingSpec<Object> SATISFIES_NONE = new ExplainingSpec<Object>() {
        @Override
        public boolean isSatisfiedBy(Object element) {
            return false;
        }
        @Override
        public String whyUnsatisfied(Object element) {
            return "Never satisfies any.";
        }
    };

    public static <T> ExplainingSpec<T> satisfyNone() {
        return Cast.uncheckedNonnullCast(SATISFIES_NONE);
    }
}
