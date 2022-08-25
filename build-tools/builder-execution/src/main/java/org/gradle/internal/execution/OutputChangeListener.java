package org.gradle.internal.execution;

import org.gradle.internal.service.scopes.EventScope;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@EventScope(Scopes.Build.class)
@ServiceScope(Scopes.Build.class)
public interface OutputChangeListener {
    /**
     * Invoked when some locations on disk have been changed or are about to be changed.
     * This happens for example just before and after the task actions are executed or the outputs are loaded from the cache.
     *
     * @param affectedOutputPaths The files which are affected by the change.
     */
    void invalidateCachesFor(Iterable<String> affectedOutputPaths);
}