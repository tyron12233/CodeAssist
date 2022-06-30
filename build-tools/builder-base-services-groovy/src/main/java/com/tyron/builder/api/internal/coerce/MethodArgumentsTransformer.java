package com.tyron.builder.api.internal.coerce;

import org.codehaus.groovy.reflection.CachedClass;

/**
 * Potentially transforms arguments to call a method with.
 */
public interface MethodArgumentsTransformer {

    /**
     * Transforms an argument list to call a method with.
     *
     * May return {@code args} if no transform is necessary.
     */
    Object[] transform(CachedClass[] types, Object[] args);

    /*
     * Returns Whether the transformer can transform
     * these arguments at all.
     */
    boolean canTransform(Object[] args);

}
