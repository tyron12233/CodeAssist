package com.tyron.builder.workers.internal;

import com.tyron.builder.workers.WorkAction;
import com.tyron.builder.workers.WorkParameters;

public interface ActionExecutionSpecFactory {
    <T extends WorkParameters> TransportableActionExecutionSpec newTransportableSpec(IsolatedParametersActionExecutionSpec<T> spec);

    <T extends WorkParameters> IsolatedParametersActionExecutionSpec<T> newIsolatedSpec(String displayName, Class<? extends WorkAction<T>> implementationClass, T params, WorkerRequirement workerRequirement, boolean usesInternalServices);

    <T extends WorkParameters> SimpleActionExecutionSpec<T> newSimpleSpec(IsolatedParametersActionExecutionSpec<T> spec);

    <T extends WorkParameters> SimpleActionExecutionSpec<T> newSimpleSpec(TransportableActionExecutionSpec spec);
}
