package com.tyron.builder.api.internal.tasks.properties;

import com.tyron.builder.api.Task;
import com.tyron.builder.internal.instantiation.InstantiationScheme;

public class TaskScheme implements TypeScheme {
    private final InstantiationScheme instantiationScheme;
    private final InspectionScheme inspectionScheme;

    public TaskScheme(InstantiationScheme instantiationScheme, InspectionScheme inspectionScheme) {
        this.instantiationScheme = instantiationScheme;
        this.inspectionScheme = inspectionScheme;
    }

    @Override
    public TypeMetadataStore getMetadataStore() {
        return inspectionScheme.getMetadataStore();
    }

    @Override
    public boolean appliesTo(Class<?> type) {
        return Task.class.isAssignableFrom(type);
    }

    public InstantiationScheme getInstantiationScheme() {
        return instantiationScheme;
    }

    public InspectionScheme getInspectionScheme() {
        return inspectionScheme;
    }
}