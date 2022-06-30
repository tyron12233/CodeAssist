package com.tyron.builder.api.specs;

import com.google.common.collect.ObjectArrays;

/**
 * A {@link CompositeSpec} which requires any one of its specs to be true in order to evaluate to
 * true. Uses lazy evaluation.
 *
 * @param <T> The target type for this Spec
 */
public class OrSpec<T> extends CompositeSpec<T> {
    public static final OrSpec<?> EMPTY = new OrSpec<Object>();

    public OrSpec() {
        super();
    }

    public OrSpec(Spec<? super T>... specs) {
        super(specs);
    }

    public OrSpec(Iterable<? extends Spec<? super T>> specs) {
        super(specs);
    }

    @Override
    public boolean isSatisfiedBy(T object) {
        Spec<? super T>[] specs = getSpecsArray();
        if (specs.length == 0) {
            return true;
        }
        for (Spec<? super T> spec : specs) {
            if (spec.isSatisfiedBy(object)) {
                return true;
            }
        }
        return false;
    }

    public OrSpec<T> or(Spec<? super T>... specs) {
        if (specs.length == 0) {
            return this;
        }
        Spec<? super T>[] thisSpecs = getSpecsArray();
        int thisLength = thisSpecs.length;
        if (thisLength == 0) {
            return new OrSpec<T>(specs);
        }
        Spec<? super T>[] combinedSpecs = uncheckedCast(ObjectArrays.newArray(Spec.class, thisLength + specs.length));
        System.arraycopy(thisSpecs, 0, combinedSpecs, 0, thisLength);
        System.arraycopy(specs, 0, combinedSpecs, thisLength, specs.length);
        return new OrSpec<T>(combinedSpecs);
    }

    public static <T> OrSpec<T> empty() {
        return uncheckedCast(EMPTY);
    }

}