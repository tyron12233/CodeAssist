package org.gradle.api.execution.internal;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.file.FileCollectionInternal;
import org.gradle.internal.service.scopes.Scope.Global;
import org.gradle.internal.service.scopes.ServiceScope;

/**
 * Allows the registration of {@link TaskInputsListener task inputs listeners}.
 */
@ServiceScope(Global.class)
public interface TaskInputsListeners {

    /**
     * Registers the listener with the build, the listener can be unregistered with {@link #removeListener(TaskInputsListener)}.
     */
    void addListener(TaskInputsListener listener);

    void removeListener(TaskInputsListener listener);

    void broadcastFileSystemInputsOf(TaskInternal task, FileCollectionInternal fileSystemInputs);
}
