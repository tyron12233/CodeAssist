package com.tyron.builder.internal.execution.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.internal.execution.WorkValidationContext;
import com.tyron.builder.internal.reflect.ProblemRecordingTypeValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.internal.reflect.validation.TypeValidationProblem;
import com.tyron.builder.plugin.use.PluginId;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class DefaultWorkValidationContext implements WorkValidationContext {
    private final Set<Class<?>> types = new HashSet<>();
    private final ImmutableList.Builder<TypeValidationProblem> problems = ImmutableList.builder();
    private DocumentationRegistry documentationRegistry;
    private final TypeOriginInspector typeOriginInspector;

    public DefaultWorkValidationContext(DocumentationRegistry documentationRegistry, TypeOriginInspector typeOriginInspector) {
        this.documentationRegistry = documentationRegistry;
        this.typeOriginInspector = typeOriginInspector;
    }

    @Override
    public TypeValidationContext forType(Class<?> type, boolean cacheable) {
        types.add(type);
        Supplier<Optional<PluginId>> pluginId = () -> typeOriginInspector.findPluginDefining(type);
        return new ProblemRecordingTypeValidationContext(documentationRegistry, type, pluginId) {

            @Override
            protected void recordProblem(TypeValidationProblem problem) {
                boolean onlyAffectsCacheableWork = problem.isOnlyAffectsCacheableWork();
                if (onlyAffectsCacheableWork && !cacheable) {
                    return;
                }
                problems.add(problem);
            }
        };
    }

    @Override
    public List<TypeValidationProblem> getProblems() {
        return problems.build();
    }

    public ImmutableSortedSet<Class<?>> getValidatedTypes() {
        return ImmutableSortedSet.copyOf(Comparator.comparing(Class::getName), types);
    }
}