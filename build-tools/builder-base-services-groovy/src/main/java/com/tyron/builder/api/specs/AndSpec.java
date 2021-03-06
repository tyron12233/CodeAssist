package com.tyron.builder.api.specs;

import com.google.common.collect.ObjectArrays;
import groovy.lang.Closure;

import com.tyron.builder.api.specs.internal.ClosureSpec;
import com.tyron.builder.internal.Cast;

/**
 * A {@link com.tyron.builder.api.specs.CompositeSpec} which requires all its specs to be true in order to evaluate to true.
 * Uses lazy evaluation.
 *
 * @param <T> The target type for this Spec
 */
public class AndSpec<T> extends CompositeSpec<T> {
    public static final AndSpec<?> EMPTY = new AndSpec<>();

    public AndSpec() {
        super();
    }

    @SafeVarargs
    @SuppressWarnings("varargs")
    public AndSpec(Spec<? super T>... specs) {
        super(specs);
    }

    public AndSpec(Iterable<? extends Spec<? super T>> specs) {
        super(specs);
    }

    @Override
    public boolean isSatisfiedBy(T object) {
        Spec<? super T>[] specs = getSpecsArray();
        for (Spec<? super T> spec : specs) {
            if (!spec.isSatisfiedBy(object)) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("varargs")
    public AndSpec<T> and(Spec<? super T>... specs) {
        if (specs.length == 0) {
            return this;
        }
        Spec<? super T>[] thisSpecs = getSpecsArray();
        int thisLength = thisSpecs.length;
        if (thisLength == 0) {
            return new AndSpec<T>(specs);
        }
        Spec<? super T>[] combinedSpecs = uncheckedCast(ObjectArrays.newArray(Spec.class, thisLength + specs.length));
        System.arraycopy(thisSpecs, 0, combinedSpecs, 0, thisLength);
        System.arraycopy(specs, 0, combinedSpecs, thisLength, specs.length);
        return new AndSpec<T>(combinedSpecs);
    }

    /**
     * Typed and() method for a single {@link Spec}.
     *
     * @since 4.3
     */
    public AndSpec<T> and(Spec<? super T> spec) {
        return and(Cast.<Spec<? super T>[]>uncheckedNonnullCast(new Spec<?>[]{spec}));
    }

    @SuppressWarnings("rawtypes")
    public AndSpec<T> and(Closure spec) {
        return and(new ClosureSpec<>(spec));
    }

    public static <T> AndSpec<T> empty() {
        return uncheckedCast(EMPTY);
    }

}
