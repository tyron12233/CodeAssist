package org.gradle.internal.execution;

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.internal.reflect.validation.TypeValidationProblem;
import org.gradle.plugin.use.PluginId;

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