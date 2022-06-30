package com.tyron.builder.initialization.exception;

import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;
import java.util.List;

@ServiceScope(Scopes.BuildTree.class)
public interface ExceptionAnalyser {
    /**
     * Transforms the given build failure to add context where relevant and to remove unnecessary noise.
     *
     * <p>Note that the argument may be mutated as part of the transformation, for example its causes or stack trace may be changed.</p>
     */
    RuntimeException transform(Throwable failure);

    /**
     * Transforms and combines the given failures into a single build failure.
     *
     * @return null if the list of exceptions is empty.
     */
    @Nullable
    RuntimeException transform(List<Throwable> failures);
}