package com.tyron.builder.internal.execution;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationProblem;
import com.tyron.builder.plugin.use.PluginId;

import java.util.List;
import java.util.Optional;

public interface WorkValidationContext {
    TypeValidationContext forType(Class<?> type, boolean cacheable);

    List<TypeValidationProblem> getProblems();

    ImmutableSet<Class<?>> getValidatedTypes();

    interface TypeOriginInspector {
        TypeOriginInspector NO_OP = type -> Optional.empty();

        Optional<PluginId> findPluginDefining(Class<?> type);
    }
}