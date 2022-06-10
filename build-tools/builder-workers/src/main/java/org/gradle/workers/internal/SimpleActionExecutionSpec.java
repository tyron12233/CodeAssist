package org.gradle.workers.internal;

import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;

public class SimpleActionExecutionSpec<T extends WorkParameters> {
    private final Class<? extends WorkAction<T>> implementationClass;
    private final T params;
    private final boolean usesInternalServices;

    public SimpleActionExecutionSpec(Class<? extends WorkAction<T>> implementationClass, T params, boolean usesInternalServices) {
        this.implementationClass = implementationClass;
        this.params = params;
        this.usesInternalServices = usesInternalServices;
    }

    public boolean isInternalServicesRequired() {
        return usesInternalServices;
    }

    public Class<? extends WorkAction<T>> getImplementationClass() {
        return implementationClass;
    }

    public T getParameters() {
        return params;
    }
}
