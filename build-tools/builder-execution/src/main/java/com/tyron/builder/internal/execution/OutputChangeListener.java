package com.tyron.builder.internal.execution;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@EventScope(Scopes.Build.class)
@ServiceScope(Scopes.Build.class)
public interface OutputChangeListener {
    /**
     * Invoked when the outputs of a work item are about to change.
     * This happens for example just before the task actions are executed or the outputs are loaded from the cache.
     *
     * @param affectedOutputPaths The files which are affected by the change.
     */
    void beforeOutputChange(Iterable<String> affectedOutputPaths);
}