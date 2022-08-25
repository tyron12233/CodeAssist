package org.gradle.api.internal.tasks.execution;

import groovy.lang.Closure;
import org.gradle.api.specs.AndSpec;
import org.gradle.api.specs.CompositeSpec;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.internal.ClosureSpec;
import org.gradle.internal.Cast;

import java.util.function.IntFunction;

import javax.annotation.Nullable;

/**
 * A {@link CompositeSpec} that requires all component specs to provide a description.
 * The aggregation is based on an {@link AndSpec}.
 *
 * @param <T> The target type for this Spec
 */
public class DescribingAndSpec<T> extends CompositeSpec<T> {
    private static final DescribingAndSpec<?> EMPTY = new DescribingAndSpec<>();
    private final AndSpec<T> specHolder;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private DescribingAndSpec(AndSpec<T> specHolder) {
        super(specHolder.getSpecs().toArray(
                (Spec[]) Cast.uncheckedCast(new Spec[0])));
        this.specHolder = specHolder;
    }

    public DescribingAndSpec() {
        this(AndSpec.empty());
    }

    public DescribingAndSpec(Spec<? super T> spec, String description) {
        this(new AndSpec<>(new SelfDescribingSpec<>(spec, description)));
    }

    @Override
    public boolean isSatisfiedBy(T element) {
        return specHolder.isSatisfiedBy(element);
    }

    @Nullable
    public SelfDescribingSpec<? super T> findUnsatisfiedSpec(T element) {
        return Cast.uncheckedCast(specHolder.findUnsatisfiedSpec(element));
    }

    public DescribingAndSpec<T> and(Spec<? super T> spec, String description) {
        return new DescribingAndSpec<>(specHolder.and(new SelfDescribingSpec<>(spec, description)));
    }

    @SuppressWarnings("rawtypes")
    public DescribingAndSpec<T> and(Closure closure, String description) {
        return and(new ClosureSpec<>(closure), description);
    }

    public static <T> DescribingAndSpec<T> empty() {
        return Cast.uncheckedNonnullCast(EMPTY);
    }
}