package org.gradle.api.internal.tasks.properties;

import org.gradle.api.Task;
import org.gradle.internal.instantiation.InstantiationScheme;

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