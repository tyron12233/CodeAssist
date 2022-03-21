package com.tyron.builder.api.internal.execution.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.api.internal.execution.WorkValidationContext;
import com.tyron.builder.api.internal.execution.WorkValidationContext.TypeOriginInspector;
import com.tyron.builder.api.internal.reflect.validation.TypeValidationContext;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class DefaultWorkValidationContext implements WorkValidationContext {
    private final Set<Class<?>> types = new HashSet<>();
    private final ImmutableList.Builder<String> problems = ImmutableList.builder();
    private final TypeOriginInspector typeOriginInspector;

    public DefaultWorkValidationContext(TypeOriginInspector typeOriginInspector) {
        this.typeOriginInspector = typeOriginInspector;
    }

    @Override
    public TypeValidationContext forType(Class<?> type, boolean cacheable) {
        types.add(type);
        return null;
    }

    @Override
    public List<String> getProblems() {
        return problems.build();
    }

    public ImmutableSortedSet<Class<?>> getValidatedTypes() {
        return ImmutableSortedSet.copyOf(Comparator.comparing(Class::getName), types);
    }
}