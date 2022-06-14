package com.tyron.builder.api.specs;

/**
 * A {@link com.tyron.builder.api.specs.Spec} implementation which negates another {@code Spec}.
 * 
 * @param <T> The target type for this Spec
 */
public class NotSpec<T> implements Spec<T> {
    private Spec<? super T> sourceSpec;

    public NotSpec(Spec<? super T> sourceSpec) {
        this.sourceSpec = sourceSpec;
    }

    Spec<? super T> getSourceSpec() {
        return sourceSpec;
    }

    @Override
    public boolean isSatisfiedBy(T element) {
        return !sourceSpec.isSatisfiedBy(element);
    }
}
