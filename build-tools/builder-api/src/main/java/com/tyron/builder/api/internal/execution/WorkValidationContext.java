package com.tyron.builder.api.internal.execution;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

import java.util.List;
import java.util.Optional;

public interface WorkValidationContext {
    TypeValidationContext forType(Class<?> type, boolean cacheable);

    List<String> getProblems();

    ImmutableSet<Class<?>> getValidatedTypes();

    interface TypeOriginInspector {
        TypeOriginInspector NO_OP = type -> Optional.empty();

        Optional<String> findPluginDefining(Class<?> type);
    }
}